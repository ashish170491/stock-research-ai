package com.ashish.stockresearch.orchestrator;

import java.time.Duration;
import java.util.List;

/**
 * A paused plan, kept on the {@link com.ashish.stockresearch.agent.ResearchSession} after a SCREEN
 * step (S8-4) until the conversation says "continue". The budget fields let a resumed run keep
 * counting from where the pause found it, rather than resetting - the time a human takes to reply is
 * never counted as the plan's own processing time.
 */
public record OrchestratorCheckpoint(
        ResearchPlan plan,
        int nextStepIndex,
        List<String> shortlist,
        List<StepResult> completedResults,
        int stepsConsumed,
        int llmCallsConsumed,
        Duration elapsedBeforePause
) {

    public OrchestratorCheckpoint {
        shortlist = shortlist == null ? List.of() : List.copyOf(shortlist);
        completedResults = completedResults == null ? List.of() : List.copyOf(completedResults);
        elapsedBeforePause = elapsedBeforePause == null ? Duration.ZERO : elapsedBeforePause;
    }
}
