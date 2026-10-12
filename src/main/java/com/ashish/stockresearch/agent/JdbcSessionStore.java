package com.ashish.stockresearch.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Each conversation's {@link SessionState} as one JSONB row of {@code conversation_session}
 * (db/migration/V3__conversations.sql), written on every change, so a restart loses nothing a conversation had
 * established - not even a research plan paused for "continue".
 */
@Component
public class JdbcSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionStore.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public JdbcSessionStore(DataSource dataSource, JsonMapper json, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<SessionState> load(String conversationId) {
        Optional<String> stored = jdbc.sql("SELECT state::text FROM conversation_session WHERE conversation_id = :id")
                .param("id", conversationId).query(String.class).optional();
        try {
            return stored.map(text -> json.readValue(text, SessionState.class));
        } catch (JacksonException ex) {
            // a state this version cannot read (written by an incompatible one) starts the conversation afresh
            log.warn("Could not read the stored session of conversation {}; starting it empty: {}", conversationId,
                    ex.getOriginalMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String conversationId, SessionState state) {
        jdbc.sql("""
                        INSERT INTO conversation_session (conversation_id, state, updated_at)
                        VALUES (:id, CAST(:state AS JSONB), :now)
                        ON CONFLICT (conversation_id) DO UPDATE SET state = EXCLUDED.state, updated_at = EXCLUDED.updated_at
                        """)
                .param("id", conversationId).param("state", json.writeValueAsString(state))
                .param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
    }

    @Override
    public void delete(String conversationId) {
        jdbc.sql("DELETE FROM conversation_session WHERE conversation_id = :id").param("id", conversationId).update();
    }
}
