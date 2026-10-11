package com.neuronest.backend.kb;

import com.neuronest.backend.kb.KbChunk.SectionKind;
import com.neuronest.backend.memory.GeminiEmbeddingClient;
import com.neuronest.backend.memory.GeminiRetryableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Finds the wellness-library sections relevant to the latest chat message. Runs on the chat
// path in parallel with memory recall, bounded by kb.retrieval-budget, and NEVER fails the
// turn: any error, timeout or skip means "no background knowledge this turn".
@Service
public class KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    // Words that make even a very short message worth a search (English, Hinglish, Marathi).
    private static final List<String> WELLNESS_WORDS = List.of("stress", "anxi", "panic", "worr", "overthink",
            "sleep", "insomnia", "neend", "nind", "tired", "exhaust", "thak", "sad", "low", "lonel", "alone",
            "akela", "cry", "rona", "exam", "pressure", "tension", "scared", "fear", "dar", "ghabra", "pareshan",
            "chinta", "udaas", "focus", "breath", "calm", "relax", "meditat", "mood", "angry", "gussa", "hurt",
            "depress", "burnout", "nervous", "overwhelm", "mind", "dimag");
    private static final Set<String> GREETING_OR_THANKS = Set.of("hi", "hii", "hiii", "hello", "hey", "heyy",
            "namaste", "namaskar", "thanks", "thank", "thankyou", "thx", "ty", "shukriya", "dhanyavaad",
            "dhanyawad", "ok", "okay", "okk", "bye", "gm", "gn", "good");
    private static final int SHORT_MESSAGE_WORDS = 4;

    private final KbProperties properties;
    private final KbRepository repository;
    private final GeminiEmbeddingClient embeddingClient;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public KnowledgeRetrievalService(KbProperties properties, KbRepository repository,
                                     GeminiEmbeddingClient embeddingClient) {
        this.properties = properties;
        this.repository = repository;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Starts retrieval in the background. The future always completes normally (empty on skip,
     * failure or timeout) within kb.retrieval-budget.
     *
     * @param earlierCrisis an earlier turn in this conversation was a crisis turn
     * @param crisisSignal the latest message matches a crisis phrase: crisis messages never
     *        go through retrieval
     */
    public CompletableFuture<List<KbChunk>> retrieveAsync(String message, String summary, boolean earlierCrisis,
                                                          boolean crisisSignal) {
        String skip = skipReason(message, earlierCrisis, crisisSignal);
        if (skip != null) {
            log.debug("Knowledge retrieval skipped: {}", skip);
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> retrieve(message, summary), executor)
                .exceptionally(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof GeminiRetryableException) {
                        log.debug("Knowledge retrieval skipped: {}", cause.getMessage());
                    } else {
                        log.warn("Knowledge retrieval failed; replying without it: {}", cause.toString());
                    }
                    return List.of();
                })
                .completeOnTimeout(List.of(), properties.retrievalBudget().toMillis(), TimeUnit.MILLISECONDS);
    }

    String skipReason(String message, boolean earlierCrisis, boolean crisisSignal) {
        if (!properties.enabled()) {
            return "disabled";
        }
        if (crisisSignal) {
            return "crisis signal in the latest message";
        }
        if (earlierCrisis && properties.skipAfterCrisis()) {
            return "crisis earlier in this conversation";
        }
        if (message == null || message.isBlank() || isSmallTalk(message)) {
            return "greeting, thanks or short small talk";
        }
        return null;
    }

    /** Short (under 4 words) or greeting/thanks-led messages without any wellness word. */
    static boolean isSmallTalk(String message) {
        List<String> words = List.of(message.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")).stream()
                .filter(w -> !w.isEmpty()).toList();
        boolean wellness = words.stream().anyMatch(w -> WELLNESS_WORDS.stream().anyMatch(w::startsWith));
        if (wellness) {
            return false;
        }
        return words.size() < SHORT_MESSAGE_WORDS || GREETING_OR_THANKS.contains(words.getFirst());
    }

    private List<KbChunk> retrieve(String message, String summary) {
        float[] query = embeddingClient.embed(queryText(message, summary));
        List<KbChunk> candidates = repository.findMostSimilar(query, properties.candidatePool());
        List<KbChunk> chosen = select(candidates);
        log.debug("Knowledge retrieval: chose {} of {} candidates: {}", chosen.size(), candidates.size(),
                chosen.stream().map(c -> c.chunkId() + String.format(Locale.ROOT, "(%.3f)", c.similarity()))
                        .toList());
        return chosen;
    }

    String queryText(String message, String summary) {
        if (summary == null || summary.isBlank() || properties.summaryContextChars() <= 0) {
            return message;
        }
        String trimmed = summary.trim();
        String tail = trimmed.length() <= properties.summaryContextChars() ? trimmed
                : trimmed.substring(trimmed.length() - properties.summaryContextChars());
        return message + "\n\nConversation so far: " + tail;
    }

    /**
     * Above the threshold only; Overview and practical sections preferred; a "Safety and
     * limitations" section only when it is the top match or its doc is safety_level medium.
     */
    List<KbChunk> select(List<KbChunk> candidates) {
        List<KbChunk> relevant = candidates.stream()
                .filter(c -> c.similarity() >= properties.minSimilarity())
                .sorted(Comparator.comparingDouble(KbChunk::similarity).reversed())
                .toList();
        List<KbChunk> allowed = new ArrayList<>();
        for (int i = 0; i < relevant.size(); i++) {
            KbChunk chunk = relevant.get(i);
            if (chunk.kind() != SectionKind.SAFETY || i == 0 || "medium".equals(chunk.safetyLevel())) {
                allowed.add(chunk);
            }
        }
        return allowed.stream()
                .sorted(Comparator.comparingDouble(this::score).reversed())
                .limit(properties.topK())
                .toList();
    }

    private double score(KbChunk chunk) {
        boolean preferred = chunk.kind() == SectionKind.OVERVIEW || chunk.kind() == SectionKind.PRACTICAL;
        return chunk.similarity() + (preferred ? properties.preferredSectionBoost() : 0);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
