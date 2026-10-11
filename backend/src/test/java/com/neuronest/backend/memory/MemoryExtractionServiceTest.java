package com.neuronest.backend.memory;

import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.chat.ChatConversationRepository;
import com.neuronest.backend.chat.ChatMessage;
import com.neuronest.backend.chat.ChatMessageRepository;
import com.neuronest.backend.chat.llm.LlmProviderException;
import com.neuronest.backend.chat.llm.LlmUnavailableException;
import com.neuronest.backend.session.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.neuronest.backend.memory.MemoryTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryExtractionServiceTest {

    @Mock ChatConversationRepository conversationRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock SessionRepository sessionRepository;
    @Mock UserRepository userRepository;
    @Mock MemoryRepository memoryRepository;
    @Mock MemoryExtractionClient extractionClient;
    @Mock GeminiEmbeddingClient embeddingClient;

    MemoryExtractionService service;
    GeminiBackoff backoff = new GeminiBackoff(JsonMapper.builder().build());

    final UUID userId = UUID.randomUUID();
    final UUID sessionId = UUID.randomUUID();
    final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MemoryExtractionService(properties(), conversationRepository, messageRepository,
                sessionRepository, userRepository, memoryRepository, extractionClient, embeddingClient, backoff);
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, sessionId)));
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(sessionId, userId)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, true)));
        when(memoryRepository.processedUntil(conversationId)).thenReturn(null);
        when(memoryRepository.moveProcessedUntil(any(), any(), any())).thenReturn(true);
        when(embeddingClient.embed(anyString())).thenReturn(new float[768]);
        when(memoryRepository.findForExtraction(eq(userId), anyInt())).thenReturn(List.of());
    }

    private static MemoryExtractionClient.Extraction newFacts(String... facts) {
        return new MemoryExtractionClient.Extraction(Map.of(), List.of(facts), List.of());
    }

    private static LlmUnavailableException everyProviderFailed() {
        return new LlmUnavailableException("memory-extraction",
                LlmProviderException.forStatus("gemini", 429));
    }

    private void givenMessages(ChatMessage... messages) {
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of(messages));
    }

    @Test
    void crisisFlaggedMessagesAreNeverSentToExtraction() {
        givenMessages(
                message("user", "I'm preparing for my final exams", false),
                message("assistant", "That sounds like a lot.", false),
                message("user", "CRISIS MESSAGE (flag on user row)", true),
                message("assistant", "Please reach out to a helpline...", true),
                message("user", "LEGACY CRISIS MESSAGE (flag only on reply)", false),
                message("assistant", "Please reach out to a helpline...", true),
                message("user", "Walking in the evening helps me", false),
                message("assistant", "That's great to hear.", false));
        when(extractionClient.extract(anyList(), anyList())).thenReturn(MemoryExtractionClient.Extraction.EMPTY);

        service.processConversation(conversationId, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
        verify(extractionClient).extract(sent.capture(), anyList());
        assertThat(sent.getValue())
                .containsExactly("I'm preparing for my final exams", "Walking in the evening helps me")
                .noneMatch(m -> m.contains("CRISIS"));
    }

    @Test
    void conversationWithOnlyCrisisMessagesNeverCallsExtraction() {
        givenMessages(message("user", "crisis", true), message("assistant", "helpline", true));

        service.processConversation(conversationId, true);

        verifyNoInteractions(extractionClient, embeddingClient);
        verify(memoryRepository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void memoryDisabledSavesNothingButMarksMessagesProcessed() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, false)));
        givenMessages(message("user", "I want to run a marathon", false), message("assistant", "Nice!", false));

        service.processConversation(conversationId, true);

        verifyNoInteractions(extractionClient, embeddingClient);
        verify(memoryRepository, never()).insert(any(), any(), any(), any());
        verify(memoryRepository).moveProcessedUntil(eq(conversationId), isNull(), any());
    }

    @Test
    void anonymousSessionIsNeverProcessed() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(sessionId, null)));
        givenMessages(message("user", "hello", false), message("assistant", "hi", false));

        service.processConversation(conversationId, true);

        verifyNoInteractions(memoryRepository, extractionClient, embeddingClient);
    }

    @Test
    void waitsForEnoughUserMessagesUnlessForced() {
        givenMessages(message("user", "one", false), message("assistant", "a", false),
                message("user", "two", false), message("assistant", "b", false));

        service.processConversation(conversationId, false);

        verifyNoInteractions(extractionClient);
        verify(memoryRepository, never()).moveProcessedUntil(any(), any(), any());
    }

    @Test
    void extractionFailingOnEveryProviderIsSwallowedAndBatchReleasedForRetry() {
        givenMessages(message("user", "I'm moving to a new city", false), message("assistant", "Big change!", false));
        when(extractionClient.extract(anyList(), anyList())).thenThrow(everyProviderFailed());

        assertThatCode(() -> service.processConversation(conversationId, true)).doesNotThrowAnyException();

        verify(memoryRepository).moveProcessedUntil(eq(conversationId), any(), isNull());
        verify(memoryRepository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void unexpectedErrorIsSwallowed() {
        givenMessages(message("user", "something", false), message("assistant", "ok", false));
        when(extractionClient.extract(anyList(), anyList())).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> service.processConversation(conversationId, true)).doesNotThrowAnyException();
        verify(memoryRepository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void nameIsSavedRightAfterTheTurnItAppearsIn() {
        givenMessages(message("user", "I can't sleep", false), message("assistant", "That's hard.", false),
                message("user", "my name is Abhishek", false), message("assistant", "Nice to meet you!", false));
        when(extractionClient.extractIdentity(eq("my name is Abhishek"), anyList()))
                .thenReturn(Map.of("name", "The user's name is Abhishek."));

        service.saveIdentityFactsNow(userId, conversationId);

        verify(memoryRepository).upsertCore(userId, "name", "The user's name is Abhishek.", new float[768],
                conversationId);
    }

    @Test
    void identitySaveSkipsOnlyACrisisFlaggedMessageNotTheWholeConversation() {
        // An earlier crisis in the conversation doesn't block the latest (safe) message.
        givenMessages(message("user", "crisis", true), message("assistant", "helpline", true),
                message("user", "my name is Abhishek", false), message("assistant", "Hi Abhishek", false));
        when(extractionClient.extractIdentity(anyString(), anyList()))
                .thenReturn(Map.of("name", "The user's name is Abhishek."));

        service.saveIdentityFactsNow(userId, conversationId);

        verify(extractionClient).extractIdentity(eq("my name is Abhishek"), anyList());
    }

    @Test
    void crisisFlaggedMessageIsNeverSentToIdentityExtraction() {
        givenMessages(message("user", "my name is Abhishek and I want to die", true),
                message("assistant", "helpline", true));

        service.saveIdentityFactsNow(userId, conversationId);

        verifyNoInteractions(extractionClient);
    }

    @Test
    void messageWithoutAnIdentityHintCostsNoExtraCall() {
        givenMessages(message("user", "I'm feeling anxious today", false), message("assistant", "I hear you", false));

        service.saveIdentityFactsNow(userId, conversationId);

        verifyNoInteractions(extractionClient);
    }

    @Test
    void identityExtractionFailureIsSwallowedAndDoesNotBlockWaiters() {
        givenMessages(message("user", "call me Abhi", false), message("assistant", "Sure, Abhi", false));
        when(extractionClient.extractIdentity(anyString(), anyList())).thenThrow(everyProviderFailed());

        assertThatCode(() -> service.saveIdentityFactsNow(userId, conversationId)).doesNotThrowAnyException();
        assertThatCode(() -> service.awaitIdentityFacts(userId, Duration.ofMillis(10))).doesNotThrowAnyException();
        verify(memoryRepository, never()).upsertCore(any(), any(), any(), any(), any());
    }

    @Test
    void coreFactsAreUpsertedByKeySoANewValueReplacesTheOld() {
        givenMessages(message("user", "my name is Abhishek, I'm preparing for exams", false),
                message("assistant", "Nice to meet you!", false));
        when(extractionClient.extract(anyList(), anyList())).thenReturn(new MemoryExtractionClient.Extraction(
                Map.of("name", "The user's name is Abhishek."), List.of("The user is preparing for exams."),
                List.of()));

        service.processConversation(conversationId, true);

        verify(memoryRepository).upsertCore(userId, "name", "The user's name is Abhishek.", new float[768],
                conversationId);
        verify(memoryRepository).insert(userId, "The user is preparing for exams.", new float[768], conversationId);
    }

    @Test
    void contradictedFactIsUpdatedInsteadOfDuplicated() {
        UUID oldFact = UUID.randomUUID();
        List<MemoryRepository.KnownMemory> known =
                List.of(new MemoryRepository.KnownMemory(oldFact, null, "The user works at a bank."));
        when(memoryRepository.findForExtraction(eq(userId), anyInt())).thenReturn(known);
        when(memoryRepository.updateContent(any(), any(), any(), any(), any())).thenReturn(true);
        givenMessages(message("user", "I quit the bank, I'm a teacher now", false), message("assistant", "Wow", false));
        when(extractionClient.extract(anyList(), eq(known))).thenReturn(new MemoryExtractionClient.Extraction(
                Map.of(), List.of(), List.of(new MemoryExtractionClient.Update(oldFact, "The user is a teacher."))));

        service.processConversation(conversationId, true);

        verify(memoryRepository).updateContent(userId, oldFact, "The user is a teacher.", new float[768],
                conversationId);
        verify(memoryRepository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void nearDuplicateFactsAreSkippedAndCapIsEnforced() {
        givenMessages(message("user", "stuff", false), message("assistant", "ok", false));
        when(extractionClient.extract(anyList(), anyList())).thenReturn(newFacts("duplicate fact", "new fact"));
        when(memoryRepository.maxSimilarity(eq(userId), any())).thenReturn(0.95, 0.20);

        service.processConversation(conversationId, true);

        verify(memoryRepository).insert(userId, "new fact", new float[768], conversationId);
        verify(memoryRepository, never()).insert(any(), eq("duplicate fact"), any(), any());
        verify(memoryRepository).trimToCap(userId, 200);
    }

    @Test
    void batchClaimedByAnotherWorkerIsNotProcessedTwice() {
        givenMessages(message("user", "stuff", false), message("assistant", "ok", false));
        when(memoryRepository.moveProcessedUntil(any(), any(), any())).thenReturn(false);

        service.processConversation(conversationId, true);

        verifyNoInteractions(extractionClient);
    }

    @Test
    void rateLimitedExtractionReleasesTheBatchWithoutThrowing() {
        givenMessages(message("user", "I'm learning guitar", false), message("assistant", "Fun!", false));
        when(extractionClient.extract(anyList(), anyList())).thenThrow(everyProviderFailed());

        assertThatCode(() -> service.processConversation(conversationId, true)).doesNotThrowAnyException();

        verify(memoryRepository).moveProcessedUntil(eq(conversationId), any(), isNull());
        verify(memoryRepository, never()).insert(any(), any(), any(), any());
    }

    @Test
    void rateLimitedEmbeddingMidBatchReleasesTheBatch() {
        givenMessages(message("user", "stuff", false), message("assistant", "ok", false));
        when(extractionClient.extract(anyList(), anyList())).thenReturn(newFacts("a fact"));
        when(embeddingClient.embed(anyString()))
                .thenThrow(new GeminiRetryableException("Gemini rate limited (429)", Duration.ofSeconds(30)));

        assertThatCode(() -> service.processConversation(conversationId, true)).doesNotThrowAnyException();

        verify(memoryRepository).moveProcessedUntil(eq(conversationId), any(), isNull());
    }

    @Test
    void whileEmbeddingsAreBackingOffTheBatchIsNotClaimedOrSent() {
        givenMessages(message("user", "stuff", false), message("assistant", "ok", false));
        backoff.onRetryableResponse("gemini-embedding-2", org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                null, "{}".getBytes());

        service.processConversation(conversationId, true);

        verifyNoInteractions(extractionClient, embeddingClient);
        verify(memoryRepository, never()).moveProcessedUntil(any(), any(), any());
    }
}
