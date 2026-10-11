package com.neuronest.backend.kb;

import com.neuronest.backend.kb.KbChunk.SectionKind;
import com.neuronest.backend.memory.GeminiEmbeddingClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {

    private final KbRepository repository = mock(KbRepository.class);
    private final GeminiEmbeddingClient embeddingClient = mock(GeminiEmbeddingClient.class);

    static KbProperties properties(boolean enabled, Duration budget) {
        return new KbProperties(enabled, "classpath:kb/", 3, 0.60, 12, 0.03, budget, 300, true,
                Duration.ZERO, Duration.ofMinutes(2), 5);
    }

    private KnowledgeRetrievalService service() {
        return new KnowledgeRetrievalService(properties(true, Duration.ofSeconds(2)), repository, embeddingClient);
    }

    private static KbChunk chunk(String id, SectionKind kind, String safetyLevel, double similarity) {
        return new KbChunk(id, id, "topic", kind.name(), kind, "technique", safetyLevel, "NIMH",
                id + " - " + kind + "\nbody", similarity);
    }

    @Test
    void greetingsThanksAndShortSmallTalkAreSkipped() {
        assertThat(KnowledgeRetrievalService.isSmallTalk("hi")).isTrue();
        assertThat(KnowledgeRetrievalService.isSmallTalk("tu kasa aahes")).isTrue();
        assertThat(KnowledgeRetrievalService.isSmallTalk("ok 👍")).isTrue();
        assertThat(KnowledgeRetrievalService.isSmallTalk("thanks, feeling a bit better")).isTrue();

        assertThat(KnowledgeRetrievalService.isSmallTalk("stressed")).isFalse();
        assertThat(KnowledgeRetrievalService.isSmallTalk("yaar kuch din se neend nahi aa rahi")).isFalse();
        assertThat(KnowledgeRetrievalService.isSmallTalk("I studied so hard but blanked in the exam")).isFalse();
        assertThat(KnowledgeRetrievalService.isSmallTalk("hi, I can't sleep at all")).isFalse();
    }

    @Test
    void crisisMessagesAndCrisisConversationsNeverReachRetrieval() {
        assertThat(service().retrieveAsync("I want to die, I can't sleep", null, false, true).join()).isEmpty();
        assertThat(service().retrieveAsync("I can't sleep before exams", null, true, false).join()).isEmpty();
        assertThat(new KnowledgeRetrievalService(properties(false, Duration.ofSeconds(2)), repository,
                embeddingClient).retrieveAsync("I can't sleep before exams", null, false, false).join()).isEmpty();
        verifyNoInteractions(embeddingClient, repository);
    }

    @Test
    void searchesWithTheMessagePlusTheEndOfTheSummary() {
        float[] query = new float[768];
        when(embeddingClient.embed(anyString())).thenReturn(query);
        when(repository.findMostSimilar(query, 12)).thenReturn(List.of(chunk("sleep", SectionKind.OVERVIEW, "low", 0.7)));
        String summary = "x".repeat(500) + "The user has exams next week.";

        List<KbChunk> found = service().retrieveAsync("I can't sleep before exams", summary, false, false).join();

        assertThat(found).extracting(KbChunk::docId).containsExactly("sleep");
        String expected = service().queryText("I can't sleep before exams", summary);
        assertThat(expected).startsWith("I can't sleep before exams\n\nConversation so far: ")
                .endsWith("The user has exams next week.").hasSize("I can't sleep before exams".length() + 23 + 300);
        verify(embeddingClient).embed(expected);
    }

    @Test
    void failuresAndTimeoutsMeanNoKnowledgeNotAFailedTurn() {
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("Gemini down"));
        assertThat(service().retrieveAsync("I can't sleep before exams", null, false, false).join()).isEmpty();

        GeminiEmbeddingClient slow = mock(GeminiEmbeddingClient.class);
        when(slow.embed(anyString())).thenAnswer(i -> {
            Thread.sleep(2_000);
            return new float[768];
        });
        when(repository.findMostSimilar(any(), anyInt())).thenReturn(List.of(chunk("a", SectionKind.OVERVIEW, "low", 0.9)));
        KnowledgeRetrievalService fast = new KnowledgeRetrievalService(properties(true, Duration.ofMillis(100)),
                repository, slow);
        long start = System.nanoTime();
        assertThat(fast.retrieveAsync("I can't sleep before exams", null, false, false).join()).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void selectionKeepsThresholdPrefersOverviewAndPracticalAndLimitsSafetySections() {
        List<KbChunk> chosen = service().select(List.of(
                chunk("other", SectionKind.OTHER, "low", 0.71),
                chunk("try", SectionKind.PRACTICAL, "low", 0.70),
                chunk("overview", SectionKind.OVERVIEW, "low", 0.69),
                chunk("safety-low", SectionKind.SAFETY, "low", 0.68),
                chunk("below", SectionKind.OVERVIEW, "low", 0.55)));

        // Preferred sections overtake a slightly closer "other" section; the low-safety
        // "Safety and limitations" chunk is not the top match, so it is left out.
        assertThat(chosen).extracting(KbChunk::docId).containsExactly("try", "overview", "other");
    }

    @Test
    void aSafetySectionIsUsedWhenItIsTheTopMatchOrItsDocIsMediumSafety() {
        assertThat(service().select(List.of(
                chunk("safety-top", SectionKind.SAFETY, "low", 0.80),
                chunk("try", SectionKind.PRACTICAL, "low", 0.70)))).extracting(KbChunk::docId)
                .containsExactly("safety-top", "try");
        assertThat(service().select(List.of(
                chunk("try", SectionKind.PRACTICAL, "low", 0.80),
                chunk("safety-medium", SectionKind.SAFETY, "medium", 0.70)))).extracting(KbChunk::docId)
                .containsExactly("try", "safety-medium");
        assertThat(service().select(List.of(chunk("below", SectionKind.OVERVIEW, "low", 0.59)))).isEmpty();
    }
}
