package com.neuronest.backend.chat;

import com.neuronest.backend.chat.llm.LlmProviderException;
import com.neuronest.backend.chat.llm.LlmRouter;
import com.neuronest.backend.chat.llm.LlmUnavailableException;
import com.neuronest.backend.chat.llm.LlmUsage;
import com.neuronest.backend.chat.llm.StreamSink;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.kb.KbChunk;
import com.neuronest.backend.kb.KnowledgeRetrievalService;
import com.neuronest.backend.memory.MemoryExtractionService;
import com.neuronest.backend.memory.MemoryRecallService;
import com.neuronest.backend.safety.CrisisResource;
import com.neuronest.backend.safety.CrisisResources;
import com.neuronest.backend.session.Session;
import com.neuronest.backend.session.SessionAccessService;
import com.neuronest.backend.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One chat turn = ONE LLM call that writes a private verdict header and then the reply.
 * The header is parsed before any reply text is released, so the "meta" event (with
 * crisis resources when flagged) always reaches the user first. If the header is missing
 * or malformed, or says "no risk" while a crisis phrase matches, the separate safety
 * classifier decides instead (it can only raise risk).
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * Sent as the reply when a crisis was flagged but no provider could write one.
     * Never a refusal: the user must not be left with only phone numbers.
     */
    static final String CRISIS_FALLBACK_REPLY = "I'm really glad you told me this. What you're feeling "
            + "matters, and you don't have to go through it alone. The helplines above are free and "
            + "available right now — and I'm still here with you. Do you want to tell me what's been happening?";

    /** Everything a turn needs, resolved on the request thread (so access errors become HTTP errors). */
    public record PreparedTurn(
            UUID conversationId,
            List<ChatMessage> history,
            UUID memoryUserId,
            boolean conversationStarted,
            String userMessage,
            String summary,
            int summarizedCount
    ) {
        public PreparedTurn(UUID conversationId, List<ChatMessage> history, UUID memoryUserId,
                            boolean conversationStarted, String userMessage) {
            this(conversationId, history, memoryUserId, conversationStarted, userMessage, null, 0);
        }
    }

    private final SessionRepository sessionRepository;
    private final SessionAccessService sessionAccessService;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final SafetyCheckService safetyCheckService;
    private final ReplyService replyService;
    private final MemoryRecallService memoryRecallService;
    private final MemoryExtractionService memoryExtractionService;
    private final ConversationSummaryService summaryService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public ChatService(SessionRepository sessionRepository,
                       SessionAccessService sessionAccessService,
                       ChatConversationRepository conversationRepository,
                       ChatMessageRepository messageRepository,
                       SafetyCheckService safetyCheckService,
                       ReplyService replyService,
                       MemoryRecallService memoryRecallService,
                       MemoryExtractionService memoryExtractionService,
                       ConversationSummaryService summaryService,
                       KnowledgeRetrievalService knowledgeRetrievalService) {
        this.sessionRepository = sessionRepository;
        this.sessionAccessService = sessionAccessService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.safetyCheckService = safetyCheckService;
        this.replyService = replyService;
        this.memoryRecallService = memoryRecallService;
        this.memoryExtractionService = memoryExtractionService;
        this.summaryService = summaryService;
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    public PreparedTurn prepareTurn(UUID sessionId, String userMessage, UUID currentUserId) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new ApiValidationException("message is required.");
        }
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        sessionAccessService.checkAccess(session, currentUserId);

        // Long-term memory only for a logged-in user chatting in their own session.
        // Guest sessions (no owner) are readable by anyone holding the id, so they
        // never get memory, even when the requester happens to be logged in.
        UUID memoryUserId = currentUserId != null && currentUserId.equals(session.getUserId())
                ? currentUserId : null;

        boolean[] conversationStarted = {false};
        ChatConversation conversation = conversationRepository
                .findFirstBySessionIdOrderByStartedAtDesc(sessionId)
                .orElseGet(() -> {
                    conversationStarted[0] = true;
                    ChatConversation newConversation = new ChatConversation();
                    newConversation.setSessionId(sessionId);
                    return conversationRepository.save(newConversation);
                });

        List<ChatMessage> history = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId());

        return new PreparedTurn(conversation.getId(), history, memoryUserId, conversationStarted[0], userMessage,
                conversation.getSummary(), conversation.getSummarizedMessageCount());
    }

    /**
     * Runs a prepared turn, reporting progress to {@code listener}. Never throws for LLM
     * failures: those become {@link ChatTurnListener#onError}. A {@link StreamClosedException}
     * from the listener (client gone) stops the turn after saving what was shown.
     */
    public void runTurn(PreparedTurn turn, ChatTurnListener listener) {
        TurnLog turnLog = new TurnLog();
        try {
            runTurn(turn, listener, turnLog);
        } finally {
            turnLog.write();
        }
    }

    private void runTurn(PreparedTurn turn, ChatTurnListener listener, TurnLog turnLog) {
        boolean earlierCrisis = hadEarlierCrisisTurn(turn.history());
        // Runs alongside memory recall; never fails and is bounded by kb.retrieval-budget.
        CompletableFuture<List<KbChunk>> knowledgeLookup = knowledgeRetrievalService.retrieveAsync(
                turn.userMessage(), turn.summary(), earlierCrisis, CrisisKeywords.matches(turn.userMessage()));
        List<String> memories = recallMemories(turn, turnLog);
        List<KbChunk> knowledge = knowledgeLookup.join();
        turnLog.knowledge = knowledge.size();

        SafetyAssessment knownVerdict = null;
        TurnStream stream;
        while (true) {
            stream = new TurnStream(turn, earlierCrisis, listener, turnLog, knownVerdict);
            try {
                turnLog.replyCalls++;
                // A crisis reply never gets background knowledge.
                LlmRouter.StreamResult result = replyService.streamReply(turn.history(), turn.userMessage(),
                        memories, knownVerdict != null ? List.of() : knowledge, turn.summary(), earlierCrisis,
                        knownVerdict != null, stream);
                turnLog.served(result.provider(), result.usage());
                stream.finish();
                break;
            } catch (RestartAsCrisis restart) {
                // The separate check found a crisis the reply's own header missed: the crisis box
                // is already showing; rewrite the reply with crisis guidance.
                log.warn("Chat turn: separate safety check raised this turn to a crisis; restarting the reply "
                        + "with crisis guidance");
                turnLog.restarted = true;
                knownVerdict = stream.assessment();
            } catch (SafetyCheckUnavailableException e) {
                log.error("Chat turn: reply header unusable and the separate safety check is unavailable");
                failUnsafe(turn, listener);
                return;
            } catch (LlmUnavailableException e) {
                if (knownVerdict == null) {
                    // Every provider failed before the verdict was known: treat as unsafe.
                    log.error("Chat turn: every provider failed before the reply header");
                    failUnsafe(turn, listener);
                    return;
                }
                log.error("Chat turn: crisis reply unavailable on every provider; sending the static crisis reply");
                stream.show(CRISIS_FALLBACK_REPLY);
                break;
            } catch (LlmProviderException e) {
                // Thrown by the router only after the turn was committed to the user.
                turnLog.served(e.provider(), null);
                if (stream.visibleText().isEmpty() && stream.assessment().riskFlag()) {
                    log.error("Chat turn: crisis reply failed before any text; sending the static crisis reply");
                    stream.show(CRISIS_FALLBACK_REPLY);
                    break;
                }
                if (stream.visibleText().isEmpty()) {
                    log.error("Chat turn: reply failed after its header, before any text");
                    listener.onError(ChatTurnError.REPLY_FAILED, null);
                    return;
                }
                savePartialReply(turn, stream);
                listener.onError(ChatTurnError.STREAM_INTERRUPTED, crisisResourcesFor(stream.assessment()));
                return;
            } catch (StreamClosedException e) {
                log.info("Chat turn: client disconnected while the reply was streaming");
                if (stream.assessment() != null) {
                    savePartialReply(turn, stream);
                }
                throw e;
            }
        }

        if (stream.visibleText().isEmpty()) {
            if (!stream.assessment().riskFlag()) {
                log.error("Chat turn: reply contained a header but no text");
                listener.onError(ChatTurnError.REPLY_FAILED, null);
                return;
            }
            stream.show(CRISIS_FALLBACK_REPLY);
        }

        ChatMessage saved = saveMessage(turn, "assistant", stream.visibleText(), stream.assessment().riskFlag());
        listener.onDone(saved.getId());
        turnLog.completed = true;
        scheduleMemoryExtraction(turn);
        summaryService.maybeUpdate(turn.conversationId(), turn.history().size() + 2, turn.summarizedCount());
    }

    /** Non-streamed turn for POST /chat: same pipeline, collected into one response. */
    public ChatAiResponse sendMessage(UUID sessionId, String userMessage, UUID currentUserId) {
        PreparedTurn turn = prepareTurn(sessionId, userMessage, currentUserId);
        CollectingListener collected = new CollectingListener();
        runTurn(turn, collected);

        if (collected.error != null) {
            throw new ChatUnavailableException(collected.error, collected.errorCrisisResources);
        }
        SafetyAssessment a = collected.assessment;
        return new ChatAiResponse(collected.reply.toString(), a.riskFlag(), a.riskReason(),
                a.recommendationTier(), a.recommendedAction(), a.recommendedInstrument(), a.crisisResources());
    }

    public List<ChatMessage> getHistory(UUID sessionId, UUID currentUserId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        sessionAccessService.checkAccess(session, currentUserId);

        // Opening a conversation: warm the database before the user's first message.
        if (currentUserId != null && currentUserId.equals(session.getUserId())) {
            memoryRecallService.warmUp(currentUserId);
        }

        return conversationRepository.findFirstBySessionIdOrderByStartedAtDesc(sessionId)
                .map(conversation -> messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId()))
                .orElse(List.of());
    }

    private List<String> recallMemories(PreparedTurn turn, TurnLog turnLog) {
        if (turn.memoryUserId() == null) {
            return List.of();
        }
        long start = System.nanoTime();
        List<String> memories = memoryRecallService.recall(turn.memoryUserId(), turn.history(), turn.userMessage());
        turnLog.ragMs = (System.nanoTime() - start) / 1_000_000;
        turnLog.memories = memories.size();
        return memories;
    }

    /** No verdict could be obtained: no reply, helplines shown calmly, message kept out of memory. */
    private void failUnsafe(PreparedTurn turn, ChatTurnListener listener) {
        saveMessage(turn, "user", turn.userMessage(), true);
        listener.onError(ChatTurnError.SAFETY_CHECK_FAILED, CrisisResources.INDIA_RESOURCES);
    }

    private ChatMessage saveMessage(PreparedTurn turn, String role, String content, boolean riskFlag) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(turn.conversationId());
        message.setRole(role);
        message.setContent(content);
        message.setRiskFlag(riskFlag);
        return messageRepository.save(message);
    }

    /** Keeps whatever text the user already saw, so history matches the screen. */
    private void savePartialReply(PreparedTurn turn, TurnStream stream) {
        if (!stream.visibleText().isEmpty()) {
            saveMessage(turn, "assistant", stream.visibleText(), stream.assessment().riskFlag());
        }
    }

    private static List<CrisisResource> crisisResourcesFor(SafetyAssessment assessment) {
        return assessment.riskFlag() ? assessment.crisisResources() : null;
    }

    /**
     * True if an earlier turn in this conversation was a checked crisis turn. Assistant
     * messages are used because user messages are also flagged when no verdict could be
     * obtained (to keep them out of memory), which is not a disclosure.
     */
    static boolean hadEarlierCrisisTurn(List<ChatMessage> history) {
        return history.stream().anyMatch(m -> "assistant".equals(m.getRole()) && m.isRiskFlag());
    }

    private void scheduleMemoryExtraction(PreparedTurn turn) {
        if (turn.memoryUserId() == null) {
            return;
        }
        try {
            // @Async: returns immediately; extraction runs in the background.
            memoryExtractionService.onTurnCompleted(turn.memoryUserId(), turn.conversationId(),
                    turn.conversationStarted());
        } catch (Exception e) {
            log.warn("Memory: could not schedule extraction: {}", e.toString());
        }
    }

    /** Aborts the current reply stream so it can be rewritten as a crisis reply. */
    private static final class RestartAsCrisis extends RuntimeException {
        RestartAsCrisis() {
            super(null, null, false, false);
        }
    }

    /**
     * Sink for the reply stream. In verdict mode it holds text until the header is parsed,
     * sends "meta", then shows the rest live; it is committed once "meta" is out. In
     * strip mode (verdict already known and sent) it only removes the header, and is
     * committed once reply text is shown. The header never reaches the user.
     */
    final class TurnStream implements StreamSink {

        private final PreparedTurn turn;
        private final boolean earlierCrisis;
        private final ChatTurnListener listener;
        private final TurnLog turnLog;
        private final boolean verdictKnown;
        private final StringBuilder pending = new StringBuilder();
        private final StringBuilder visible = new StringBuilder();
        private SafetyAssessment assessment;
        private boolean headerDone;

        TurnStream(PreparedTurn turn, boolean earlierCrisis, ChatTurnListener listener, TurnLog turnLog,
                   SafetyAssessment knownVerdict) {
            this.turn = turn;
            this.earlierCrisis = earlierCrisis;
            this.listener = listener;
            this.turnLog = turnLog;
            this.verdictKnown = knownVerdict != null;
            this.assessment = knownVerdict;
        }

        @Override
        public void accept(String text) {
            if (headerDone) {
                show(text);
                return;
            }
            pending.append(text);
            resolveHeader(false);
        }

        @Override
        public boolean committed() {
            return verdictKnown ? !visible.isEmpty() : assessment != null;
        }

        @Override
        public void reset() {
            // Only called while uncommitted: the next provider writes its own header.
            pending.setLength(0);
            headerDone = false;
        }

        /** Called when the provider finished; resolves a header still being waited for. */
        void finish() {
            if (!headerDone) {
                resolveHeader(true);
            }
        }

        SafetyAssessment assessment() {
            return assessment;
        }

        String visibleText() {
            return visible.toString();
        }

        private void resolveHeader(boolean streamEnded) {
            String buffered = pending.toString();
            String lead = buffered.stripLeading();
            if (lead.isEmpty()) {
                if (streamEnded) {
                    headerUnusable("");
                }
                return;
            }
            if (!lead.startsWith("[")) {
                headerUnusable(buffered);
                return;
            }
            int close = lead.indexOf("]]");
            if (close < 0) {
                if (lead.length() > ReplyHeader.MAX_HEADER_CHARS || streamEnded) {
                    headerUnusable(buffered);
                }
                return;
            }
            String rest = lead.substring(close + 2);
            if (verdictKnown) {
                release(rest);
                return;
            }
            Optional<SafetyAssessment> parsed = ReplyHeader.parse(lead.substring(0, close + 2));
            if (parsed.isEmpty()) {
                headerUnusable(rest);
                return;
            }
            turnLog.header = "ok";
            open(parsed.get(), rest, true);
        }

        private void headerUnusable(String held) {
            if (verdictKnown) {
                release(stripHeaderLike(held));
                return;
            }
            log.warn("Chat turn: reply header missing or malformed; running the separate safety check");
            turnLog.header = "fallback";
            turnLog.safetyChecks++;
            SafetyAssessment checked = safetyCheckService.assess(turn.history(), turn.userMessage());
            open(checked, stripHeaderLike(held), false);
        }

        private void open(SafetyAssessment verdict, String rest, boolean fromHeader) {
            boolean raised = false;
            if (fromHeader && !verdict.riskFlag() && CrisisKeywords.matches(turn.userMessage())) {
                log.warn("Chat turn: header said no risk but a crisis phrase matched; running the separate "
                        + "safety check");
                turnLog.safetyChecks++;
                try {
                    SafetyAssessment checked = safetyCheckService.assess(turn.history(), turn.userMessage());
                    if (checked.riskFlag()) {
                        verdict = checked;
                        raised = true;
                        turnLog.header = "ok+keyword-check:raised";
                    } else {
                        turnLog.header = "ok+keyword-check:confirmed";
                    }
                } catch (SafetyCheckUnavailableException e) {
                    log.warn("Chat turn: keyword backstop check unavailable; keeping the header verdict");
                    turnLog.header = "ok+keyword-check:unavailable";
                }
            }
            assessment = verdict.withEarlierRisk(earlierCrisis);
            headerDone = true;
            pending.setLength(0);
            listener.onMeta(assessment);
            // Flag the triggering user message too, so memory extraction can skip it.
            saveMessage(turn, "user", turn.userMessage(), assessment.riskFlag());
            // A crisis found by the separate check (keyword backstop, or header fallback) means the
            // held text was not written as a crisis reply: the crisis box is out, rewrite the reply.
            if (raised || (!fromHeader && assessment.riskFlag())) {
                throw new RestartAsCrisis();
            }
            release(rest);
        }

        private void release(String rest) {
            headerDone = true;
            pending.setLength(0);
            if (!rest.isEmpty()) {
                show(rest);
            }
        }

        void show(String text) {
            if (visible.isEmpty()) {
                text = text.stripLeading();
                if (text.isEmpty()) {
                    return;
                }
                turnLog.firstVisible();
            }
            visible.append(text);
            listener.onToken(text);
        }

        /** Drops a leading "[[...]]" (or a broken "[[..." line) the model wrote. */
        private String stripHeaderLike(String text) {
            String lead = text.stripLeading();
            if (!lead.startsWith("[[")) {
                return text;
            }
            int close = lead.indexOf("]]");
            int lineEnd = lead.indexOf('\n');
            int cut = close >= 0 ? close + 2 : lineEnd >= 0 ? lineEnd + 1 : lead.length();
            return lead.substring(cut);
        }
    }

    /** Per-turn timings and counts for one log line (never content). */
    static final class TurnLog {
        private final long start = System.nanoTime();
        private long firstVisibleNanos = -1;
        String provider = "-";
        String header = "none";
        boolean restarted;
        boolean completed;
        int replyCalls;
        int safetyChecks;
        long ragMs = -1;
        int memories;
        int knowledge;
        Integer promptTokens;
        Integer completionTokens;

        void firstVisible() {
            if (firstVisibleNanos < 0) {
                firstVisibleNanos = System.nanoTime();
            }
        }

        void served(String provider, LlmUsage usage) {
            this.provider = provider;
            if (usage != null) {
                promptTokens = sum(promptTokens, usage.promptTokens());
                completionTokens = sum(completionTokens, usage.completionTokens());
            }
        }

        private static Integer sum(Integer a, Integer b) {
            return a == null ? b : b == null ? a : a + b;
        }

        void write() {
            long now = System.nanoTime();
            log.info("Chat turn: provider={} header={} restarted={} replyCalls={} safetyChecks={} completed={} "
                            + "ragMs={} memories={} kbChunks={} ttftMs={} totalMs={} promptTokens={} completionTokens={}",
                    provider, header, restarted, replyCalls, safetyChecks, completed,
                    ragMs < 0 ? "-" : ragMs, memories, knowledge,
                    firstVisibleNanos < 0 ? "-" : (firstVisibleNanos - start) / 1_000_000,
                    (now - start) / 1_000_000,
                    promptTokens == null ? "-" : promptTokens,
                    completionTokens == null ? "-" : completionTokens);
        }
    }

    private static final class CollectingListener implements ChatTurnListener {
        private SafetyAssessment assessment;
        private final StringBuilder reply = new StringBuilder();
        private ChatTurnError error;
        private List<CrisisResource> errorCrisisResources;

        @Override
        public void onMeta(SafetyAssessment assessment) {
            this.assessment = assessment;
        }

        @Override
        public void onToken(String text) {
            reply.append(text);
        }

        @Override
        public void onDone(UUID assistantMessageId) {
        }

        @Override
        public void onError(ChatTurnError error, List<CrisisResource> crisisResources) {
            this.error = error;
            this.errorCrisisResources = crisisResources;
        }
    }
}
