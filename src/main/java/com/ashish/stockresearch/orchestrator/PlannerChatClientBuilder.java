package com.ashish.stockresearch.orchestrator;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Wraps the {@link ChatClient.Builder} the orchestrator plans with (S8-8), as its own type rather
 * than a second, competing bean of type {@code ChatClient.Builder} - every other component in this
 * application takes that type unqualified, and a second bean of the identical type would make every
 * one of those injection points ambiguous. {@link OrchestratorPlannerConfiguration} is the only place
 * this type is produced.
 */
public record PlannerChatClientBuilder(ChatClient.Builder builder) {
}
