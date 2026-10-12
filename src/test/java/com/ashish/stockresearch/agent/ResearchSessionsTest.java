package com.ashish.stockresearch.agent;

import com.ashish.stockresearch.orchestrator.OrchestratorCheckpoint;
import com.ashish.stockresearch.orchestrator.PlanStep;
import com.ashish.stockresearch.orchestrator.ResearchPlan;
import com.ashish.stockresearch.orchestrator.StepType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Sessions are read from the store when first needed and written back on every change. */
class ResearchSessionsTest {

    /** A store in a map, standing in for the database (JdbcSessionStoreTest covers the real one). */
    static final class MapStore implements SessionStore {
        final Map<String, SessionState> rows = new HashMap<>();
        boolean failing;

        @Override
        public Optional<SessionState> load(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void save(String id, SessionState state) {
            if (failing) {
                throw new IllegalStateException("database unreachable");
            }
            rows.put(id, state);
        }

        @Override
        public void delete(String id) {
            rows.remove(id);
        }
    }

    private final MapStore store = new MapStore();
    private final AtomicLong nanos = new AtomicLong();
    private final ResearchSessions sessions = new ResearchSessions(store, nanos::get);

    @Test
    void everyChangeIsStored() {
        ResearchSession session = sessions.get("c1");
        session.rememberCompanies(List.of("INFY"));
        session.rememberFocus(List.of("rating"));
        session.awaitCompanyFor("Is the debt high?");

        assertThat(store.rows.get("c1")).isEqualTo(new SessionState(List.of("INFY"), List.of(), null,
                List.of("rating"), null, "Is the debt high?"));

        session.takeAwaitingCompany();
        assertThat(store.rows.get("c1").awaitingCompanyFor()).isNull();
    }

    // A restart: a new ResearchSessions over the same store picks the conversation up where it left off.
    @Test
    void aConversationIsPickedUpAfterARestartWithItsPausedPlan() {
        OrchestratorCheckpoint paused = new OrchestratorCheckpoint(new ResearchPlan("compare",
                List.of(new PlanStep(StepType.SCREEN, "screen", List.of()))), 1, List.of("TCS", "INFY"), List.of(),
                1, 2, Duration.ofSeconds(40));
        sessions.get("c1").rememberCompanies(List.of("LUPIN"));
        sessions.get("c1").pause(paused);

        ResearchSessions restarted = new ResearchSessions(store, nanos::get);

        assertThat(restarted.get("c1").companies()).containsExactly("LUPIN");
        assertThat(restarted.get("c1").checkpoint()).contains(paused);
    }

    // Two idle hours drop the session from memory only: the conversation itself is kept.
    @Test
    void anIdleConversationLeavesMemoryButIsStillThere() {
        sessions.get("c1").rememberCompanies(List.of("INFY"));

        nanos.addAndGet(ResearchSessions.IDLE_TIMEOUT.plusSeconds(1).toNanos());

        assertThat(sessions.get("c1").companies()).containsExactly("INFY");
    }

    @Test
    void forgettingAConversationRemovesItEverywhere() {
        sessions.get("c1").rememberCompanies(List.of("INFY"));

        sessions.forget("c1");

        assertThat(store.rows).doesNotContainKey("c1");
        assertThat(sessions.get("c1").companies()).isEmpty();
    }

    // A store that cannot be written never fails the user's answer: the session is still right in memory.
    @Test
    void aStoreThatCannotBeWrittenDoesNotFailTheTurn() {
        store.failing = true;

        sessions.get("c1").rememberCompanies(List.of("INFY"));

        assertThat(sessions.get("c1").companies()).containsExactly("INFY");
    }

    @Test
    void theModelSeesTheLastTenExchangesOfAConversation() {
        ChatMemory chatMemory = new ConversationConfiguration().chatMemory(new InMemoryChatMemoryRepository());
        for (int n = 1; n <= 12; n++) {
            chatMemory.add("c1", List.of(new UserMessage("question " + n), new AssistantMessage("answer " + n)));
        }

        assertThat(chatMemory.get("c1")).hasSize(ConversationConfiguration.MEMORY_WINDOW);
        assertThat(chatMemory.get("c1").get(0).getText()).isEqualTo("question 3");
    }
}
