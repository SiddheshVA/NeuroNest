package com.neuronest.backend.kb;

import com.neuronest.backend.kb.KbDocumentParser.KbDocument;
import com.neuronest.backend.kb.KbRepository.IngestedVersion;
import com.neuronest.backend.memory.GeminiEmbeddingClient;
import com.neuronest.backend.memory.GeminiRetryableException;
import com.neuronest.backend.memory.MemoryProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Runs against the real kb/ folder with a mocked database and embedding API. */
class KbIngestionServiceTest {

    private final KbRepository repository = mock(KbRepository.class);
    private final GeminiEmbeddingClient embeddingClient = mock(GeminiEmbeddingClient.class);
    private final MemoryProperties memoryProperties = new MemoryProperties(true, "gemini-embedding-2", 768, 5, 0.60,
            Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1), 0.90, 200, 5, 5,
            Duration.ofMinutes(30), Duration.ofSeconds(2), Duration.ofSeconds(2));

    private KbIngestionService service() {
        return new KbIngestionService(KnowledgeRetrievalServiceTest.properties(true, Duration.ofSeconds(2)),
                memoryProperties, repository, embeddingClient, new PathMatchingResourcePatternResolver());
    }

    @Test
    void firstRunEmbedsEveryRetrievableDocButNeverTheHelplineDirectory() throws Exception {
        when(embeddingClient.embed(anyString())).thenReturn(new float[768]);
        when(repository.ingestedVersions()).thenReturn(Map.of());

        assertThat(service().ingest()).isTrue();

        verify(repository).ensureSchema(768);
        ArgumentCaptor<KbDocument> docs = ArgumentCaptor.forClass(KbDocument.class);
        verify(repository, times(36)).replaceDocument(docs.capture(), anyList(), anyInt());
        assertThat(docs.getAllValues()).extracting(KbDocument::docId).doesNotContain("support-services-india");
        int sections = docs.getAllValues().stream().mapToInt(d -> d.sections().size()).sum();
        verify(embeddingClient, times(sections)).embed(anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> kept = ArgumentCaptor.forClass(Set.class);
        verify(repository).deleteDocumentsNotIn(kept.capture());
        assertThat(kept.getValue()).hasSize(36).doesNotContain("support-services-india");
    }

    @Test
    void unchangedVersionsAreNotReEmbeddedAndAChangedOneIs() throws Exception {
        Map<String, IngestedVersion> stored = new HashMap<>();
        for (KbDocument doc : service().loadDocuments()) {
            stored.put(doc.docId(), new IngestedVersion(doc.version(), KbDocumentParser.CHUNKER_REVISION));
        }
        when(repository.ingestedVersions()).thenReturn(stored);

        assertThat(service().ingest()).isTrue();
        verify(repository, never()).replaceDocument(any(), anyList(), anyInt());
        verify(embeddingClient, never()).embed(anyString());

        stored.put("sleep-healthy-habits", new IngestedVersion("0", KbDocumentParser.CHUNKER_REVISION));
        stored.put("anxiety-anxious-moment", new IngestedVersion("1", 0)); // older chunker
        when(embeddingClient.embed(anyString())).thenReturn(new float[768]);
        service().ingest();
        ArgumentCaptor<KbDocument> docs = ArgumentCaptor.forClass(KbDocument.class);
        verify(repository, times(2)).replaceDocument(docs.capture(), anyList(), anyInt());
        assertThat(docs.getAllValues()).extracting(KbDocument::docId)
                .containsExactlyInAnyOrder("sleep-healthy-habits", "anxiety-anxious-moment");
    }

    @Test
    void rateLimitingStopsTheRunWithoutDeletingAnything() throws Exception {
        when(repository.ingestedVersions()).thenReturn(Map.of());
        when(embeddingClient.embed(anyString())).thenThrow(new GeminiRetryableException("rate limited", Duration.ofSeconds(30)));

        assertThat(service().ingest()).isFalse();

        verify(embeddingClient, times(1)).embed(anyString());
        verify(repository, never()).replaceDocument(any(), anyList(), anyInt());
        verify(repository, never()).deleteDocumentsNotIn(anySet());
    }
}
