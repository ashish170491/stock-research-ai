package com.ashish.stockresearch.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A plan is checked before anything in it runs (S8-1): nothing here ever executes a step. */
class PlanValidatorTest {

    private static PlanStep step(StepType type, String description, String... symbols) {
        return new PlanStep(type, description, List.of(symbols));
    }

    private static ResearchPlan plan(PlanStep... steps) {
        return new ResearchPlan("find candidates", List.of(steps));
    }

    @Test
    void acceptsAPlanThatScreensThenDeepDivesThenWritesTheMemo() {
        ResearchPlan plan = plan(
                step(StepType.SCREEN, "pharma companies with ROE above 15%"),
                step(StepType.DEEP_DIVE, null),
                step(StepType.WRITE_MEMO, null));

        assertThatCode(plan);
    }

    @Test
    void acceptsAPlanWithExplicitSymbolsAndNoScreen() {
        ResearchPlan plan = plan(
                step(StepType.COMPARE, null, "TCS", "INFY"),
                step(StepType.CRITIQUE, null),
                step(StepType.WRITE_MEMO, null));

        assertThatCode(plan);
    }

    @Test
    void rejectsAnEmptyPlan() {
        assertThatThrownBy(() -> PlanValidator.validate(new ResearchPlan("goal", List.of())))
                .isInstanceOf(InvalidPlanException.class).hasMessageContaining("no steps");
    }

    @Test
    void rejectsAScreenStepWithNoDescription() {
        ResearchPlan plan = plan(step(StepType.SCREEN, " "), step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("SCREEN").hasMessageContaining("description");
    }

    @Test
    void rejectsADeepDiveWithNoSymbolsAndNoPrecedingScreen() {
        ResearchPlan plan = plan(step(StepType.DEEP_DIVE, null), step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("DEEP_DIVE").hasMessageContaining("SCREEN");
    }

    @Test
    void rejectsACompareWithOnlyOneCompany() {
        ResearchPlan plan = plan(step(StepType.COMPARE, null, "TCS"), step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("COMPARE").hasMessageContaining("2 to 5");
    }

    @Test
    void rejectsACompareWithMoreThanFiveCompanies() {
        ResearchPlan plan = plan(step(StepType.COMPARE, null, "A", "B", "C", "D", "E", "F"),
                step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("2 to 5");
    }

    @Test
    void rejectsSearchDocsWithNoSymbol() {
        ResearchPlan plan = plan(step(StepType.SEARCH_DOCS, "What was the Gross NPA ratio?"),
                step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("SEARCH_DOCS").hasMessageContaining("exactly one company");
    }

    @Test
    void rejectsSearchDocsWithMoreThanOneSymbol() {
        ResearchPlan plan = plan(step(StepType.SEARCH_DOCS, "What was the Gross NPA ratio?", "HDFCBANK", "ICICIBANK"),
                step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("exactly one company");
    }

    @Test
    void rejectsSearchDocsWithNoQuestion() {
        ResearchPlan plan = plan(step(StepType.SEARCH_DOCS, " ", "HDFCBANK"), step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("SEARCH_DOCS").hasMessageContaining("question");
    }

    @Test
    void rejectsACritiqueWithNothingToCritique() {
        ResearchPlan plan = plan(step(StepType.SCREEN, "banks"), step(StepType.CRITIQUE, null),
                step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("CRITIQUE").hasMessageContaining("nothing to critique");
    }

    @Test
    void acceptsACritiqueAfterSearchDocs() {
        ResearchPlan plan = plan(step(StepType.SEARCH_DOCS, "Gross NPA ratio?", "HDFCBANK"),
                step(StepType.CRITIQUE, null), step(StepType.WRITE_MEMO, null));

        assertThatCode(plan);
    }

    @Test
    void rejectsAPlanWithNoWriteMemo() {
        ResearchPlan plan = plan(step(StepType.SCREEN, "banks"));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("WRITE_MEMO");
    }

    @Test
    void rejectsAWriteMemoThatIsNotLast() {
        ResearchPlan plan = plan(step(StepType.WRITE_MEMO, null), step(StepType.SCREEN, "banks"));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("last step");
    }

    @Test
    void rejectsAPlanWithTwoWriteMemoSteps() {
        ResearchPlan plan = plan(step(StepType.SCREEN, "banks"), step(StepType.WRITE_MEMO, null));
        // Append a second WRITE_MEMO by building a fresh plan rather than mutating the fixed list.
        ResearchPlan twoMemos = new ResearchPlan(plan.goal(), List.of(plan.steps().get(0), plan.steps().get(1),
                step(StepType.WRITE_MEMO, null)));

        assertThatThrownBy(() -> PlanValidator.validate(twoMemos)).isInstanceOf(InvalidPlanException.class);
    }

    @Test
    void rejectsAPlanWithANullStepType() {
        ResearchPlan plan = plan(new PlanStep(null, "banks", List.of()), step(StepType.WRITE_MEMO, null));

        assertThatThrownBy(() -> PlanValidator.validate(plan)).isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining("no recognised type");
    }

    private static void assertThatCode(ResearchPlan plan) {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> PlanValidator.validate(plan))).isNull();
    }
}
