package com.ashish.stockresearch.orchestrator;

import com.ashish.stockresearch.trace.ChainTracer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The three budgets the orchestrator enforces (S8-3), and that a resumed run keeps counting from
 * what a checkpoint had already consumed rather than resetting. */
class OrchestratorBudgetTest {

    private static final OrchestratorProperties PROPERTIES =
            new OrchestratorProperties(3, 5, Duration.ofMinutes(1));

    private ChainTracer.Chain chainWithCalls(int calls) {
        ChainTracer.Chain chain = mock(ChainTracer.Chain.class);
        when(chain.modelCalls()).thenReturn(calls);
        return chain;
    }

    @Test
    void allowsStepsUntilTheStepLimit() {
        OrchestratorBudget budget = new OrchestratorBudget(PROPERTIES, chainWithCalls(0), 0, 0, Duration.ZERO);

        assertThat(budget.exceeded()).isEmpty();
        budget.stepCompleted();
        budget.stepCompleted();
        assertThat(budget.exceeded()).isEmpty();
        budget.stepCompleted();

        Optional<String> exceeded = budget.exceeded();
        assertThat(exceeded).isPresent();
        assertThat(exceeded.get()).contains("step limit").contains("3 steps");
    }

    @Test
    void allowsCallsUntilTheModelCallLimit() {
        OrchestratorBudget budget = new OrchestratorBudget(PROPERTIES, chainWithCalls(5), 0, 0, Duration.ZERO);

        Optional<String> exceeded = budget.exceeded();

        assertThat(exceeded).isPresent();
        assertThat(exceeded.get()).contains("model-call limit").contains("5 calls");
    }

    @Test
    void stopsAtTheWallClockLimitEvenWithStepsAndCallsToSpare() {
        OrchestratorBudget budget = new OrchestratorBudget(PROPERTIES, chainWithCalls(0), 0, 0,
                Duration.ofMinutes(2));

        Optional<String> exceeded = budget.exceeded();

        assertThat(exceeded).isPresent();
        assertThat(exceeded.get()).contains("wall-clock limit");
    }

    @Test
    void aResumedBudgetKeepsCountingFromWhatWasConsumedBeforeThePause() {
        // 2 steps and 4 calls already consumed before the pause; one more of either reaches the limit.
        OrchestratorBudget budget = new OrchestratorBudget(PROPERTIES, chainWithCalls(1), 2, 4, Duration.ZERO);

        assertThat(budget.llmCalls()).isEqualTo(5);
        assertThat(budget.exceeded()).isPresent().get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("model-call limit");
    }
}
