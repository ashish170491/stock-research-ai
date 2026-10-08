package com.ashish.stockresearch.orchestrator;

/**
 * Checks a {@link ResearchPlan} before anything in it runs (S8-1): every step is a recognised
 * {@link StepType} with the fields its type needs, DEEP_DIVE/COMPARE/SEARCH_DOCS name their companies
 * or follow a SCREEN step they can inherit a shortlist from, CRITIQUE has something to critique, and
 * the plan ends with exactly one WRITE_MEMO. An unknown step type never reaches here: it fails
 * Jackson's enum deserialization inside {@code .entity(ResearchPlan.class)} first.
 */
public final class PlanValidator {

    private static final int MIN_COMPARE = 2;
    private static final int MAX_COMPARE = 5;

    private PlanValidator() {
    }

    public static void validate(ResearchPlan plan) {
        if (plan == null || plan.steps().isEmpty()) {
            throw new InvalidPlanException("The plan has no steps.");
        }
        boolean sawScreen = false;
        boolean sawCritiquable = false;
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            int number = i + 1;
            if (step.type() == null) {
                throw new InvalidPlanException("Step %d has no recognised type.".formatted(number));
            }
            switch (step.type()) {
                case SCREEN -> {
                    requireDescription(step, number, "SCREEN", "a description of what to screen for");
                    sawScreen = true;
                }
                case DEEP_DIVE -> {
                    requireSymbolsOrShortlist(step, number, "DEEP_DIVE", sawScreen);
                    sawCritiquable = true;
                }
                case COMPARE -> {
                    requireSymbolsOrShortlist(step, number, "COMPARE", sawScreen);
                    int named = step.symbols().size();
                    if (named > 0 && (named < MIN_COMPARE || named > MAX_COMPARE)) {
                        throw new InvalidPlanException("Step %d (COMPARE) names %d compan%s; it needs %d to %d."
                                .formatted(number, named, named == 1 ? "y" : "ies", MIN_COMPARE, MAX_COMPARE));
                    }
                    sawCritiquable = true;
                }
                case SEARCH_DOCS -> {
                    if (step.symbols().size() != 1) {
                        throw new InvalidPlanException(
                                "Step %d (SEARCH_DOCS) must name exactly one company.".formatted(number));
                    }
                    requireDescription(step, number, "SEARCH_DOCS", "a question to search for");
                    sawCritiquable = true;
                }
                case CRITIQUE -> {
                    if (!sawCritiquable) {
                        throw new InvalidPlanException(("Step %d (CRITIQUE) has nothing to critique yet: it must "
                                + "follow a DEEP_DIVE, COMPARE or SEARCH_DOCS step.").formatted(number));
                    }
                }
                case WRITE_MEMO -> {
                    if (number != plan.steps().size()) {
                        throw new InvalidPlanException(
                                "WRITE_MEMO (step %d) must be the plan's last step.".formatted(number));
                    }
                }
            }
        }
        long memoCount = plan.steps().stream().filter(step -> step.type() == StepType.WRITE_MEMO).count();
        if (memoCount != 1) {
            throw new InvalidPlanException(
                    "The plan must have exactly one WRITE_MEMO step, as its last step; found " + memoCount + ".");
        }
    }

    private static void requireDescription(PlanStep step, int number, String type, String needs) {
        if (step.description() == null || step.description().isBlank()) {
            throw new InvalidPlanException("Step %d (%s) needs %s.".formatted(number, type, needs));
        }
    }

    private static void requireSymbolsOrShortlist(PlanStep step, int number, String type, boolean sawScreen) {
        if (step.symbols().isEmpty() && !sawScreen) {
            throw new InvalidPlanException(("Step %d (%s) names no companies, and no SCREEN step precedes it to "
                    + "supply a shortlist.").formatted(number, type));
        }
    }
}
