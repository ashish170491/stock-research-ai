package com.ashish.stockresearch.orchestrator;

/**
 * The fixed set of steps a {@link ResearchPlan} may use. The model picks and orders these and fills
 * in their fields; Java validates and runs them (S8-1, S8-2). No other step type is ever accepted.
 */
public enum StepType {

    /** Screen stocks against criteria described in words - {@link PlanStep#description()}. */
    SCREEN,

    /** A full verified research report on each of {@link PlanStep#symbols()}. */
    DEEP_DIVE,

    /** A single aligned comparison of 2 to 5 of {@link PlanStep#symbols()}. */
    COMPARE,

    /** Searches one company's ingested documents for {@link PlanStep#description()}. */
    SEARCH_DOCS,

    /** Reviews the most recent DEEP_DIVE, COMPARE or SEARCH_DOCS step's output against the evidence so far. */
    CRITIQUE,

    /** Writes the final memo from everything gathered. Always the plan's last step, exactly once. */
    WRITE_MEMO
}
