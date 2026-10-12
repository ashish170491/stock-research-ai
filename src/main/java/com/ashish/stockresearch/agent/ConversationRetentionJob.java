package com.ashish.stockresearch.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Deletes every conversation unused for longer than {@code app.conversations.retention} (30 days): its chat
 * history, and its session with any topic choices, pending question or paused research plan. A conversation is in
 * use while either its session changed or a message was added within the period - a turn always adds messages.
 */
@Component
public class ConversationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ConversationRetentionJob.class);

    private static final int BATCH = 1_000;

    private final JdbcClient jdbc;
    private final ResearchSessions sessions;
    private final Clock clock;
    private final Duration retention;

    public ConversationRetentionJob(DataSource dataSource, ResearchSessions sessions, Clock clock,
                                    @Value("${app.conversations.retention:30d}") Duration retention) {
        this.jdbc = JdbcClient.create(dataSource);
        this.sessions = sessions;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(cron = "${app.conversations.cleanup-cron:0 0 3 * * *}", zone = "Asia/Kolkata")
    public void scheduled() {
        Result result = deleteIdle();
        log.info("Conversation retention ({}): deleted {} conversation(s) - {} session(s), {} message(s)", retention,
                result.conversations().size(), result.sessions(), result.messages());
    }

    /** What one clean-up deleted. */
    public record Result(List<String> conversations, int sessions, int messages) {
    }

    @Transactional
    public Result deleteIdle() {
        Instant cutoff = clock.instant().minus(retention);
        // spring_ai_chat_memory's timestamp has no zone: JdbcChatMemoryRepository writes it as a java.sql.Timestamp,
        // so the cutoff is compared the same way
        List<String> idle = jdbc.sql("""
                        SELECT conversation_id FROM (
                            SELECT conversation_id FROM conversation_session
                            UNION
                            SELECT conversation_id FROM spring_ai_chat_memory
                        ) known
                        WHERE NOT EXISTS (SELECT 1 FROM conversation_session s
                                          WHERE s.conversation_id = known.conversation_id AND s.updated_at >= :cutoff)
                          AND NOT EXISTS (SELECT 1 FROM spring_ai_chat_memory m
                                          WHERE m.conversation_id = known.conversation_id AND m."timestamp" >= :cutoffTs)
                        """)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).param("cutoffTs", Timestamp.from(cutoff))
                .query(String.class).list();
        if (idle.isEmpty()) {
            return new Result(List.of(), 0, 0);
        }
        int messages = 0;
        int stored = 0;
        // a bounded IN list per statement: PostgreSQL takes at most 32,767 bind parameters
        for (int from = 0; from < idle.size(); from += BATCH) {
            List<String> batch = idle.subList(from, Math.min(idle.size(), from + BATCH));
            messages += jdbc.sql("DELETE FROM spring_ai_chat_memory WHERE conversation_id IN (:ids)")
                    .param("ids", batch).update();
            stored += jdbc.sql("DELETE FROM conversation_session WHERE conversation_id IN (:ids)")
                    .param("ids", batch).update();
        }
        sessions.evict(idle);
        return new Result(List.copyOf(idle), stored, messages);
    }
}
