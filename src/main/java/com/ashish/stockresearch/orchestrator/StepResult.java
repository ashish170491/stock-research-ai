package com.ashish.stockresearch.orchestrator;

import java.util.List;

/**
 * What one completed plan step produced: its rendered output, kept as evidence for later steps
 * (CRITIQUE, WRITE_MEMO), and - for a SCREEN step only - the shortlist later steps inherit.
 */
public record StepResult(StepType type, String description, String output, List<String> shortlist) {

    public StepResult {
        shortlist = shortlist == null ? List.of() : List.copyOf(shortlist);
    }
}
