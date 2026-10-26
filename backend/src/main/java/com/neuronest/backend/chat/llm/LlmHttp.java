package com.neuronest.backend.chat.llm;

import com.neuronest.backend.chat.llm.LlmProviderException.Kind;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * HTTP plumbing shared by the providers: a timed JSON POST, and an SSE reader
 * that enforces a first-token deadline and an idle timeout between chunks.
 * Every failure becomes an {@link LlmProviderException}.
 */
final class LlmHttp {

    /**
     * What one SSE "data:" payload contributed: text to show, whether the reply is complete,
     * and token usage if the provider reported it in this chunk.
     */
    record Chunk(String text, boolean end, LlmUsage usage) {
        static final Chunk EMPTY = new Chunk("", false, null);
        static final Chunk END = new Chunk("", true, null);

        Chunk(String text, boolean end) {
            this(text, end, null);
        }
    }

    /** Provider-specific decoding of one SSE "data:" payload. */
    @FunctionalInterface
    interface ChunkParser {
        Chunk parse(String data);
    }

    // Queue items besides body lines (Strings).
    /** A non-200 response, with its error body. */
    private record Status(int code, String body) {}
    private record Failure(Throwable error) {}
    private static final Object COMPLETE = new Object();

    private final String provider;
    private final LlmSettings settings;
    private final HttpClient client;

    LlmHttp(String provider, LlmSettings settings) {
        this.provider = provider;
        this.settings = settings;
        this.client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
    }

    static HttpRequest.BodyPublisher json(String body) {
        return HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    }

    /** POSTs and returns the 200 response body; any other outcome throws. */
    String postForString(HttpRequest request) {
        return postForString(request, settings.jsonTimeout());
    }

    /** As {@link #postForString(HttpRequest)}, with its own deadline (e.g. a long audio upload). */
    String postForString(HttpRequest request, Duration timeout) {
        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmProviderException(provider, Kind.TIMEOUT, null, false, e);
        } catch (ExecutionException e) {
            throw transportFailure(e.getCause(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new LlmProviderException(provider, Kind.CONNECTION, null, false, e);
        }
        if (response.statusCode() != 200) {
            throw LlmProviderException.forStatus(provider, response.statusCode(), response.body());
        }
        return response.body();
    }

    /**
     * POSTs a streaming request and feeds each SSE "data:" payload through {@code parser},
     * passing non-empty text to {@code onToken}. Returns once the parser reports the end
     * of the reply. Throws if the stream fails, stalls, or ends early; the exception records
     * whether any text had already been passed on. Exceptions from {@code onToken} propagate
     * unchanged (and abort the request).
     *
     * @return the last token usage the provider reported, or null
     */
    LlmUsage stream(HttpRequest request, ChunkParser parser, Consumer<String> onToken) {
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        LineSubscriber lines = new LineSubscriber(events);
        BodyHandler<Void> handler = info -> {
            if (info.statusCode() == 200) {
                return BodySubscribers.fromLineSubscriber(lines);
            }
            // Read the (small) error body so the failure can say why.
            return BodySubscribers.mapping(BodySubscribers.ofString(StandardCharsets.UTF_8), body -> {
                events.add(new Status(info.statusCode(), body));
                return null;
            });
        };
        CompletableFuture<HttpResponse<Void>> exchange = client.sendAsync(request, handler);
        exchange.whenComplete((response, error) -> {
            if (error != null) {
                events.add(new Failure(error));
            }
        });

        boolean emitted = false;
        boolean finished = false;
        LlmUsage usage = null;
        try {
            long firstTokenDeadline = System.nanoTime() + settings.firstTokenTimeout().toNanos();
            while (true) {
                long waitNanos = emitted
                        ? settings.idleTimeout().toNanos()
                        : firstTokenDeadline - System.nanoTime();
                Object event = waitNanos <= 0 ? null : events.poll(waitNanos, TimeUnit.NANOSECONDS);

                if (event == null) {
                    throw new LlmProviderException(provider, Kind.TIMEOUT, null, emitted, null);
                }
                if (event instanceof Failure failure) {
                    throw transportFailure(failure.error(), emitted);
                }
                if (event instanceof Status status) {
                    throw LlmProviderException.forStatus(provider, status.code(), status.body());
                }
                if (event == COMPLETE) {
                    // Body closed before the provider said the reply was complete.
                    throw new LlmProviderException(provider, Kind.CONNECTION, null, emitted, null);
                }

                String line = (String) event;
                if (!line.startsWith("data:")) {
                    continue; // "event:", "id:", comments, blank separators
                }
                Chunk chunk;
                try {
                    chunk = parser.parse(line.substring(5).trim());
                } catch (LlmProviderException e) {
                    throw e.withTokensEmitted(emitted);
                }
                if (chunk.usage() != null) {
                    usage = chunk.usage();
                }
                if (!chunk.text().isEmpty()) {
                    onToken.accept(chunk.text());
                    emitted = true;
                }
                if (chunk.end()) {
                    if (!emitted) {
                        throw LlmProviderException.badResponse(provider, "empty reply");
                    }
                    finished = true;
                    return usage;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(provider, Kind.CONNECTION, null, emitted, e);
        } finally {
            if (!finished) {
                lines.cancel();
                exchange.cancel(true);
            }
        }
    }

    private LlmProviderException transportFailure(Throwable error, boolean emitted) {
        Throwable cause = error;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof LlmProviderException e) {
            return e.withTokensEmitted(emitted);
        }
        Kind kind = cause instanceof HttpTimeoutException ? Kind.TIMEOUT : Kind.CONNECTION;
        return new LlmProviderException(provider, kind, null, emitted, cause);
    }

    /** Pushes body lines (and completion/failure) onto the queue the reader thread polls. */
    private static final class LineSubscriber implements Flow.Subscriber<String> {

        private final BlockingQueue<Object> events;
        private volatile Flow.Subscription subscription;

        LineSubscriber(BlockingQueue<Object> events) {
            this.events = events;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            events.add(line);
        }

        @Override
        public void onError(Throwable throwable) {
            events.add(new Failure(throwable));
        }

        @Override
        public void onComplete() {
            events.add(COMPLETE);
        }

        void cancel() {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
        }
    }
}
