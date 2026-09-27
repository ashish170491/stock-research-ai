package com.ashish.stockresearch.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchSessionsTest {

    private final ChatMemory chatMemory = new ConversationConfiguration().chatMemory();
    private final AtomicLong nanos = new AtomicLong();
    private final ResearchSessions sessions = new ResearchSessions(chatMemory, nanos::get, Runnable::run);

    private void exchange(String conversationId, int n) {
        chatMemory.add(conversationId, List.of(new UserMessage("question " + n), new AssistantMessage("answer " + n)));
    }

    @Test
    void anIdleConversationIsForgottenWithItsChatMemory() {
        sessions.get("c1").rememberCompanies(List.of("INFY"));
        exchange("c1", 1);

        nanos.addAndGet(ResearchSessions.IDLE_TIMEOUT.plusSeconds(1).toNanos());

        assertThat(sessions.get("c1").companies()).isEmpty();
        assertThat(chatMemory.get("c1")).isEmpty();
    }

    @Test
    void aConversationInUseIsKept() {
        sessions.get("c1").rememberCompanies(List.of("INFY"));
        exchange("c1", 1);

        nanos.addAndGet(ResearchSessions.IDLE_TIMEOUT.minus(Duration.ofMinutes(1)).toNanos());
        sessions.get("c1");
        nanos.addAndGet(Duration.ofMinutes(30).toNanos());

        assertThat(sessions.get("c1").companies()).containsExactly("INFY");
        assertThat(chatMemory.get("c1")).hasSize(2);
    }

    @Test
    void theModelSeesTheLastTenExchangesOfAConversation() {
        for (int n = 1; n <= 12; n++) {
            exchange("c1", n);
        }

        assertThat(chatMemory.get("c1")).hasSize(ConversationConfiguration.MEMORY_WINDOW);
        assertThat(chatMemory.get("c1").get(0).getText()).isEqualTo("question 3");
    }
}
