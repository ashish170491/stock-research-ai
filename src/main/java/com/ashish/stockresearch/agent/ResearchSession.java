package com.ashish.stockresearch.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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
}
