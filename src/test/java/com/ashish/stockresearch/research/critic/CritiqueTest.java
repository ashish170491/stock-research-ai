package com.ashish.stockresearch.research.critic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// S6-1
class CritiqueTest {

    @Test
    void hasTheFourFieldsTheEvaluatorOptimizerLoopNeeds() {
        Critique critique = new Critique(List.of("claim"), List.of("gap"), List.of("risk"), Critique.Verdict.REVISE);

        assertThat(critique.unsupportedClaims()).containsExactly("claim");
        assertThat(critique.missedDataGaps()).containsExactly("gap");
        assertThat(critique.missingRisks()).containsExactly("risk");
        assertThat(critique.verdict()).isEqualTo(Critique.Verdict.REVISE);
        assertThat(critique.needsRevision()).isTrue();
    }

    @Test
    void acceptIsTheSafeDefaultWithNothingFlagged() {
        Critique accept = Critique.accept();

        assertThat(accept.verdict()).isEqualTo(Critique.Verdict.ACCEPT);
        assertThat(accept.needsRevision()).isFalse();
        assertThat(accept.unsupportedClaims()).isEmpty();
        assertThat(accept.missedDataGaps()).isEmpty();
        assertThat(accept.missingRisks()).isEmpty();
    }

    @Test
    void nullListsAndVerdictDefaultSafely() {
        Critique critique = new Critique(null, null, null, null);

        assertThat(critique.unsupportedClaims()).isEmpty();
        assertThat(critique.missedDataGaps()).isEmpty();
        assertThat(critique.missingRisks()).isEmpty();
        assertThat(critique.verdict()).isEqualTo(Critique.Verdict.ACCEPT);
    }
}
