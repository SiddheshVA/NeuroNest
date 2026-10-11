package com.neuronest.backend.memory;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.chat.ChatConversation;
import com.neuronest.backend.chat.ChatConversationRepository;
import com.neuronest.backend.chat.ChatMessage;
import com.neuronest.backend.chat.ChatMessageRepository;
import com.neuronest.backend.chat.llm.LlmUnavailableException;
import com.neuronest.backend.session.Session;
import com.neuronest.backend.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// Background "save" side of long-term memory. Turns unprocessed user messages
// of a logged-in user's conversation into stored facts: core facts (name,
// preferred language, ...; one per key, replaced when they change) and regular
// facts (deduplicated; an outdated one is rewritten instead of duplicated).
// Extraction goes through the LLM router (Groq first, Gemini fallback);
// embeddings use Gemini.
//
// Triggers:
//  - identity facts (name, preferred name, preferred language): right after the
//    turn they appear in (onTurnCompleted -> saveIdentityFactsNow)
//  - every N unprocessed user messages in a conversation (onTurnCompleted)
//  - the first message of a user's new conversation: their other unfinished ones
//    are flushed (by recall, before it searches, and again here)
//  - when a conversation has been idle for memory.idle-after (scheduled sweep)
//
// Safety: anonymous sessions are never processed; crisis-flagged messages are
// never sent to extraction; memory_enabled=false means nothing is saved; every
// failure is caught and logged here and never reaches the chat request.
@Service
public class MemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);
    private static final int SWEEP_BATCH = 50;
    // Regular memories shown to extraction so it can spot duplicates and outdated facts.
    private static final int KNOWN_MEMORIES_FOR_EXTRACTION = 40;

    private final MemoryProperties properties;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final MemoryRepository memoryRepository;
    private final MemoryExtractionClient extractionClient;
    private final GeminiEmbeddingClient embeddingClient;
    private final GeminiBackoff backoff;

    // Last time a logged-in chat turn happened. The sweep stops querying the
    // database once the app has been quiet for a while so Neon can suspend.
    private volatile Instant lastActivity = Instant.now();
    // Identity saves in progress, per user (see saveIdentityFactsNow).
    private final Map<UUID, CompletableFuture<Void>> identityJobs = new ConcurrentHashMap<>();

    public MemoryExtractionService(MemoryProperties properties,
                                   ChatConversationRepository conversationRepository,
                                   ChatMessageRepository messageRepository,
                                   SessionRepository sessionRepository,
                                   UserRepository userRepository,
                                   MemoryRepository memoryRepository,
                                   MemoryExtractionClient extractionClient,
                                   GeminiEmbeddingClient embeddingClient,
                                   GeminiBackoff backoff) {
        this.properties = properties;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.memoryRepository = memoryRepository;
        this.extractionClient = extractionClient;
        this.embeddingClient = embeddingClient;
        this.backoff = backoff;
    }

    // Called by ChatService after a logged-in user's turn has been saved.
    @Async("memoryExecutor")
    public void onTurnCompleted(UUID userId, UUID conversationId, boolean conversationStarted) {
        lastActivity = Instant.now();
        if (!properties.enabled()) {
            return;
        }
        saveIdentityFactsNow(userId, conversationId);
        if (conversationStarted) {
            try {
                for (UUID previous : memoryRepository.findUnprocessedConversationsForUser(userId, conversationId)) {
                    processConversation(previous, true);
                }
            } catch (Exception e) {
                log.warn("Memory: failed to flush earlier conversations for a user: {}", e.toString());
            }
        }
        processConversation(conversationId, false);
    }

    // Identity facts (name, what to call them, preferred language) are saved right after
    // the turn they appear in, not with the next batch, so they are remembered even if the
    // user opens a new chat straight away. Only the latest user message is looked at, only
    // when it may state one (IdentityHints), and never when it or its reply is crisis-flagged.
    // Recall waits for this (awaitIdentityFacts) before reading core facts. Never throws.
    void saveIdentityFactsNow(UUID userId, UUID conversationId) {
        CompletableFuture<Void> job = new CompletableFuture<>();
        identityJobs.put(userId, job);
        try {
            List<ChatMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            int reply = messages.size() - 1;
            if (reply < 1 || !"assistant".equals(messages.get(reply).getRole())
                    || !"user".equals(messages.get(reply - 1).getRole())) {
                return;
            }
            // Crisis rule applies to this message alone: it (or its reply) flagged -> never stored.
            List<String> safe = nonCrisisUserMessages(messages.subList(reply - 1, reply + 1));
            if (safe.isEmpty() || !IdentityHints.mayStateIdentity(safe.getFirst())
                    || !isMemoryEnabled(userId) || backoff.isBlocked(properties.embeddingModel())) {
                return;
            }
            Map<String, String> identity = extractionClient.extractIdentity(safe.getFirst(),
                    memoryRepository.findForExtraction(userId, 0));
            for (Map.Entry<String, String> fact : identity.entrySet()) {
                memoryRepository.upsertCore(userId, fact.getKey(), fact.getValue(),
                        embeddingClient.embed(fact.getValue()), conversationId);
            }
            log.info("Memory: identity facts saved right after the turn in conversation {}: {}",
                    conversationId, identity.keySet());
        } catch (LlmUnavailableException e) {
            log.warn("Memory: identity extraction failed on every provider for conversation {} "
                    + "(the batch extraction will retry): {}", conversationId,
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (Exception e) {
            log.warn("Memory: identity extraction failed for conversation {}: {}", conversationId, e.toString());
        } finally {
            job.complete(null);
            identityJobs.remove(userId, job);
        }
    }

    // Waits (up to `timeout`) for an identity save still running for this user. Never throws.
    public void awaitIdentityFacts(UUID userId, Duration timeout) {
        CompletableFuture<Void> job = identityJobs.get(userId);
        if (job == null) {
            return;
        }
        try {
            job.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.info("Memory: identity save still running after {}; recalling without it", timeout);
        }
    }

    // Extracts every unfinished conversation of the user now (a new conversation is
    // starting, so earlier ones are over). Never throws.
    public void flushPendingForUser(UUID userId) {
        if (!properties.enabled()) {
            return;
        }
        try {
            for (UUID conversationId : memoryRepository.findUnprocessedConversationsForUser(userId)) {
                processConversation(conversationId, true);
            }
        } catch (Exception e) {
            log.warn("Memory: failed to flush earlier conversations for a user: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${memory.sweep-interval:PT10M}",
            initialDelayString = "${memory.sweep-initial-delay:PT2M}")
    public void sweepIdleConversations() {
        if (!properties.enabled()
                || lastActivity.isBefore(Instant.now().minus(properties.idleAfter().multipliedBy(2)))) {
            return;
        }
        try {
            for (UUID conversationId : memoryRepository.findIdleUnprocessedConversations(properties.idleAfter(), SWEEP_BATCH)) {
                processConversation(conversationId, true);
            }
        } catch (Exception e) {
            log.warn("Memory: idle sweep failed: {}", e.toString());
        }
    }

    // force=false only extracts once enough user messages have piled up;
    // force=true extracts whatever is pending (conversation ended / replaced).
    // Never throws.
    public void processConversation(UUID conversationId, boolean force) {
        try {
            doProcess(conversationId, force);
        } catch (Exception e) {
            log.warn("Memory: extraction failed for conversation {}: {}", conversationId, e.toString());
        }
    }

    private void doProcess(UUID conversationId, boolean force) {
        ChatConversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) {
            return;
        }
        UUID ownerId = sessionRepository.findById(conversation.getSessionId())
                .map(Session::getUserId)
                .orElse(null);
        if (ownerId == null) {
            return; // anonymous session: never saved
        }

        Instant processedUntil = memoryRepository.processedUntil(conversationId);
        List<ChatMessage> pending = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> processedUntil == null || m.getCreatedAt().isAfter(processedUntil))
                .toList();

        // Only consider messages up to the last assistant reply, so every user
        // message in the batch already has its final crisis flag.
        int lastReply = -1;
        for (int i = 0; i < pending.size(); i++) {
            if ("assistant".equals(pending.get(i).getRole())) {
                lastReply = i;
            }
        }
        if (lastReply < 0) {
            return;
        }
        pending = pending.subList(0, lastReply + 1);

        long userMessageCount = pending.stream().filter(m -> "user".equals(m.getRole())).count();
        if (userMessageCount == 0) {
            return;
        }
        boolean enabled = isMemoryEnabled(ownerId);
        if (enabled && !force && userMessageCount < properties.extractEveryUserMessages()) {
            return;
        }
        // Gemini embeddings asked us to wait: leave the batch unclaimed so a trigger
        // or the sweep picks it up once the back-off has passed.
        if (enabled && backoff.isBlocked(properties.embeddingModel())) {
            return;
        }

        // Claim this batch atomically; if another worker got there first, stop.
        Instant batchEnd = pending.getLast().getCreatedAt();
        if (!memoryRepository.moveProcessedUntil(conversationId, processedUntil, batchEnd)) {
            return;
        }
        if (!enabled) {
            return; // memory off: mark these messages processed without saving anything
        }

        List<String> safeMessages = nonCrisisUserMessages(pending);
        if (safeMessages.isEmpty()) {
            return;
        }

        List<MemoryRepository.KnownMemory> known =
                memoryRepository.findForExtraction(ownerId, KNOWN_MEMORIES_FOR_EXTRACTION);
        MemoryExtractionClient.Extraction extraction;
        try {
            extraction = extractionClient.extract(safeMessages, known);
        } catch (LlmUnavailableException e) {
            // Every provider failed (each failure is logged by the router): release the
            // claim so a later trigger or the sweep retries.
            log.warn("Memory: extraction failed on every provider for conversation {}; batch released for retry: {}",
                    conversationId, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            memoryRepository.moveProcessedUntil(conversationId, batchEnd, processedUntil);
            return;
        }
        try {
            saveFacts(ownerId, conversationId, extraction);
        } catch (GeminiRetryableException e) {
            // Embedding quota hit mid-batch: release it. On the retry, core facts are
            // upserted again and regular facts already saved are skipped as near-duplicates.
            log.warn("Memory: embedding rate-limited while saving conversation {}; batch released for retry",
                    conversationId);
            memoryRepository.moveProcessedUntil(conversationId, batchEnd, processedUntil);
        }
    }

    // User messages that neither carry a crisis flag themselves nor got a
    // crisis-flagged reply. (Older rows only have the flag on the reply.)
    static List<String> nonCrisisUserMessages(List<ChatMessage> messages) {
        List<String> safe = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (!"user".equals(message.getRole())) {
                continue;
            }
            boolean replyFlagged = i + 1 < messages.size()
                    && "assistant".equals(messages.get(i + 1).getRole())
                    && messages.get(i + 1).isRiskFlag();
            if (!message.isRiskFlag() && !replyFlagged) {
                safe.add(message.getContent());
            }
        }
        return safe;
    }

    private void saveFacts(UUID userId, UUID conversationId, MemoryExtractionClient.Extraction extraction) {
        if (extraction.isEmpty()) {
            log.info("Memory: nothing to save from conversation {}", conversationId);
            return;
        }
        // Re-check: the user may have turned memory off or deleted the
        // conversation while extraction was running.
        if (!isMemoryEnabled(userId) || !conversationRepository.existsById(conversationId)) {
            return;
        }

        // Core facts: one per key, so a new value (e.g. a corrected name) replaces the old one.
        for (Map.Entry<String, String> core : extraction.core().entrySet()) {
            memoryRepository.upsertCore(userId, core.getKey(), core.getValue(),
                    embeddingClient.embed(core.getValue()), conversationId);
        }

        // A remembered fact that is now outdated is rewritten rather than contradicted.
        int updated = 0;
        for (MemoryExtractionClient.Update update : extraction.updates()) {
            if (memoryRepository.updateContent(userId, update.memoryId(), update.fact(),
                    embeddingClient.embed(update.fact()), conversationId)) {
                updated++;
            }
        }

        int saved = 0;
        for (String fact : extraction.newFacts()) {
            float[] embedding = embeddingClient.embed(fact);
            // Inserted facts are visible to the next check, so this also
            // dedupes facts within the same batch.
            if (memoryRepository.maxSimilarity(userId, embedding) > properties.dedupSimilarity()) {
                continue;
            }
            memoryRepository.insert(userId, fact, embedding, conversationId);
            saved++;
        }
        if (saved > 0) {
            memoryRepository.trimToCap(userId, properties.maxPerUser());
        }
        log.info("Memory: conversation {}: core facts set {} {}, updated {}, saved {} of {} new facts",
                conversationId, extraction.core().size(), extraction.core().keySet(), updated, saved,
                extraction.newFacts().size());
    }

    private boolean isMemoryEnabled(UUID userId) {
        return userRepository.findById(userId).map(User::isMemoryEnabled).orElse(false);
    }
}
