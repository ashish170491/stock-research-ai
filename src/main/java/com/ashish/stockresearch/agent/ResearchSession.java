package com.ashish.stockresearch.agent;

import com.ashish.stockresearch.orchestrator.OrchestratorCheckpoint;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * What one conversation has established so far, beyond its chat history: the companies it is
 * about, so that "what about its debt?" can be routed, and the evidence earlier turns were shown,
 * so that a figure quoted from an earlier answer is still checked against real tool output.
 *
 * Earlier evidence carries its own dates (a quote says when it was taken, a financial figure names
 * its period), so a figure repeated from it is never presented as newer than it is.
 */
public final class ResearchSession {

    /** How many earlier turns of evidence are kept: enough for a follow-up, without growing forever. */
    static final int EVIDENCE_TURNS = 5;

    private List<String> companies = List.of();
    private final Deque<String> evidence = new ArrayDeque<>();
    private OrchestratorCheckpoint checkpoint;
    private List<String> focus = List.of();
    private String awaitingChoiceAbout;
    private String awaitingCompanyFor;

    /** The companies the most recent company-specific turn was about, in order; empty at the start. */
    public synchronized List<String> companies() {
        return companies;
    }

    /** Remembers the companies a turn was about; a turn about no company leaves the earlier ones. */
    public synchronized void rememberCompanies(List<String> discussed) {
        if (discussed != null && !discussed.isEmpty()) {
            companies = List.copyOf(discussed);
        }
    }

    /**
     * The company the last answer asked the user to choose topics about; the next message may be that choice,
     * typed. Taking it clears it, so it applies to the very next message only.
     */
    public synchronized Optional<String> takeAwaitingChoice() {
        Optional<String> company = Optional.ofNullable(awaitingChoiceAbout);
        awaitingChoiceAbout = null;
        return company;
    }

    public synchronized void awaitChoiceAbout(String company) {
        awaitingChoiceAbout = company;
    }

    /** The question asked before the user was asked which company it is about; taking it clears it. */
    public synchronized Optional<String> takeAwaitingCompany() {
        Optional<String> question = Optional.ofNullable(awaitingCompanyFor);
        awaitingCompanyFor = null;
        return question;
    }

    public synchronized void awaitCompanyFor(String question) {
        awaitingCompanyFor = question;
    }

    /** The topics the user last chose to hear about (their ids), ticked first the next time they are asked. */
    public synchronized List<String> focus() {
        return focus;
    }

    public synchronized void rememberFocus(List<String> topics) {
        if (topics != null && !topics.isEmpty()) {
            focus = List.copyOf(topics);
        }
    }

    /** Keeps what a turn's tools returned (or what a Java-rendered answer showed) for later turns. */
    public synchronized void addEvidence(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        evidence.addLast(text);
        while (evidence.size() > EVIDENCE_TURNS) {
            evidence.removeFirst();
        }
    }

    /** The evidence of the earlier turns still kept, oldest first. */
    public synchronized String earlierEvidence() {
        return String.join("\n", evidence);
    }

    /** The paused research plan (roadmap Step 8), if a SCREEN step is waiting for "continue". */
    public synchronized Optional<OrchestratorCheckpoint> checkpoint() {
        return Optional.ofNullable(checkpoint);
    }

    public synchronized void pause(OrchestratorCheckpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    public synchronized void clearCheckpoint() {
        this.checkpoint = null;
    }
}
