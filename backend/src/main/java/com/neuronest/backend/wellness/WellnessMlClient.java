package com.neuronest.backend.wellness;

import com.neuronest.backend.wellness.WellnessDtos.FieldError;
import com.neuronest.backend.wellness.WellnessMlDtos.MlCheckinRequest;
import com.neuronest.backend.wellness.WellnessMlDtos.MlCheckinResult;
import com.neuronest.backend.wellness.WellnessMlDtos.MlError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Calls the internal ML service (POST /v1/wellness/checkin) with the shared secret in X-Internal-Key.
 * The app starts whether or not the service is up or the key is set; a check-in then answers 503.
 * <p>
 * Privacy: the answers and results are health data. Only a per-call request id, the HTTP status (or the kind
 * of failure) and the latency are logged: never the body, the key or the user.
 */
@Component
public class WellnessMlClient {

    static final String CHECKIN_PATH = "/v1/wellness/checkin";
    static final String KEY_HEADER = "X-Internal-Key";
    static final String REQUEST_ID_HEADER = "X-Request-ID";

    private static final Logger log = LoggerFactory.getLogger(WellnessMlClient.class);

    private final String checkinUrl;
    private final String internalKey;
    private final RestClient http;

    @Autowired
    public WellnessMlClient(@Value("${wellness.ml.base-url:http://127.0.0.1:8001}") String baseUrl,
                            @Value("${wellness.ml.internal-key:}") String internalKey,
                            @Value("${wellness.ml.connect-timeout:PT2S}") Duration connectTimeout,
                            @Value("${wellness.ml.read-timeout:PT3S}") Duration readTimeout) {
        this(baseUrl, internalKey, RestClient.builder().requestFactory(requestFactory(connectTimeout, readTimeout)));
        // Once, at startup: whether the key is present (never its value).
        if (this.internalKey == null) {
            log.warn("Wellness ML service: wellness.ml.internal-key is not set (WELLNESS_INTERNAL_KEY); check-ins "
                    + "will only work against an ML service running in dev mode.");
        } else {
            log.info("Wellness ML service configured at {} (internal key present).", stripTrailingSlash(baseUrl));
        }
    }

    // With an explicit RestClient builder (tests bind a mock server or a short-timeout factory to it).
    WellnessMlClient(String baseUrl, String internalKey, RestClient.Builder http) {
        this.checkinUrl = stripTrailingSlash(baseUrl) + CHECKIN_PATH;
        this.internalKey = internalKey == null || internalKey.isBlank() ? null : internalKey.trim();
        this.http = http.build();
    }

    static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /**
     * Scores one check-in.
     *
     * @throws WellnessExceptions.InvalidInputException the service rejected the answers (its 422)
     * @throws WellnessExceptions.UnavailableException anything else that is not a usable 200
     */
    public MlCheckinResult checkin(MlCheckinRequest request) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        long start = System.nanoTime();
        String outcome = "error";
        try {
            MlCheckinResult result = http.post()
                    .uri(checkinUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(REQUEST_ID_HEADER, requestId)
                    .headers(h -> {
                        if (internalKey != null) h.set(KEY_HEADER, internalKey);
                    })
                    .body(request)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 200) {
                            MlCheckinResult body = res.bodyTo(MlCheckinResult.class);
                            if (body == null || !body.isComplete()) throw new IncompleteResponse();
                            return body;
                        }
                        if (status == 422) {
                            throw new WellnessExceptions.InvalidInputException(fieldErrors(res.bodyTo(MlError.class)));
                        }
                        throw new HttpFailure(status);
                    });
            outcome = "200";
            return result;
        } catch (WellnessExceptions.InvalidInputException e) {
            outcome = "422";
            throw e;
        } catch (HttpFailure e) {
            outcome = String.valueOf(e.status);
            if (e.status == 401 || e.status == 403) {
                log.error("Wellness ML service refused the internal key (HTTP {}): check WELLNESS_INTERNAL_KEY on both "
                        + "services.", e.status);
            }
            throw new WellnessExceptions.UnavailableException();
        } catch (IncompleteResponse e) {
            outcome = "200-incomplete";
            throw new WellnessExceptions.UnavailableException();
        } catch (RestClientException e) {
            outcome = failureKind(e);
            throw new WellnessExceptions.UnavailableException();
        } catch (RuntimeException e) {
            outcome = "error-" + e.getClass().getSimpleName();
            throw new WellnessExceptions.UnavailableException();
        } finally {
            log.info("wellness ml call request_id={} status={} latency_ms={}", requestId, outcome,
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    private static List<FieldError> fieldErrors(MlError error) {
        if (error == null || error.error() == null || error.error().fields() == null) {
            return List.of(new FieldError(null, "Some check-in answers are invalid."));
        }
        return error.error().fields().stream()
                .map(f -> new FieldError(WellnessInputs.apiFieldName(f.field()),
                        f.message() == null ? "invalid value" : f.message()))
                .toList();
    }

    private static String failureKind(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof java.net.SocketTimeoutException) return "timeout";
            if (t instanceof ConnectException) return "unreachable";
        }
        return "io-error";
    }

    private static String stripTrailingSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** A non-200, non-422 answer; only its status is kept. */
    private static final class HttpFailure extends RuntimeException {
        final int status;

        HttpFailure(int status) {
            super(null, null, false, false);
            this.status = status;
        }
    }

    /** A 200 without the fields the backend relies on. */
    private static final class IncompleteResponse extends RuntimeException {
        IncompleteResponse() {
            super(null, null, false, false);
        }
    }
}
