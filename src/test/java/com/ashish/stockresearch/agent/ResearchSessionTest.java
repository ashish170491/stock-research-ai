package com.ashish.stockresearch.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchSessionTest {

    @Test
    void keepsOnlyTheLastFewTurnsOfEvidence() {
        ResearchSession session = new ResearchSession();
        for (int turn = 1; turn <= ResearchSession.EVIDENCE_TURNS + 2; turn++) {
            session.addEvidence("turn " + turn);
        }

        assertThat(session.earlierEvidence()).doesNotContain("turn 1\n").doesNotContain("turn 2\n")
                .startsWith("turn 3").endsWith("turn " + (ResearchSession.EVIDENCE_TURNS + 2));
    }

    @Test
    void aTurnAboutNoCompanyKeepsTheEarlierOnes() {
        ResearchSession session = new ResearchSession();
        session.rememberCompanies(List.of("INFY"));
        session.rememberCompanies(List.of());

        assertThat(session.companies()).containsExactly("INFY");
    }
}
