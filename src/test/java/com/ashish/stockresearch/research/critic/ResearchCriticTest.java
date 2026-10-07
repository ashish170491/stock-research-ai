package com.ashish.stockresearch.research.critic;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ashish.stockresearch.service.OllamaCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// S6-4
class ResearchCriticTest {

    private static final class ScriptedModel implements ChatModel {

        private final String reply;
        private final List<Prompt> prompts = new ArrayList<>();

        ScriptedModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(reply).build())));
        }
    }

    private static final class PassThrough implements CallAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return chain.nextCall(request);
        }

        @Override
        public String getName() {
            return "pass-through";
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    private static final OllamaCalls OLLAMA = new OllamaCalls("qwen3:8b", "http://localhost:11434");

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger criticLogger;

    @BeforeEach
    void captureLogs() {
        criticLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ResearchCritic.class);
        criticLogger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        criticLogger.addAppender(appender);
    }

    @AfterEach
    void stopCapturing() {
        criticLogger.detachAppender(appender);
    }

    private static ResearchCritic critic(ChatModel model) {
        return new ResearchCritic(ChatClient.builder(model), new PassThrough(), OLLAMA);
    }

    @Test
    void parsesAnAcceptVerdictWithNothingFlagged() {
        ScriptedModel model = new ScriptedModel(
                "{\"unsupportedClaims\":[],\"missedDataGaps\":[],\"missingRisks\":[],\"verdict\":\"ACCEPT\"}");

        Critique critique = critic(model).review("EVIDENCE", "a draft");

        assertThat(critique.needsRevision()).isFalse();
        assertThat(critique.unsupportedClaims()).isEmpty();
    }

    @Test
    void parsesAReviseVerdictWithWhatItFound() {
        ScriptedModel model = new ScriptedModel("{\"unsupportedClaims\":[\"ROE of 40%\"],"
                + "\"missedDataGaps\":[\"shareholding unavailable\"],\"missingRisks\":[\"a conflict is unmentioned\"],"
                + "\"verdict\":\"REVISE\"}");

        Critique critique = critic(model).review("EVIDENCE", "a draft");

        assertThat(critique.needsRevision()).isTrue();
        assertThat(critique.unsupportedClaims()).containsExactly("ROE of 40%");
        assertThat(critique.missedDataGaps()).containsExactly("shareholding unavailable");
        assertThat(critique.missingRisks()).containsExactly("a conflict is unmentioned");
    }

    @Test
    void treatsUnparseableOutputAsAcceptAndLogsAWarning() {
        ScriptedModel model = new ScriptedModel("I think this draft looks fine overall.");

        Critique critique = critic(model).review("EVIDENCE", "a draft");

        assertThat(critique.needsRevision()).isFalse();
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("no usable answer");
        });
    }

    @Test
    void isGivenNoTools() {
        ScriptedModel model = new ScriptedModel(
                "{\"unsupportedClaims\":[],\"missedDataGaps\":[],\"missingRisks\":[],\"verdict\":\"ACCEPT\"}");

        critic(model).review("EVIDENCE", "a draft");

        assertThat(model.prompts).singleElement().satisfies(prompt -> {
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolOptions) {
                assertThat(toolOptions.getToolCallbacks()).isNullOrEmpty();
            }
        });
    }
}
