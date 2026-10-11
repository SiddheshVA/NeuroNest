package com.neuronest.backend.kb;

import com.neuronest.backend.kb.KbDocumentParser.KbDocument;
import com.neuronest.backend.kb.KbDocumentParser.KbSection;
import com.neuronest.backend.kb.KbRepository.IngestedVersion;
import com.neuronest.backend.memory.GeminiEmbeddingClient;
import com.neuronest.backend.memory.GeminiRetryableException;
import com.neuronest.backend.memory.MemoryProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Embeds the knowledge-base folder into kb_chunks once the app is up, in the background, so
// startup is never slowed down. Idempotent: a doc is re-embedded only when its front-matter
// `version` (or KbDocumentParser.CHUNKER_REVISION) changes. If a run stops early (e.g. Gemini
// rate limits), it retries later; until then retrieval simply uses what is already stored.
@Service
public class KbIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KbIngestionService.class);

    private final KbProperties properties;
    private final int embeddingDimensions;
    private final KbRepository repository;
    private final GeminiEmbeddingClient embeddingClient;
    private final ResourcePatternResolver resources;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("kb-ingest").factory());

    public KbIngestionService(KbProperties properties, MemoryProperties memoryProperties, KbRepository repository,
                              GeminiEmbeddingClient embeddingClient, ResourcePatternResolver resources) {
        this.properties = properties;
        this.embeddingDimensions = memoryProperties.embeddingDimensions();
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.resources = resources;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.enabled()) {
            scheduler.execute(() -> attempt(1));
        }
    }

    private void attempt(int number) {
        boolean complete;
        try {
            complete = ingest();
        } catch (Exception e) {
            log.error("Knowledge base: ingestion failed: {}", e.toString());
            complete = false;
        }
        if (!complete && number < properties.ingestMaxAttempts()) {
            Duration delay = properties.ingestRetryDelay();
            log.info("Knowledge base: ingestion incomplete; retrying in {} (attempt {}/{})", delay, number + 1,
                    properties.ingestMaxAttempts());
            scheduler.schedule(() -> attempt(number + 1), delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** @return true when every retrievable doc is stored at its current version */
    boolean ingest() throws IOException {
        repository.ensureSchema(embeddingDimensions);
        List<KbDocument> docs = loadDocuments();
        if (docs.isEmpty()) {
            // Most likely a wrong kb.location: keep what is stored rather than wipe it.
            log.warn("Knowledge base: no documents found at {}; nothing ingested", properties.location());
            return true;
        }
        Map<String, IngestedVersion> stored = repository.ingestedVersions();

        Set<String> keep = new HashSet<>();
        int embedded = 0;
        int unchanged = 0;
        boolean complete = true;
        for (KbDocument doc : docs) {
            if (!doc.retrievable()) {
                log.info("Knowledge base: '{}' is {} and is not ingested", doc.docId(), doc.safetyLevel());
                continue;
            }
            keep.add(doc.docId());
            IngestedVersion current = stored.get(doc.docId());
            if (current != null && current.version().equals(doc.version())
                    && current.chunkerRevision() == KbDocumentParser.CHUNKER_REVISION) {
                unchanged++;
                continue;
            }
            try {
                repository.replaceDocument(doc, embed(doc), KbDocumentParser.CHUNKER_REVISION);
                embedded++;
            } catch (GeminiRetryableException e) {
                log.warn("Knowledge base: embedding rate limited at '{}'; stopping this run", doc.docId());
                complete = false;
                break;
            } catch (Exception e) {
                log.warn("Knowledge base: could not ingest '{}': {}", doc.docId(), e.toString());
                complete = false;
            }
        }
        int removed = complete ? repository.deleteDocumentsNotIn(keep) : 0;
        log.info("Knowledge base: {} docs found; {} embedded, {} unchanged, {} removed{}", docs.size(), embedded,
                unchanged, removed, complete ? "" : " (incomplete)");
        return complete;
    }

    List<KbDocument> loadDocuments() throws IOException {
        String base = properties.location().endsWith("/") ? properties.location() : properties.location() + "/";
        List<KbDocument> docs = new ArrayList<>();
        for (Resource resource : resources.getResources(base + "**/*.md")) {
            String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
            KbDocumentParser.parse(markdown).ifPresentOrElse(docs::add,
                    () -> log.debug("Knowledge base: skipping {} (no front matter)", resource.getFilename()));
        }
        return docs;
    }

    private List<float[]> embed(KbDocument doc) throws InterruptedException {
        List<float[]> embeddings = new ArrayList<>();
        for (KbSection section : doc.sections()) {
            embeddings.add(embeddingClient.embed(section.content()));
            Thread.sleep(properties.ingestDelay().toMillis());
        }
        return embeddings;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
