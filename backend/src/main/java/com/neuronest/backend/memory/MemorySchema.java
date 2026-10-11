package com.neuronest.backend.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// Additive, idempotent schema changes for user_memories (the table itself is
// created outside the app). Runs once at startup; safe to run on every boot.
//
// core_key marks a "core fact" (name, preferred language, ...): at most one per
// key per user, always recalled without a similarity search, never trimmed.
@Component
public class MemorySchema implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MemorySchema.class);

    static final List<String> STATEMENTS = List.of(
            "alter table if exists user_memories add column if not exists core_key text",
            """
            create unique index if not exists uq_user_memories_core_key
              on user_memories (user_id, core_key) where core_key is not null""");

    private final JdbcTemplate jdbc;

    public MemorySchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            apply(jdbc);
        } catch (Exception e) {
            // Without the column core facts can't be saved or recalled; regular memory still works.
            log.error("Memory: could not apply user_memories schema changes: {}", e.toString());
        }
    }

    static void apply(JdbcTemplate jdbc) {
        Boolean exists = jdbc.queryForObject("select to_regclass('user_memories') is not null", Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            return;
        }
        STATEMENTS.forEach(jdbc::execute);
    }
}
