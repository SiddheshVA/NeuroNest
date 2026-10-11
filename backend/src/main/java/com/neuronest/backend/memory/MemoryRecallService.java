package com.neuronest.backend.memory;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.chat.ChatMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

// Looks up a logged-in user's memories for the current chat message: their core
// facts (name, preferred language, ...) always, plus the regular memories most
// similar to the message. Runs on the chat path, so it is bounded by
// memory.recall-budget and NEVER throws: a failure or timeout means "no memories
// this turn" (core facts are still returned if they were already loaded).
@Service
public class MemoryRecallService {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallService.class);
    private static final Duration IDENTITY_WAIT_LATER_MESSAGES = Duration.ofSeconds(1);

    private final MemoryProperties properties;
    private final UserRepository userRepository;
    private final GeminiEmbeddingClient embeddingClient;
    private final MemoryRepository memoryRepository;
    private final MemoryExtractionService extractionService;
    private final ExecutorService recallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MemoryRecallService(MemoryProperties properties,
                               UserRepository userRepository,
                               GeminiEmbeddingClient embeddingClient,
                               MemoryRepository memoryRepository,
                               MemoryExtractionService extractionService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.embeddingClient = embeddingClient;
        this.memoryRepository = memoryRepository;
        this.extractionService = extractionService;
    }

    // userId must be the authenticated owner of the session; callers pass null
    // for anonymous chats and recall is skipped.
    public List<String> recall(UUID userId, List<ChatMessage> history, String userMessage) {
        if (userId == null || !properties.enabled() || userMessage == null || userMessage.isBlank()) {
            return List.of();
        }
        // The crisis classifier runs inside the reply call itself, so we can't know
        // yet whether THIS message will be flagged. Instead, once the conversation
        // has been flagged at any point, it gets no searched memories from then on;
        // core facts (name, preferred language) are still included. Only replies
        // count: a user message is also flagged when no verdict could be obtained (to
        // keep it out of memory), which is not a disclosure.
        boolean crisisConversation = history.stream().anyMatch(m -> "assistant".equals(m.getRole()) && m.isRiskFlag());

        // The first message of a conversation may land on a database that is still
        // waking up, and first extracts the user's earlier conversations, so it gets
        // a longer budget.
        boolean firstMessage = history.isEmpty();
        Duration budget = firstMessage ? properties.firstMessageRecallBudget() : properties.recallBudget();
        AtomicReference<List<String>> coreFacts = new AtomicReference<>(List.of());
        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(
                () -> doRecall(userId, userMessage, firstMessage, crisisConversation, coreFacts), recallExecutor);
        try {
            return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Memory recall exceeded {} budget; replying with {} core facts only", budget,
                    coreFacts.get().size());
            return coreFacts.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GeminiRetryableException) {
                // Already warned once by GeminiBackoff; recall is skipped fast until it clears.
                log.debug("Memory recall skipped: {}", e.getCause().getMessage());
            } else {
                log.warn("Memory recall failed; replying without memories: {}", rootMessage(e));
            }
        } catch (Exception e) {
            log.warn("Memory recall failed; replying without memories: {}", rootMessage(e));
        }
        return List.of();
    }

    // Fire-and-forget: wakes the database (Neon suspends when idle) when a
    // logged-in user opens or starts a conversation, so recall on their first
    // message doesn't pay the cold start. Never throws, never blocks the caller.
    public void warmUp(UUID userId) {
        if (userId == null || !properties.enabled()) {
            return;
        }
        try {
            recallExecutor.execute(() -> {
                try {
                    memoryRepository.warmUp(userId);
                } catch (Exception e) {
                    log.debug("Memory warm-up failed: {}", rootMessage(e));
                }
            });
        } catch (Exception e) {
            log.debug("Memory warm-up not scheduled: {}", e.toString());
        }
    }

    private List<String> doRecall(UUID userId, String userMessage, boolean firstMessage, boolean crisisConversation,
                                  AtomicReference<List<String>> coreFacts) {
        boolean enabled = userRepository.findById(userId).map(User::isMemoryEnabled).orElse(false);
        if (!enabled) {
            return List.of();
        }
        if (firstMessage) {
            flushEarlierConversations(userId);
        } else {
            // An identity fact from a turn that just finished (e.g. in another tab) may still be saving.
            extractionService.awaitIdentityFacts(userId, IDENTITY_WAIT_LATER_MESSAGES);
        }

        List<String> core = memoryRepository.findCoreFacts(userId);
        coreFacts.set(core);
        if (crisisConversation) {
            log.info("Memory recall: {} core facts; search skipped (crisis earlier in this conversation)", core.size());
            return core;
        }

        List<MemoryRepository.RecalledMemory> found;
        try {
            found = memoryRepository.findMostSimilar(userId, embeddingClient.embed(userMessage),
                    properties.recallTopK());
        } catch (GeminiRetryableException e) {
            // Already warned once by GeminiBackoff; the search is skipped fast until it clears.
            log.debug("Memory search skipped: {}", e.getMessage());
            return core;
        } catch (Exception e) {
            log.warn("Memory search failed; replying with {} core facts only: {}", core.size(), rootMessage(e));
            return core;
        }
        List<MemoryRepository.RecalledMemory> relevant = found.stream()
                .filter(m -> m.similarity() >= properties.recallMinSimilarity())
                .toList();
        // Scores only, never content.
        log.info("Memory recall: {} core facts; search returned {} memories with similarity {}; {} at or above {}",
                core.size(), found.size(),
                found.stream().map(m -> String.format(Locale.ROOT, "%.3f", m.similarity())).toList(),
                relevant.size(), properties.recallMinSimilarity());

        memoryRepository.markUsed(userId, relevant.stream().map(MemoryRepository.RecalledMemory::id).toList());
        List<String> memories = new ArrayList<>(core);
        relevant.forEach(m -> memories.add(m.content()));
        return memories;
    }

    // A new conversation means the user's earlier ones are over. First wait for an identity
    // save still running from their last turn (e.g. "my name is ..." right before opening
    // this chat), then extract the rest of those conversations. Bounded in total by
    // memory.first-message-flush-budget; if it runs over, it finishes in the background
    // and this turn uses what is already stored.
    private void flushEarlierConversations(UUID userId) {
        long deadline = System.nanoTime() + properties.firstMessageFlushBudget().toNanos();
        extractionService.awaitIdentityFacts(userId, properties.firstMessageFlushBudget());
        long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
        if (remainingMs <= 0) {
            return;
        }
        CompletableFuture<Void> flush =
                CompletableFuture.runAsync(() -> extractionService.flushPendingForUser(userId), recallExecutor);
        try {
            flush.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("Memory: extracting earlier conversations took over {}; recalling without waiting",
                    properties.firstMessageFlushBudget());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("Memory: extracting earlier conversations failed: {}", rootMessage(e));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    @PreDestroy
    void shutdown() {
        recallExecutor.shutdownNow();
    }
}
