package com.neuronest.backend.memory;

import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.chat.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.neuronest.backend.memory.MemoryTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryRecallServiceTest {

    @Mock UserRepository userRepository;
    @Mock GeminiEmbeddingClient embeddingClient;
    @Mock MemoryRepository memoryRepository;
    @Mock MemoryExtractionService extractionService;

    MemoryRecallService service;
    final UUID userA = UUID.randomUUID();
    final float[] query = new float[768];

    @BeforeEach
    void setUp() {
        service = new MemoryRecallService(properties(), userRepository, embeddingClient, memoryRepository, extractionService);
        when(userRepository.findById(userA)).thenReturn(Optional.of(user(userA, true)));
        when(embeddingClient.embed(anyString())).thenReturn(query);
    }

    private static final List<ChatMessage> LATER_MESSAGE =
            List.of(message("user", "hi", false), message("assistant", "hey", false));

    @Test
    void coreFactsAreAlwaysIncludedEvenWhenNothingIsSimilarEnough() {
        when(memoryRepository.findCoreFacts(userA)).thenReturn(List.of("The user's name is Abhishek."));
        when(memoryRepository.findMostSimilar(userA, query, 5)).thenReturn(List.of(
                new MemoryRepository.RecalledMemory(UUID.randomUUID(), "The user is preparing for exams", 0.50)));

        assertThat(service.recall(userA, LATER_MESSAGE, "do u know my name"))
                .containsExactly("The user's name is Abhishek.");
    }

    @Test
    void coreFactsComeFirstThenSimilarMemories() {
        when(memoryRepository.findCoreFacts(userA)).thenReturn(List.of("The user's name is Abhishek."));
        when(memoryRepository.findMostSimilar(userA, query, 5)).thenReturn(List.of(
                new MemoryRepository.RecalledMemory(UUID.randomUUID(), "The user is preparing for exams", 0.67)));

        assertThat(service.recall(userA, LATER_MESSAGE, "what am I preparing for"))
                .containsExactly("The user's name is Abhishek.", "The user is preparing for exams");
    }

    @Test
    void embeddingFailureStillReturnsCoreFacts() {
        when(memoryRepository.findCoreFacts(userA)).thenReturn(List.of("The user's name is Abhishek."));
        when(embeddingClient.embed(anyString())).thenThrow(new ResourceAccessException("Gemini down"));

        assertThat(service.recall(userA, LATER_MESSAGE, "hello")).containsExactly("The user's name is Abhishek.");
    }

    @Test
    void firstMessageExtractsEarlierConversationsBeforeRecalling() {
        when(memoryRepository.findCoreFacts(userA)).thenReturn(List.of("The user's name is Abhishek."));

        assertThat(service.recall(userA, List.of(), "do u know my name"))
                .containsExactly("The user's name is Abhishek.");

        var order = inOrder(extractionService, memoryRepository);
        order.verify(extractionService).flushPendingForUser(userA);
        order.verify(memoryRepository).findCoreFacts(userA);
    }

    @Test
    void laterMessagesDoNotFlush() {
        service.recall(userA, LATER_MESSAGE, "hello");

        verify(extractionService, never()).flushPendingForUser(any());
    }

    @Test
    void slowFlushIsCutOffAndRecallStillAnswers() {
        when(memoryRepository.findCoreFacts(userA)).thenReturn(List.of("The user's name is Abhishek."));
        doAnswer(inv -> {
            Thread.sleep(5_000);
            return null;
        }).when(extractionService).flushPendingForUser(userA);

        assertThat(service.recall(userA, List.of(), "hello")).containsExactly("The user's name is Abhishek.");
    }

    @Test
    void returnsOnlyMemoriesAboveThresholdForTheRequestingUser() {
        UUID relevant = UUID.randomUUID();
        when(memoryRepository.findMostSimilar(userA, query, 5)).thenReturn(List.of(
                new MemoryRepository.RecalledMemory(relevant, "The user is preparing for exams", 0.82),
                new MemoryRepository.RecalledMemory(UUID.randomUUID(), "Unrelated", 0.41)));

        List<String> memories = service.recall(userA, List.of(), "exams are stressing me out");

        assertThat(memories).containsExactly("The user is preparing for exams");
        verify(memoryRepository).findMostSimilar(eq(userA), any(), anyInt());
        verify(memoryRepository).markUsed(userA, List.of(relevant));
    }

    @Test
    void anonymousUserNeverTouchesMemory() {
        assertThat(service.recall(null, List.of(), "hello")).isEmpty();
        verifyNoInteractions(userRepository, embeddingClient, memoryRepository);
    }

    @Test
    void memoryDisabledMeansNoRecall() {
        when(userRepository.findById(userA)).thenReturn(Optional.of(user(userA, false)));

        assertThat(service.recall(userA, List.of(), "hello")).isEmpty();
        verifyNoInteractions(embeddingClient, memoryRepository);
    }

    @Test
    void conversationThatWasCrisisFlaggedGetsCoreFactsButNoSearchedMemories() {
        when(memoryRepository.findCoreFacts(userA)).thenReturn(List.of("The user's name is Abhishek."));
        List<ChatMessage> history = List.of(
                message("user", "crisis", true),
                message("assistant", "helpline", true));

        assertThat(service.recall(userA, history, "what is my name"))
                .containsExactly("The user's name is Abhishek.");
        verifyNoInteractions(embeddingClient);
        verify(memoryRepository, never()).findMostSimilar(any(), any(), anyInt());
    }

    @Test
    void firstMessageWaitsForAnIdentitySaveFromTheLastTurnBeforeReadingCoreFacts() {
        when(memoryRepository.findCoreFacts(userA)).thenReturn(List.of("The user's name is Abhishek."));

        service.recall(userA, List.of(), "do u know my name");

        var order = inOrder(extractionService, memoryRepository);
        order.verify(extractionService).awaitIdentityFacts(eq(userA), any());
        order.verify(extractionService).flushPendingForUser(userA);
        order.verify(memoryRepository).findCoreFacts(userA);
    }

    @Test
    void userMessageFlaggedOnlyBecauseNoVerdictWasObtainedDoesNotBlockRecall() {
        // A failed safety check saves the user message flagged (to keep it out of memory)
        // with no reply; that is not a crisis disclosure.
        when(memoryRepository.findCoreFacts(userA)).thenReturn(List.of("The user's name is Abhishek."));
        List<ChatMessage> history = List.of(
                message("user", "hi", false), message("assistant", "hey", false),
                message("user", "message whose turn failed", true));

        assertThat(service.recall(userA, history, "what am I preparing for"))
                .containsExactly("The user's name is Abhishek.");
    }

    @Test
    void embeddingFailureReturnsNoMemories() {
        when(embeddingClient.embed(anyString())).thenThrow(new ResourceAccessException("Gemini down"));

        assertThat(service.recall(userA, List.of(), "hello")).isEmpty();
    }

    @Test
    void slowEmbeddingIsCutOffByTheRecallBudget() {
        service = new MemoryRecallService(properties(Duration.ofMillis(200)), userRepository, embeddingClient,
                memoryRepository, extractionService);
        when(embeddingClient.embed(anyString())).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return query;
        });

        long start = System.nanoTime();
        List<String> memories = service.recall(userA, List.of(), "hello");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(memories).isEmpty();
        assertThat(elapsedMs).isLessThan(2_000);
    }

    @Test
    void firstMessageOfAConversationGetsTheLongerBudget() {
        service = new MemoryRecallService(properties(Duration.ofMillis(200), Duration.ofSeconds(3)),
                userRepository, embeddingClient, memoryRepository, extractionService);
        when(embeddingClient.embed(anyString())).thenAnswer(inv -> {
            Thread.sleep(600);
            return query;
        });
        when(memoryRepository.findMostSimilar(userA, query, 5)).thenReturn(List.of(
                new MemoryRepository.RecalledMemory(UUID.randomUUID(), "The user is preparing for exams", 0.82)));

        assertThat(service.recall(userA, List.of(), "hello")).containsExactly("The user is preparing for exams");

        List<ChatMessage> history = List.of(message("user", "hi", false), message("assistant", "hey", false));
        assertThat(service.recall(userA, history, "hello")).isEmpty();
    }

    @Test
    void warmUpRunsAQueryForTheUserInTheBackground() {
        service.warmUp(userA);

        verify(memoryRepository, timeout(1_000)).warmUp(userA);
    }

    @Test
    void warmUpFailureIsSwallowed() {
        doThrow(new RuntimeException("db down")).when(memoryRepository).warmUp(any());

        service.warmUp(userA);

        verify(memoryRepository, timeout(1_000)).warmUp(userA);
    }

    @Test
    void anonymousUserIsNeverWarmedUp() {
        service.warmUp(null);

        verifyNoInteractions(memoryRepository);
    }
}
