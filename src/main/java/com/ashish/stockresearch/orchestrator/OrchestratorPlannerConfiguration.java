package com.ashish.stockresearch.orchestrator;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Where the orchestrator's planning client comes from (S8-8). Tools, data and verification always run
 * on the local Ollama {@link ChatClient.Builder} every other component uses - only planning may run
 * elsewhere, and only behind its own profile.
 */
@Configuration
public class OrchestratorPlannerConfiguration {

    /** Default: plans with the same local model every other client uses. */
    @Bean
    @Profile("!hosted-planner")
    public PlannerChatClientBuilder localPlannerChatClientBuilder(ChatClient.Builder chatClientBuilder) {
        return new PlannerChatClientBuilder(chatClientBuilder);
    }

    /**
     * Opt-in: planning runs on a hosted model instead. Activating the {@code hosted-planner} profile
     * requires adding the chosen provider's Spring AI starter and its own configuration, and registering
     * a {@link ChatModel} bean qualified {@code plannerChatModel} with its credentials - this method only
     * wires that model into the orchestrator once it exists; it adds no provider dependency itself.
     */
    @Bean
    @Profile("hosted-planner")
    public PlannerChatClientBuilder hostedPlannerChatClientBuilder(
            @Qualifier("plannerChatModel") ChatModel plannerChatModel) {
        return new PlannerChatClientBuilder(ChatClient.builder(plannerChatModel));
    }
}
