package com.neuronest.backend.chat;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.chat.llm.LlmProviderException;
import com.neuronest.backend.chat.llm.LlmRouter;
import com.neuronest.backend.chat.llm.LlmUnavailableException;
import com.neuronest.backend.chat.llm.LlmUsage;
import com.neuronest.backend.chat.llm.StreamSink;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.memory.GeminiEmbeddingClient;
import com.neuronest.backend.memory.GeminiRetryableException;
import com.neuronest.backend.memory.MemoryExtractionService;
import com.neuronest.backend.memory.MemoryProperties;
import com.neuronest.backend.memory.MemoryRecallService;
import com.neuronest.backend.memory.MemoryRepository;
import com.neuronest.backend.safety.CrisisResource;
import com.neuronest.backend.safety.CrisisResources;
import com.neuronest.backend.session.Session;
import com.neuronest.backend.session.SessionAccessService;
import com.neuronest.backend.session.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatService with the single-call pipeline: header-first streaming, header fallback and
 * keyword backstop, failure handling, and the memory/summary rules around it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceMemoryTest {

    static final String NO_RISK = "[[RISK:0|TIER:5|INSTR:NONE|ACT:NONE]]\n";
    static final String RELAX = "[[RISK:0|TIER:3|INSTR:NONE|ACT:RELAX]]\n";
    static final String CRISIS = "[[RISK:1|TIER:1|INSTR:NONE|ACT:NONE]]\n";

    @Mock SessionRepository sessionRepository;
    @Mock ChatConversationRepository conversationRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock SafetyCheckService safetyCheckService;
    @Mock ReplyService replyService;
    @Mock MemoryExtractionService extractionService;
    @Mock ConversationSummaryService summaryService;
    @Mock UserRepository userRepository;
    @Mock GeminiEmbeddingClient embeddingClient;
    @Mock MemoryRepository memoryRepository;

    final UUID userId = UUID.randomUUID();
    final UUID sessionId = UUID.randomUUID();
    final UUID conversationId = UUID.randomUUID();
    final SafetyAssessment noConcern = new SafetyAssessment(false, null, 5, "continue", null, null);
    final SafetyAssessment checkedCrisis = new SafetyAssessment(true, "passive suicidal ideation", 1, "seek_help",
            null, CrisisResources.INDIA_RESOURCES);

    ChatConversation conversation;
    ChatService chatService;
    /** Scripted reply calls, consumed in order: each is a list of chunks, or an exception to throw. */
    final Deque<Object> replyCalls = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        // Real recall service wired to mocked Gemini + DB, so these tests cover
        // the whole recall path, including its failure handling.
        MemoryProperties properties = new MemoryProperties(true, "gemini-embedding-2", 768, 5, 0.60,
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1), 0.90, 200, 5, 5,
                Duration.ofMinutes(30), Duration.ofSeconds(2), Duration.ofSeconds(2));
        MemoryRecallService recallService = new MemoryRecallService(properties, userRepository, embeddingClient,
                memoryRepository, extractionService);
        chatService = new ChatService(sessionRepository, new SessionAccessService(), conversationRepository,
                messageRepository, safetyCheckService, replyService, recallService, extractionService, summaryService);

        conversation = new ChatConversation();
        ReflectionTestUtils.setField(conversation, "id", conversationId);
        conversation.setSessionId(sessionId);
        when(conversationRepository.findFirstBySessionIdOrderByStartedAtDesc(sessionId))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
            return message;
        });

        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(safetyCheckService.assess(anyList(), anyString())).thenReturn(noConcern);

        doAnswer(invocation -> {
            StreamSink sink = invocation.getArgument(6);
            Object call = replyCalls.isEmpty() ? List.of(NO_RISK + "I'm ", "here for you.") : replyCalls.poll();
            if (call instanceof RuntimeException e) {
                throw e;
            }
            @SuppressWarnings("unchecked")
            List<Object> steps = (List<Object>) call;
            for (Object step : steps) {
                if (step instanceof RuntimeException e) {
                    throw e;
                }
                sink.accept((String) step);
            }
            return new LlmRouter.StreamResult("groq", new LlmUsage(900, 60));
        }).when(replyService).streamReply(anyList(), anyString(), anyList(), any(), anyBoolean(), anyBoolean(), any());
    }

    private void reply(Object... steps) {
        replyCalls.add(List.of(steps));
    }

    private void givenSessionOwnedBy(UUID owner) {
        Session session = new Session();
        ReflectionTestUtils.setField(session, "id", sessionId);
        session.setUserId(owner);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    }

    private RecordingListener run(String message) {
        givenSessionOwnedBy(null);
        RecordingListener listener = new RecordingListener();
        chatService.runTurn(chatService.prepareTurn(sessionId, message, null), listener);
        return listener;
    }

    private List<ChatMessage> savedMessages(int expected) {
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(expected)).save(saved.capture());
        return saved.getAllValues();
    }

    private static ChatMessage message(String role, String content, boolean riskFlag) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        message.setRiskFlag(riskFlag);
        return message;
    }

    // ---- Memory (unchanged guarantees) ----

    @Test
    void anonymousChatNeverTouchesMemory() {
        givenSessionOwnedBy(null);

        ChatAiResponse response = chatService.sendMessage(sessionId, "hello", null);

        assertThat(response.reply()).isEqualTo("I'm here for you.");
        verify(replyService).streamReply(anyList(), eq("hello"), eq(List.of()), any(), eq(false), eq(false), any());
        verifyNoInteractions(memoryRepository, embeddingClient, extractionService);
    }

    @Test
    void loggedInUserInAGuestSessionGetsNoMemory() {
        givenSessionOwnedBy(null);

        chatService.sendMessage(sessionId, "hello", userId);

        verifyNoInteractions(memoryRepository, embeddingClient, extractionService);
    }

    @Test
    void recalledMemoriesAreAddedToTheReplyPromptButNeverToTheSafetyCheck() {
        givenSessionOwnedBy(userId);
        float[] query = new float[768];
        when(embeddingClient.embed("exam tomorrow")).thenReturn(query);
        when(memoryRepository.findMostSimilar(userId, query, 5)).thenReturn(List.of(
                new MemoryRepository.RecalledMemory(UUID.randomUUID(), "The user is a final-year student", 0.8)));

        chatService.sendMessage(sessionId, "exam tomorrow", userId);

        verify(replyService).streamReply(anyList(), eq("exam tomorrow"),
                eq(List.of("The user is a final-year student")), any(), anyBoolean(), anyBoolean(), any());
        verifyNoInteractions(safetyCheckService);
        verify(extractionService).onTurnCompleted(userId, conversationId, false);
    }

    @Test
    void geminiFailureInMemoryDoesNotFailTheChat() {
        givenSessionOwnedBy(userId);
        when(embeddingClient.embed(anyString())).thenThrow(new ResourceAccessException("Gemini embeddings down"));
        doThrow(new RuntimeException("executor rejected")).when(extractionService)
                .onTurnCompleted(any(), any(), anyBoolean());

        ChatAiResponse response = chatService.sendMessage(sessionId, "hello", userId);

        assertThat(response.reply()).isEqualTo("I'm here for you.");
    }

    @Test
    void rateLimitedMemoryNeverAffectsTheCrisisReply() {
        givenSessionOwnedBy(userId);
        when(embeddingClient.embed(anyString())).thenThrow(
                new GeminiRetryableException("Gemini rate limited (429) for gemini-embedding-2", Duration.ofSeconds(30)));
        reply(CRISIS + "I'm really glad you told me.");

        ChatAiResponse response = chatService.sendMessage(sessionId, "crisis message", userId);

        assertThat(response.riskFlag()).isTrue();
        assertThat(response.crisisResources()).isEqualTo(CrisisResources.INDIA_RESOURCES);
        assertThat(response.reply()).isEqualTo("I'm really glad you told me.");
    }

    @Test
    void crisisTurnFlagsTheUserMessageAsWell() {
        reply(CRISIS + "I'm here with you.");
        run("crisis message");
        assertThat(savedMessages(2)).allMatch(ChatMessage::isRiskFlag);
    }

    // ---- Header-first streaming ----

    @Test
    void headerIsHiddenAndMetaComesBeforeAnyText() {
        reply("[[RISK:0|TI", "ER:3|INSTR:NONE|ACT:RELAX]]", "\nLong days ", "are tiring.");

        RecordingListener listener = run("long day at work");

        assertThat(listener.events).containsExactly("meta", "token:Long days ", "token:are tiring.", "done");
        assertThat(listener.meta).isEqualTo(new SafetyAssessment(false, null, 3, "relaxation", null, null));
        assertThat(String.join("", listener.events)).doesNotContain("[[", "RISK", "]]");
        List<ChatMessage> saved = savedMessages(2);
        assertThat(saved.get(1).getContent()).isEqualTo("Long days are tiring.");
        verifyNoInteractions(safetyCheckService);
    }

    @Test
    void headerAndReplyCanArriveInOneChunk() {
        reply("[[RISK:0|TIER:2|INSTR:GAD7|ACT:NONE]]\nThat worry sounds exhausting.");

        RecordingListener listener = run("I've been anxious for weeks");

        assertThat(listener.events).containsExactly("meta", "token:That worry sounds exhausting.", "done");
        assertThat(listener.meta.recommendedInstrument()).isEqualTo("GAD7");
    }

    @Test
    void crisisHeaderSendsCrisisResourcesFirst() {
        reply(CRISIS, "I'm really glad ", "you told me.");

        RecordingListener listener = run("mujhe ab jeena nahi hai");

        assertThat(listener.events.getFirst()).isEqualTo("meta");
        assertThat(listener.meta.riskFlag()).isTrue();
        assertThat(listener.meta.crisisResources()).isEqualTo(CrisisResources.INDIA_RESOURCES);
        assertThat(listener.text()).isEqualTo("I'm really glad you told me.");
    }

    @Test
    void missingHeaderFallsBackToTheSeparateSafetyCheck() {
        reply("Hi there, ", "how are you?");

        RecordingListener listener = run("hii");

        verify(safetyCheckService).assess(anyList(), eq("hii"));
        // No "[" at the start: the verdict is fetched at once and text flows from then on.
        assertThat(listener.events.getFirst()).isEqualTo("meta");
        assertThat(listener.events.getLast()).isEqualTo("done");
        assertThat(listener.text()).isEqualTo("Hi there, how are you?");
    }

    @Test
    void malformedHeaderIsStrippedAndFallsBack() {
        reply("[[RISK:maybe|TIER:x]]\nHello again.");

        RecordingListener listener = run("hello");

        verify(safetyCheckService).assess(anyList(), eq("hello"));
        assertThat(listener.text()).isEqualTo("Hello again.");
    }

    @Test
    void textWithoutAHeaderBeyondTheLimitFallsBackWithoutWaitingForTheEnd() {
        String longText = "x".repeat(ReplyHeader.MAX_HEADER_CHARS + 5);
        reply("[" + longText, " more");

        RecordingListener listener = run("hello");

        verify(safetyCheckService).assess(anyList(), anyString());
        assertThat(listener.text()).isEqualTo("[" + longText + " more");
    }

    @Test
    void headerFallbackThatFindsACrisisRewritesTheReplyAsACrisisReply() {
        when(safetyCheckService.assess(anyList(), anyString())).thenReturn(checkedCrisis);
        reply("Sounds like a rough day!");                       // no header, written without a verdict
        reply(CRISIS + "Thank you for telling me. Are you safe right now?");

        RecordingListener listener = run("i dont want to wake up tomorrow");

        assertThat(listener.events).containsExactly("meta",
                "token:Thank you for telling me. Are you safe right now?", "done");
        assertThat(listener.meta.riskFlag()).isTrue();
        verify(replyService).streamReply(anyList(), anyString(), anyList(), any(), anyBoolean(), eq(true), any());
    }

    @Test
    void keywordBackstopCanRaiseANoRiskHeaderToACrisis() {
        when(safetyCheckService.assess(anyList(), anyString())).thenReturn(checkedCrisis);
        reply(NO_RISK + "Grades can feel heavy.");
        reply(CRISIS + "I'm so glad you told me. Are you safe right now?");

        RecordingListener listener =
                run("watching at my grades. i cant face my parents. dieing feels easy");

        assertThat(listener.events).containsExactly("meta",
                "token:I'm so glad you told me. Are you safe right now?", "done");
        assertThat(listener.meta.riskFlag()).isTrue();
        assertThat(listener.text()).doesNotContain("Grades can feel heavy");
        List<ChatMessage> saved = savedMessages(2);
        assertThat(saved).allMatch(ChatMessage::isRiskFlag);
    }

    @Test
    void keywordHitAloneNeverSetsRisk() {
        when(safetyCheckService.assess(anyList(), anyString())).thenReturn(noConcern);
        reply(NO_RISK + "That joke made you laugh!");

        RecordingListener listener = run("lol that meme, i want to die laughing");

        verify(safetyCheckService).assess(anyList(), anyString());
        assertThat(listener.meta.riskFlag()).isFalse();
        assertThat(listener.text()).isEqualTo("That joke made you laugh!");
        verify(replyService, times(1)).streamReply(anyList(), anyString(), anyList(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void keywordBackstopUnavailableKeepsTheHeaderVerdict() {
        when(safetyCheckService.assess(anyList(), anyString()))
                .thenThrow(new SafetyCheckUnavailableException(new RuntimeException("down")));
        reply(RELAX + "Rest sounds good.");

        RecordingListener listener = run("so tired, i want to die lol");

        assertThat(listener.meta.riskFlag()).isFalse();
        assertThat(listener.text()).isEqualTo("Rest sounds good.");
    }

    @Test
    void noKeywordMeansNoExtraSafetyCall() {
        reply(RELAX + "Rest sounds good.");
        run("long day, very tired");
        verifyNoInteractions(safetyCheckService);
    }

    // ---- Failures ----

    @Test
    void everyProviderFailingBeforeTheHeaderIsTreatedAsUnsafe() {
        replyCalls.add(new LlmUnavailableException("reply", null));

        RecordingListener listener = run("hello");

        assertThat(listener.events).containsExactly("error:SAFETY_CHECK_FAILED");
        assertThat(listener.errorCrisisResources).isEqualTo(CrisisResources.INDIA_RESOURCES);
        ChatMessage user = savedMessages(1).getFirst();
        assertThat(user.getRole()).isEqualTo("user");
        assertThat(user.isRiskFlag()).isTrue();
    }

    @Test
    void headerFallbackWithTheSafetyCheckUnavailableIsTreatedAsUnsafe() {
        when(safetyCheckService.assess(anyList(), anyString()))
                .thenThrow(new SafetyCheckUnavailableException(new RuntimeException("down")));
        reply("No header here.");

        RecordingListener listener = run("hello");

        assertThat(listener.events).containsExactly("error:SAFETY_CHECK_FAILED");
    }

    @Test
    void midStreamFailureKeepsThePartialReply() {
        reply(NO_RISK + "Partial ",
                new LlmProviderException("groq", LlmProviderException.Kind.CONNECTION, null, true, null));

        RecordingListener listener = run("hello");

        assertThat(listener.events).containsExactly("meta", "token:Partial ", "error:STREAM_INTERRUPTED");
        assertThat(savedMessages(2).get(1).getContent()).isEqualTo("Partial ");
    }

    @Test
    void crisisReplyThatFailsBeforeAnyTextGetsTheWarmStaticReply() {
        reply(CRISIS, new LlmProviderException("groq", LlmProviderException.Kind.CONNECTION, null, true, null));

        RecordingListener listener = run("dieing feels easy");

        assertThat(listener.events).containsExactly("meta", "token:" + ChatService.CRISIS_FALLBACK_REPLY, "done");
        assertThat(ChatService.CRISIS_FALLBACK_REPLY).doesNotContainIgnoringCase("can't help");
        assertThat(savedMessages(2).get(1).isRiskFlag()).isTrue();
    }

    @Test
    void restartedCrisisReplyUnavailableGetsTheWarmStaticReply() {
        when(safetyCheckService.assess(anyList(), anyString())).thenReturn(checkedCrisis);
        reply(NO_RISK + "ok!");
        replyCalls.add(new LlmUnavailableException("reply", null));

        RecordingListener listener = run("i want to end my life");

        assertThat(listener.events).containsExactly("meta", "token:" + ChatService.CRISIS_FALLBACK_REPLY, "done");
    }

    @Test
    void nonCrisisReplyFailingAfterItsHeaderReportsAFailure() {
        reply(NO_RISK, new LlmProviderException("groq", LlmProviderException.Kind.CONNECTION, null, true, null));

        RecordingListener listener = run("hello");

        assertThat(listener.events).containsExactly("meta", "error:REPLY_FAILED");
    }

    // ---- Earlier crisis, context and summary ----

    @Test
    void followUpAfterACrisisIsJudgedOnItsOwnButRemembersTheEarlierDisclosure() {
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of(
                message("user", "dieing feels easy", true), message("assistant", "I'm really glad you told me", true)));
        reply(NO_RISK + "Hi, good to see you. How are you holding up?");

        RecordingListener listener = run("hii");

        assertThat(listener.meta.riskFlag()).isFalse();
        assertThat(listener.meta.earlierRiskInConversation()).isTrue();
        assertThat(listener.meta.crisisResources()).isEqualTo(CrisisResources.INDIA_RESOURCES);
        verify(replyService).streamReply(anyList(), eq("hii"), anyList(), any(), eq(true), eq(false), any());
        assertThat(savedMessages(2).getFirst().isRiskFlag()).isFalse();
    }

    @Test
    void anUncheckedMessageFromAFailedTurnIsNotTreatedAsADisclosure() {
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of(message("user", "hello", true)));

        RecordingListener listener = run("hello again");

        assertThat(listener.meta.earlierRiskInConversation()).isFalse();
        assertThat(listener.meta.crisisResources()).isNull();
    }

    @Test
    void theRollingSummaryIsSentAndAnUpdateIsOfferedAfterEachTurn() {
        conversation.setSummary("The user is preparing for exams.");
        conversation.setSummarizedMessageCount(4);
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            history.add(message(i % 2 == 0 ? "user" : "assistant", "m" + i, false));
        }
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(history);

        run("hello");

        verify(replyService).streamReply(anyList(), anyString(), anyList(), eq("The user is preparing for exams."),
                anyBoolean(), anyBoolean(), any());
        verify(summaryService).maybeUpdate(conversationId, 16, 4);
    }

    @Test
    void nonStreamedEndpointSurfacesTurnErrors() {
        givenSessionOwnedBy(null);
        replyCalls.add(new LlmUnavailableException("reply", null));

        assertThatThrownBy(() -> chatService.sendMessage(sessionId, "hello", null))
                .isInstanceOfSatisfying(ChatUnavailableException.class, e -> {
                    assertThat(e.error()).isEqualTo(ChatTurnError.SAFETY_CHECK_FAILED);
                    assertThat(e.crisisResources()).isEqualTo(CrisisResources.INDIA_RESOURCES);
                });
    }

    @Test
    void blankMessagesAreRejectedBeforeAnyWork() {
        assertThatThrownBy(() -> chatService.prepareTurn(sessionId, "  ", null))
                .isInstanceOf(ApiValidationException.class);
        verifyNoInteractions(sessionRepository, safetyCheckService, replyService);
    }

    static final class RecordingListener implements ChatTurnListener {
        final List<String> events = new ArrayList<>();
        SafetyAssessment meta;
        List<CrisisResource> errorCrisisResources;

        @Override public void onMeta(SafetyAssessment assessment) { meta = assessment; events.add("meta"); }
        @Override public void onToken(String text) { events.add("token:" + text); }
        @Override public void onDone(UUID assistantMessageId) { events.add("done"); }
        @Override public void onError(ChatTurnError error, List<CrisisResource> crisisResources) {
            errorCrisisResources = crisisResources;
            events.add("error:" + error.name());
        }

        String text() {
            return String.join("", events.stream().filter(e -> e.startsWith("token:"))
                    .map(e -> e.substring("token:".length())).toList());
        }
    }
}
