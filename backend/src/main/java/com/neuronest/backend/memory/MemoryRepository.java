package com.neuronest.backend.memory;

import com.pgvector.PGvector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// All SQL for user_memories (and the extraction watermark on chat_conversations).
//
// ISOLATION RULE: every statement that reads or writes user_memories takes the
// owning userId as a required argument and filters on user_id = ?. Never add a
// method here that touches user_memories without that predicate.
@Repository
public class MemoryRepository {

    public record StoredMemory(UUID id, String content, Instant createdAt) {}

    public record RecalledMemory(UUID id, String content, double similarity) {}

    /** A memory as shown to extraction; coreKey is null for regular (searched) memories. */
    public record KnownMemory(UUID id, String coreKey, String content) {}

    private final JdbcTemplate jdbc;

    public MemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- recall -------------------------------------------------------------

    // Core facts (name, preferred language, ...) are always recalled, without a search.
    public List<String> findCoreFacts(UUID userId) {
        return jdbc.queryForList("""
                        select content from user_memories
                        where user_id = ? and core_key is not null
                        order by core_key
                        """,
                String.class, userId);
    }

    // Regular memories only: core facts are always added separately.
    public List<RecalledMemory> findMostSimilar(UUID userId, float[] query, int limit) {
        PGvector vector = new PGvector(query);
        return jdbc.query("""
                        select id, content, 1 - (embedding <=> ?) as similarity
                        from user_memories
                        where user_id = ? and core_key is null
                        order by embedding <=> ?
                        limit ?
                        """,
                (rs, i) -> new RecalledMemory(rs.getObject("id", UUID.class), rs.getString("content"),
                        rs.getDouble("similarity")),
                vector, userId, vector, limit);
    }

    // Cheap query run when a user opens a conversation, so a suspended database
    // (and this user's rows) are warm before recall needs them.
    public void warmUp(UUID userId) {
        jdbc.queryForObject("select count(*) from user_memories where user_id = ?", Long.class, userId);
    }

    public void markUsed(UUID userId, List<UUID> memoryIds) {
        if (memoryIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("update user_memories set last_used_at = now() where user_id = ? and id = ?",
                memoryIds.stream().map(id -> new Object[]{userId, id}).toList());
    }

    // ---- save ---------------------------------------------------------------

    // Highest cosine similarity between the given vector and any of this user's
    // memories, or 0 when the user has none.
    public double maxSimilarity(UUID userId, float[] embedding) {
        Double max = jdbc.queryForObject(
                "select max(1 - (embedding <=> ?)) from user_memories where user_id = ?",
                Double.class, new PGvector(embedding), userId);
        return max == null ? 0.0 : max;
    }

    public void insert(UUID userId, String content, float[] embedding, UUID sourceConversationId) {
        jdbc.update("""
                        insert into user_memories (user_id, content, embedding, source_conversation_id)
                        values (?, ?, ?, ?)
                        """,
                userId, content, new PGvector(embedding), sourceConversationId);
    }

    // Sets the user's core fact for `coreKey`, replacing the previous value (e.g. a new name).
    public void upsertCore(UUID userId, String coreKey, String content, float[] embedding,
                           UUID sourceConversationId) {
        jdbc.update("""
                        insert into user_memories (user_id, core_key, content, embedding, source_conversation_id)
                        values (?, ?, ?, ?, ?)
                        on conflict (user_id, core_key) where core_key is not null
                        do update set content = excluded.content, embedding = excluded.embedding,
                                      source_conversation_id = excluded.source_conversation_id,
                                      created_at = now()
                        """,
                userId, coreKey, content, new PGvector(embedding), sourceConversationId);
    }

    // Rewrites one of the user's regular memories (a newer fact contradicts it).
    public boolean updateContent(UUID userId, UUID memoryId, String content, float[] embedding,
                                 UUID sourceConversationId) {
        return jdbc.update("""
                        update user_memories
                        set content = ?, embedding = ?, source_conversation_id = ?, created_at = now()
                        where user_id = ? and id = ? and core_key is null
                        """,
                content, new PGvector(embedding), sourceConversationId, userId, memoryId) > 0;
    }

    // What extraction is shown so it can avoid duplicates and correct outdated facts:
    // every core fact, plus the `limit` most recently used/created regular memories.
    public List<KnownMemory> findForExtraction(UUID userId, int limit) {
        return jdbc.query("""
                        (select id, core_key, content from user_memories
                         where user_id = ? and core_key is not null
                         order by core_key)
                        union all
                        (select id, core_key, content from user_memories
                         where user_id = ? and core_key is null
                         order by coalesce(last_used_at, created_at) desc, created_at desc
                         limit ?)
                        """,
                (rs, i) -> new KnownMemory(rs.getObject("id", UUID.class), rs.getString("core_key"),
                        rs.getString("content")),
                userId, userId, limit);
    }

    // Keeps the user's `cap` most recently used (or, if never used, most recently
    // created) regular memories and deletes the rest; core facts are never trimmed.
    // Returns the number deleted.
    public int trimToCap(UUID userId, int cap) {
        return jdbc.update("""
                        delete from user_memories
                        where user_id = ?
                          and id in (select id from user_memories
                                     where user_id = ? and core_key is null
                                     order by coalesce(last_used_at, created_at) desc, created_at desc
                                     offset ?)
                        """,
                userId, userId, cap);
    }

    // ---- user-facing management ----------------------------------------------

    public List<StoredMemory> findAllForUser(UUID userId) {
        return jdbc.query("""
                        select id, content, created_at from user_memories
                        where user_id = ?
                        order by created_at desc
                        """,
                (rs, i) -> new StoredMemory(rs.getObject("id", UUID.class), rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant()),
                userId);
    }

    public boolean deleteOne(UUID userId, UUID memoryId) {
        return jdbc.update("delete from user_memories where user_id = ? and id = ?", userId, memoryId) > 0;
    }

    public int deleteAll(UUID userId) {
        return jdbc.update("delete from user_memories where user_id = ?", userId);
    }

    public int deleteBySourceConversation(UUID userId, UUID conversationId) {
        return jdbc.update("delete from user_memories where user_id = ? and source_conversation_id = ?",
                userId, conversationId);
    }

    // ---- extraction watermark (chat_conversations.memory_processed_until) ----

    public Instant processedUntil(UUID conversationId) {
        List<Timestamp> rows = jdbc.queryForList(
                "select memory_processed_until from chat_conversations where id = ?",
                Timestamp.class, conversationId);
        return rows.isEmpty() || rows.getFirst() == null ? null : rows.getFirst().toInstant();
    }

    // Moves the watermark from `expected` to `next` only if nobody else moved it
    // first. Returns false when another worker already claimed this batch.
    public boolean moveProcessedUntil(UUID conversationId, Instant expected, Instant next) {
        return jdbc.update("""
                        update chat_conversations
                        set memory_processed_until = cast(? as timestamptz)
                        where id = ? and memory_processed_until is not distinct from cast(? as timestamptz)
                        """,
                toTimestamp(next), conversationId, toTimestamp(expected)) == 1;
    }

    // Conversations of user-owned sessions with user messages past the watermark
    // and no activity for `idleAfter` — i.e. conversations that have "ended".
    public List<UUID> findIdleUnprocessedConversations(Duration idleAfter, int limit) {
        return jdbc.queryForList("""
                        select c.id from chat_conversations c
                        join sessions s on s.id = c.session_id
                        where s.user_id is not null
                          and exists (select 1 from chat_messages m
                                      where m.conversation_id = c.id and m.role = 'user'
                                        and m.created_at > coalesce(c.memory_processed_until, '-infinity'))
                          and (select max(m.created_at) from chat_messages m where m.conversation_id = c.id)
                              < now() - make_interval(secs => ?)
                        limit ?
                        """,
                UUID.class, (double) idleAfter.toSeconds(), limit);
    }

    // The given user's other conversations that still have unprocessed user messages.
    public List<UUID> findUnprocessedConversationsForUser(UUID userId, UUID excludeConversationId) {
        return jdbc.queryForList("""
                        select c.id from chat_conversations c
                        join sessions s on s.id = c.session_id
                        where s.user_id = ?
                          and c.id <> ?
                          and exists (select 1 from chat_messages m
                                      where m.conversation_id = c.id and m.role = 'user'
                                        and m.created_at > coalesce(c.memory_processed_until, '-infinity'))
                        """,
                UUID.class, userId, excludeConversationId);
    }

    // All of the given user's conversations that still have unprocessed user messages.
    public List<UUID> findUnprocessedConversationsForUser(UUID userId) {
        return jdbc.queryForList("""
                        select c.id from chat_conversations c
                        join sessions s on s.id = c.session_id
                        where s.user_id = ?
                          and exists (select 1 from chat_messages m
                                      where m.conversation_id = c.id and m.role = 'user'
                                        and m.created_at > coalesce(c.memory_processed_until, '-infinity'))
                        """,
                UUID.class, userId);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
