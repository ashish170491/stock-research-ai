package com.ashish.stockresearch.orchestrator;

import java.util.List;

/**
 * An ordered research plan: the goal it was built for, and the steps the application will run for it
 * (S8-1). Structured output from the planning model; never run before {@link PlanValidator#validate}
 * has checked it.
 */
public record ResearchPlan(String goal, List<PlanStep> steps) {

    public ResearchPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
