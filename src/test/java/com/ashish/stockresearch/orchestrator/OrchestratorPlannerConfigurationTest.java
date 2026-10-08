package com.ashish.stockresearch.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the orchestrator's planner comes from (S8-8): the same local {@code ChatClient.Builder} every
 * other component uses by default, and a hosted model only once the {@code hosted-planner} profile is
 * active and a qualified {@code plannerChatModel} bean exists. Tools, data and verification are
 * untouched either way - this configuration wires nothing else.
 */
class OrchestratorPlannerConfigurationTest {

    private static final class FixedModel implements ChatModel {

        private final String content;

        FixedModel(String content) {
            this.content = content;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("test").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
        }
    }

    @Test
    void defaultsToTheLocalChatClientBuilder() {
        ChatClient.Builder local = ChatClient.builder(new FixedModel("local"));
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(OrchestratorPlannerConfiguration.class);
        context.registerBean(ChatClient.Builder.class, () -> local);
        context.refresh();

        PlannerChatClientBuilder planner = context.getBean(PlannerChatClientBuilder.class);

        assertThat(planner.builder()).isSameAs(local);
        context.close();
    }

    @Test
    void usesTheQualifiedHostedModelWhenTheProfileIsActive() {
        ChatClient.Builder local = ChatClient.builder(new FixedModel("local"));
        FixedModel hosted = new FixedModel("hosted");
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("hosted-planner");
        context.register(OrchestratorPlannerConfiguration.class);
        context.registerBean(ChatClient.Builder.class, () -> local);
        context.registerBean("plannerChatModel", ChatModel.class, () -> hosted);
        context.refresh();

        PlannerChatClientBuilder planner = context.getBean(PlannerChatClientBuilder.class);

        String answer = planner.builder().build().prompt().user("hi").call().content();
        assertThat(answer).isEqualTo("hosted");
        context.close();
    }
}
