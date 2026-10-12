package com.ashish.stockresearch.agent;

import com.ashish.stockresearch.TestPostgres;
import com.ashish.stockresearch.orchestrator.OrchestratorCheckpoint;
import com.ashish.stockresearch.orchestrator.PlanStep;
import com.ashish.stockresearch.orchestrator.ResearchPlan;
import com.ashish.stockresearch.orchestrator.StepResult;
import com.ashish.stockresearch.orchestrator.StepType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Conversations in PostgreSQL: they outlive a restart, and are deleted after the retention period. */
class ConversationStorageTest {

    /** As long as AiController allows a conversation id to be. */
    private static final String LONG_ID = "c".repeat(64);

    private final EmbeddedDatabase database = TestPostgres.newDatabase();
    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void dropDatabase() {
        database.shutdown();
    }

    /** The application's chat memory, as a freshly started application builds it. */
    private ChatMemory chatMemory() {
        return new ConversationConfiguration().chatMemory(JdbcChatMemoryRepository.builder()
                .jdbcTemplate(new JdbcTemplate(database)).dialect(new PostgresChatMemoryRepositoryDialect()).build());
    }

    private JdbcSessionStore store(Clock clock) {
        return new JdbcSessionStore(database, json, clock);
    }

    private static void exchange(ChatMemory memory, String id, int n) {
        memory.add(id, List.of(new UserMessage("question " + n), new AssistantMessage("answer " + n)));
    }

    @Test
    void theChatHistoryOutlivesARestartAndKeepsItsWindow() {
        ChatMemory before = chatMemory();
        for (int n = 1; n <= 12; n++) {
            exchange(before, LONG_ID, n);
        }

        ChatMemory after = chatMemory();

        assertThat(after.get(LONG_ID)).hasSize(ConversationConfiguration.MEMORY_WINDOW);
        assertThat(after.get(LONG_ID).get(0).getText()).isEqualTo("question 3");
    }

    // S8-4 across a restart: the paused plan, its shortlist and completed steps come back exactly.
    @Test
    void theWholeSessionRoundTripsThroughTheDatabase() {
        OrchestratorCheckpoint paused = new OrchestratorCheckpoint(new ResearchPlan("Find and compare IT leaders",
                List.of(new PlanStep(StepType.SCREEN, "screen Nifty 50 IT", List.of()),
                        new PlanStep(StepType.COMPARE, "compare the shortlist", List.of("TCS", "INFY")))),
                1, List.of("TCS", "INFY"), List.of(new StepResult(StepType.SCREEN, "screen", "## Screen\n2 met",
                List.of("TCS", "INFY"))), 1, 3, Duration.ofSeconds(95));
        SessionState state = new SessionState(List.of("LUPIN"), List.of("evidence 1", "evidence 2"), paused,
                List.of("rating", "moat"), "LUPIN", "Is the debt high?");

        store(Clock.systemUTC()).save(LONG_ID, state);

        assertThat(store(Clock.systemUTC()).load(LONG_ID)).contains(state);
        assertThat(store(Clock.systemUTC()).load("never-seen")).isEmpty();
    }

    @Test
    void aSaveReplacesTheConversationsStateAndADeleteRemovesIt() {
        JdbcSessionStore store = store(Clock.systemUTC());
        store.save("c1", new SessionState(List.of("TCS"), List.of(), null, List.of(), null, null));
        store.save("c1", new SessionState(List.of("INFY"), List.of(), null, List.of(), null, null));

        assertThat(store.load("c1")).map(SessionState::companies).contains(List.of("INFY"));
        store.delete("c1");
        assertThat(store.load("c1")).isEmpty();
    }

    @Test
    void aStateThisVersionCannotReadStartsTheConversationEmpty() {
        new JdbcTemplate(database).update("INSERT INTO conversation_session VALUES ('c1', "
                + "CAST('{\"checkpoint\": {\"nextStepIndex\": \"not a number\"}}' AS JSONB), now())");

        assertThat(store(Clock.systemUTC()).load("c1")).isEmpty();
    }

    // A conversation is kept while its session changed or a message was added within the retention period.
    @Test
    void deletesOnlyConversationsUnusedForTheRetentionPeriod() {
        ChatMemory memory = chatMemory();
        Clock now = Clock.systemUTC();
        // "old": a session and messages from now; "recent": messages from now but a session that changed later;
        // "orphan": messages only
        store(now).save("old", new SessionState(List.of("TCS"), List.of(), null, List.of(), null, null));
        exchange(memory, "old", 1);
        store(Clock.offset(now, Duration.ofDays(2))).save("recent",
                new SessionState(List.of("INFY"), List.of(), null, List.of(), null, null));
        exchange(memory, "recent", 1);
        exchange(memory, "orphan", 1);
        ResearchSessions sessions = new ResearchSessions(store(now));
        sessions.get("old");

        // 31 days on, the cutoff is a day after "now" and before "recent"'s last change
        ConversationRetentionJob job = new ConversationRetentionJob(database, sessions,
                Clock.offset(now, Duration.ofDays(31)), Duration.ofDays(30));
        ConversationRetentionJob.Result result = job.deleteIdle();

        assertThat(result.conversations()).containsExactlyInAnyOrder("old", "orphan");
        assertThat(result.sessions()).isEqualTo(1);
        assertThat(result.messages()).isEqualTo(4);
        assertThat(memory.get("old")).isEmpty();
        assertThat(memory.get("orphan")).isEmpty();
        assertThat(memory.get("recent")).hasSize(2);
        assertThat(store(now).load("recent")).isPresent();
        assertThat(store(now).load("old")).isEmpty();
        // nor is the deleted conversation still in memory
        assertThat(sessions.get("old").companies()).isEmpty();
    }

    @Test
    void nothingToDeleteDeletesNothing() {
        store(Clock.systemUTC()).save("c1", SessionState.empty());

        ConversationRetentionJob.Result result = new ConversationRetentionJob(database,
                new ResearchSessions(SessionStore.NONE), Clock.systemUTC(), Duration.ofDays(30)).deleteIdle();

        assertThat(result.conversations()).isEmpty();
        assertThat(new JdbcTemplate(database).queryForObject("SELECT count(*) FROM conversation_session",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void lastActivityIsRecordedInUtc() {
        Clock fixed = Clock.fixed(java.time.Instant.parse("2026-10-12T05:00:00Z"), ZoneOffset.UTC);
        store(fixed).save("c1", SessionState.empty());

        assertThat(new JdbcTemplate(database).queryForObject(
                "SELECT updated_at FROM conversation_session WHERE conversation_id = 'c1'", java.time.OffsetDateTime.class)
                .toInstant()).isEqualTo(fixed.instant());
    }
}
