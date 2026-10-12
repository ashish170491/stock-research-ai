package com.ashish.stockresearch.agent;

import com.ashish.stockresearch.orchestrator.OrchestratorCheckpoint;

import java.util.List;

/**
 * Everything a {@link ResearchSession} holds, as one value: what is stored between turns and restarts
 * ({@link SessionStore}).
 *
 * @param companies           the companies the most recent company-specific turn was about
 * @param evidence            what earlier turns' tools returned, oldest first, at most {@link ResearchSession#EVIDENCE_TURNS}
 * @param checkpoint          a research plan paused for "continue"; null when none
 * @param focus               the topic ids the user last chose
 * @param awaitingChoiceAbout the company the last answer asked the user to choose topics about; null when none
 * @param awaitingCompanyFor  the question asked before the user was asked which company it is about; null when none
 */
public record SessionState(List<String> companies, List<String> evidence, OrchestratorCheckpoint checkpoint,
                           List<String> focus, String awaitingChoiceAbout, String awaitingCompanyFor) {

    public SessionState {
        companies = companies == null ? List.of() : List.copyOf(companies);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        focus = focus == null ? List.of() : List.copyOf(focus);
    }

    public static SessionState empty() {
        return new SessionState(List.of(), List.of(), null, List.of(), null, null);
    }
}
