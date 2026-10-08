package com.ashish.stockresearch.orchestrator;

/** A {@link ResearchPlan} that cannot be run as written (S8-1): nothing from it is ever executed. */
public class InvalidPlanException extends RuntimeException {

    public InvalidPlanException(String message) {
        super(message);
    }
}
