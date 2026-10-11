package com.neuronest.backend.kb;

import com.neuronest.backend.kb.KbChunk.SectionKind;
import com.neuronest.backend.kb.KbDocumentParser.KbDocument;
import com.neuronest.backend.kb.KbDocumentParser.KbSection;
import com.pgvector.PGvector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// All SQL for kb_chunks: the shared (not per-user) wellness library, one row per H2 section.
@Repository
public class KbRepository {

    /** What is stored for a doc, to decide whether it must be re-embedded. */
    record IngestedVersion(String version, int chunkerRevision) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public KbRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // Additive and idempotent, like MemorySchema; safe on every boot. Needs the pgvector
    // extension, which long-term memory already uses.
    void ensureSchema(int dimensions) {
        jdbc.execute("""
                create table if not exists kb_chunks (
                  id bigserial primary key,
                  doc_id text not null,
                  doc_title text not null,
                  topic text not null,
                  section text not null,
                  section_kind text not null,
                  doc_type text,
                  safety_level text,
                  source_name text,
                  version text not null,
                  chunker_rev int not null,
                  content text not null,
                  embedding vector(%d) not null,
                  ingested_at timestamptz not null default now(),
                  unique (doc_id, section))""".formatted(dimensions));
    }

    Map<String, IngestedVersion> ingestedVersions() {
        Map<String, IngestedVersion> versions = new HashMap<>();
        jdbc.query("select distinct doc_id, version, chunker_rev from kb_chunks", rs -> {
            versions.put(rs.getString("doc_id"),
                    new IngestedVersion(rs.getString("version"), rs.getInt("chunker_rev")));
        });
        return versions;
    }

    /** Replaces all chunks of one doc atomically; embeddings are in section order. */
    void replaceDocument(KbDocument doc, List<float[]> embeddings, int chunkerRevision) {
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from kb_chunks where doc_id = ?", doc.docId());
            for (int i = 0; i < doc.sections().size(); i++) {
                KbSection section = doc.sections().get(i);
                jdbc.update("""
                                insert into kb_chunks (doc_id, doc_title, topic, section, section_kind, doc_type,
                                  safety_level, source_name, version, chunker_rev, content, embedding)
                                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        doc.docId(), doc.title(), doc.topic(), section.name(), section.kind().name(),
                        doc.docType(), doc.safetyLevel(), doc.sourceName(), doc.version(), chunkerRevision,
                        section.content(), new PGvector(embeddings.get(i)));
            }
        });
    }

    /** Removes docs that were deleted from the folder or are no longer retrievable. */
    int deleteDocumentsNotIn(Set<String> keepDocIds) {
        if (keepDocIds.isEmpty()) {
            return jdbc.update("delete from kb_chunks");
        }
        return jdbc.update("delete from kb_chunks where doc_id <> all (?)",
                (Object) keepDocIds.toArray(String[]::new));
    }

    List<KbChunk> findMostSimilar(float[] query, int limit) {
        PGvector vector = new PGvector(query);
        return jdbc.query("""
                        select doc_id, doc_title, topic, section, section_kind, doc_type, safety_level,
                               source_name, content, 1 - (embedding <=> ?) as similarity
                        from kb_chunks
                        order by embedding <=> ?
                        limit ?
                        """,
                (rs, i) -> new KbChunk(rs.getString("doc_id"), rs.getString("doc_title"), rs.getString("topic"),
                        rs.getString("section"), SectionKind.valueOf(rs.getString("section_kind")),
                        rs.getString("doc_type"), rs.getString("safety_level"), rs.getString("source_name"),
                        rs.getString("content"), rs.getDouble("similarity")),
                vector, vector, limit);
    }
}
