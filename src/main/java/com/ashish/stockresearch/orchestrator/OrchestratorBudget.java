package com.ashish.stockresearch.orchestrator;

import com.ashish.stockresearch.trace.ChainTracer;

import java.time.Duration;
import java.util.Optional;

/**
 * Tracks one request's consumption of the three budgets (S8-3) against {@link OrchestratorProperties},
 * starting from whatever a resumed checkpoint had already consumed before its pause. Model calls are
 * read live from the {@link ChainTracer.Chain} every step shares, so a step that makes several calls
 * (a screen's extraction and summary, a deep dive's several reports) is counted exactly once each.
 */
final class OrchestratorBudget {

    private final OrchestratorProperties properties;
    private final ChainTracer.Chain chain;
    private final long startNanos = System.nanoTime();
    private final int llmCallsBefore;
    private final Duration elapsedBefore;
    private int stepsConsumed;

    OrchestratorBudget(OrchestratorProperties properties, ChainTracer.Chain chain, int stepsBefore,
            int llmCallsBefore, Duration elapsedBefore) {
        this.properties = properties;
        this.chain = chain;
        this.stepsConsumed = stepsBefore;
        this.llmCallsBefore = llmCallsBefore;
        this.elapsedBefore = elapsedBefore == null ? Duration.ZERO : elapsedBefore;
    }

    /** Empty when one more step fits every budget; otherwise which limit stopped it, in words. */
    Optional<String> exceeded() {
        if (stepsConsumed >= properties.maxSteps()) {
            return Optional.of("the step limit (%d steps)".formatted(properties.maxSteps()));
        }
        if (llmCalls() >= properties.maxLlmCalls()) {
            return Optional.of("the model-call limit (%d calls)".formatted(properties.maxLlmCalls()));
        }
        if (elapsed().compareTo(properties.maxWallClock()) >= 0) {
            return Optional.of("the wall-clock limit (%s)".formatted(properties.maxWallClock()));
        }
        return Optional.empty();
    }

    void stepCompleted() {
        stepsConsumed++;
    }

    int stepsConsumed() {
        return stepsConsumed;
    }

    int llmCalls() {
        return llmCallsBefore + chain.modelCalls();
    }

    Duration elapsed() {
        return elapsedBefore.plus(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
