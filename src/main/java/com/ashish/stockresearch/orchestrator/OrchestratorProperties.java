package com.ashish.stockresearch.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Budgets the orchestrator enforces while executing a plan (S8-3): exceeding any one stops execution
 * before the next step and returns a partial memo from whatever was gathered, stating which limit
 * stopped it.
 *
 * @param maxSteps      the most plan steps run in one request, across a pause and its "continue"
 * @param maxLlmCalls   the most model calls made in one request, across every step and the plan itself
 * @param maxWallClock  the most processing time spent in one request - the time a human takes between a
 *                      pause and "continue" is never counted against it
 */
@ConfigurationProperties(prefix = "app.orchestrator")
public record OrchestratorProperties(int maxSteps, int maxLlmCalls, Duration maxWallClock) {

    public OrchestratorProperties {
        if (maxSteps <= 0) {
            maxSteps = 12;
        }
        if (maxLlmCalls <= 0) {
            maxLlmCalls = 20;
        }
        if (maxWallClock == null || maxWallClock.isZero() || maxWallClock.isNegative()) {
            maxWallClock = Duration.ofMinutes(10);
        }
    }
}
