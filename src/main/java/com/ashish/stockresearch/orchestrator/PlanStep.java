package com.ashish.stockresearch.orchestrator;

import java.util.List;

/**
 * One step of a {@link ResearchPlan}, as the planning model wrote it - before {@link PlanValidator}
 * has checked it and before Java has run it.
 *
 * @param description the screen request in words (SCREEN), the question to search for (SEARCH_DOCS),
 *                     or a short note of intent (other types); not required by every type
 * @param symbols      the companies this step names; empty for SCREEN and CRITIQUE, and for DEEP_DIVE or
 *                     COMPARE when the step means "the shortlist from the SCREEN step before it"
 */
public record PlanStep(StepType type, String description, List<String> symbols) {

    public PlanStep {
        symbols = symbols == null ? List.of() : symbols.stream().filter(s -> s != null && !s.isBlank())
                .map(String::strip).toList();
    }
}
