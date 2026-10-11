package com.neuronest.backend.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Runs the real SQL against Postgres + pgvector to prove one user can never
// read, use, or delete another user's memories. Skipped when Docker is not
// available on the machine.
@Testcontainers(disabledWithoutDocker = true)
class MemoryRepositoryIsolationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static JdbcTemplate jdbc;
    static MemoryRepository repository;

    UUID userA;
    UUID userB;
    UUID conversationA;
    UUID conversationB;

    @BeforeAll
    static void createSchema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        repository = new MemoryRepository(jdbc);
        jdbc.execute("create extension if not exists vector");
        jdbc.execute("""
                create table users (id uuid primary key, email varchar not null,
                                    memory_enabled boolean not null default true)""");
        jdbc.execute("create table sessions (id uuid primary key, user_id uuid)");
        jdbc.execute("""
                create table chat_conversations (id uuid primary key, session_id uuid not null,
                                                 memory_processed_until timestamptz)""");
        jdbc.execute("""
                create table chat_messages (id uuid primary key default gen_random_uuid(),
                                            conversation_id uuid not null, role varchar not null,
                                            content text not null, risk_flag boolean not null default false,
                                            created_at timestamptz not null default now())""");
        // Exactly the production DDL.
        jdbc.execute("""
                create table user_memories (
                  id uuid primary key default gen_random_uuid(),
                  user_id uuid not null references users(id) on delete cascade,
                  content text not null,
                  embedding vector(768) not null,
                  source_conversation_id uuid,
                  created_at timestamptz not null default now(),
                  last_used_at timestamptz)""");
        MemorySchema.apply(jdbc);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("truncate user_memories, chat_messages, chat_conversations, sessions, users");
        userA = createUser("a@example.com");
        userB = createUser("b@example.com");
        conversationA = createConversation(userA);
        conversationB = createConversation(userB);
    }

    @Test
    void recallOnlyReturnsTheRequestingUsersMemories() {
        float[] query = unit(0);
        // B owns an exact match; A only owns a weaker one.
        repository.insert(userB, "B: exact match", query, conversationB);
        repository.insert(userA, "A: partial match", mix(0, 1), conversationA);

        List<MemoryRepository.RecalledMemory> recalled = repository.findMostSimilar(userA, query, 10);

        assertThat(recalled).extracting(MemoryRepository.RecalledMemory::content).containsExactly("A: partial match");
        assertThat(repository.findAllForUser(userA)).extracting(MemoryRepository.StoredMemory::content)
                .containsExactly("A: partial match");
    }

    @Test
    void dedupeSimilarityIgnoresOtherUsers() {
        repository.insert(userB, "B fact", unit(3), conversationB);

        assertThat(repository.maxSimilarity(userA, unit(3))).isEqualTo(0.0);
        assertThat(repository.maxSimilarity(userB, unit(3))).isGreaterThan(0.99);
    }

    @Test
    void userCannotDeleteOrTouchAnotherUsersMemory() {
        repository.insert(userB, "B fact", unit(1), conversationB);
        UUID bMemory = repository.findAllForUser(userB).getFirst().id();

        assertThat(repository.deleteOne(userA, bMemory)).isFalse();
        repository.markUsed(userA, List.of(bMemory));
        assertThat(repository.deleteAll(userA)).isZero();
        assertThat(repository.deleteBySourceConversation(userA, conversationB)).isZero();
        repository.trimToCap(userA, 0);

        assertThat(repository.findAllForUser(userB)).hasSize(1);
        assertThat(jdbc.queryForObject("select last_used_at from user_memories where id = ?", Instant.class, bMemory))
                .isNull();
    }

    @Test
    void trimToCapKeepsMostRecentlyUsedAndOnlyAffectsThatUser() {
        repository.insert(userA, "old unused", unit(1), conversationA);
        repository.insert(userA, "old but used", unit(2), conversationA);
        repository.insert(userA, "newest", unit(3), conversationA);
        repository.insert(userB, "B fact", unit(4), conversationB);
        jdbc.update("update user_memories set created_at = now() - interval '2 days' where content like 'old%'");
        UUID used = repository.findAllForUser(userA).stream()
                .filter(m -> m.content().equals("old but used")).findFirst().orElseThrow().id();
        repository.markUsed(userA, List.of(used));

        assertThat(repository.trimToCap(userA, 2)).isEqualTo(1);

        assertThat(repository.findAllForUser(userA)).extracting(MemoryRepository.StoredMemory::content)
                .containsExactlyInAnyOrder("old but used", "newest");
        assertThat(repository.findAllForUser(userB)).hasSize(1);
    }

    @Test
    void coreFactIsOnePerKeyPerUserAndNeverLeaksOrGetsTrimmed() {
        repository.upsertCore(userA, "name", "The user's name is Abhi.", unit(5), conversationA);
        repository.upsertCore(userA, "name", "The user's name is Abhishek.", unit(5), conversationA);
        repository.upsertCore(userB, "name", "The user's name is Priya.", unit(5), conversationB);
        repository.insert(userA, "A regular fact", unit(6), conversationA);

        assertThat(repository.findCoreFacts(userA)).containsExactly("The user's name is Abhishek.");
        assertThat(repository.findCoreFacts(userB)).containsExactly("The user's name is Priya.");
        // Core facts are not part of the similarity search (they are always added separately).
        assertThat(repository.findMostSimilar(userA, unit(5), 10))
                .extracting(MemoryRepository.RecalledMemory::content).containsExactly("A regular fact");
        assertThat(repository.trimToCap(userA, 0)).isEqualTo(1);
        assertThat(repository.findCoreFacts(userA)).containsExactly("The user's name is Abhishek.");
    }

    @Test
    void updateContentOnlyRewritesTheUsersOwnRegularMemory() {
        repository.insert(userB, "B works at a bank", unit(1), conversationB);
        UUID bMemory = repository.findAllForUser(userB).getFirst().id();

        assertThat(repository.updateContent(userA, bMemory, "hijacked", unit(1), conversationA)).isFalse();
        assertThat(repository.updateContent(userB, bMemory, "B is a teacher", unit(2), conversationB)).isTrue();
        assertThat(repository.findAllForUser(userB)).extracting(MemoryRepository.StoredMemory::content)
                .containsExactly("B is a teacher");
        assertThat(repository.findForExtraction(userA, 10)).isEmpty();
    }

    @Test
    void unprocessedConversationLookupIsScopedToTheUser() {
        jdbc.update("insert into chat_messages (conversation_id, role, content) values (?, 'user', 'hi')", conversationA);
        jdbc.update("insert into chat_messages (conversation_id, role, content) values (?, 'user', 'hi')", conversationB);

        assertThat(repository.findUnprocessedConversationsForUser(userA, UUID.randomUUID()))
                .containsExactly(conversationA);
        assertThat(repository.findUnprocessedConversationsForUser(userA)).containsExactly(conversationA);
    }

    @Test
    void watermarkMovesOnlyFromTheExpectedValue() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant second = Instant.parse("2026-01-02T00:00:00Z");

        assertThat(repository.moveProcessedUntil(conversationA, null, first)).isTrue();
        assertThat(repository.moveProcessedUntil(conversationA, null, second)).isFalse();
        assertThat(repository.processedUntil(conversationA)).isEqualTo(first);
        assertThat(repository.moveProcessedUntil(conversationA, first, second)).isTrue();
    }

    private UUID createUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", id, email);
        return id;
    }

    private UUID createConversation(UUID owner) {
        UUID sessionId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        jdbc.update("insert into sessions (id, user_id) values (?, ?)", sessionId, owner);
        jdbc.update("insert into chat_conversations (id, session_id) values (?, ?)", conversationId, sessionId);
        return conversationId;
    }

    private static float[] unit(int axis) {
        float[] v = new float[768];
        v[axis] = 1f;
        return v;
    }

    private static float[] mix(int axisA, int axisB) {
        float[] v = new float[768];
        v[axisA] = 0.7f;
        v[axisB] = 0.7f;
        return v;
    }
}
