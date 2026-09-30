package com.ashish.stockresearch.research.report;

import org.springframework.stereotype.Component;

/**
 * The checks every model-written chat answer goes through, in one place so each answer path applies all
 * of them: figures ({@link NumericClaimVerifier}), counts of a screen ({@link ScreeningCountVerifier}), then
 * claims ({@link UnsupportedClaimFilter}). A removed statement is never silent: the answer says how many
 * were removed and why.
 */
@Component
public class AnswerChecks {

    private final NumericClaimVerifier numericVerifier;
    private final ScreeningCountVerifier countVerifier;
    private final UnsupportedClaimFilter claimFilter;

    public AnswerChecks(NumericClaimVerifier numericVerifier, ScreeningCountVerifier countVerifier,
                        UnsupportedClaimFilter claimFilter) {
        this.numericVerifier = numericVerifier;
        this.countVerifier = countVerifier;
        this.claimFilter = claimFilter;
    }

    /**
     * @param text      the answer shown to the user once checked, with a note for each kind of removal
     * @param removed   how many statements were removed, of every kind
     */
    public record Checked(String text, int removed) {
    }

    /**
     * @param answer   the model's answer
     * @param evidence everything the answer may rest on: the tool results, or the rendered data it was given
     */
    public Checked check(String answer, String evidence) {
        NumericClaimVerifier.Result figures = numericVerifier.verify(answer, evidence);
        ScreeningCountVerifier.Result counts = countVerifier.verify(figures.text(), evidence);
        UnsupportedClaimFilter.Result claims = claimFilter.filter(counts.text(), evidence);
        StringBuilder text = new StringBuilder(claims.text() == null ? "" : claims.text());
        if (!figures.removedStatements().isEmpty()) {
            text.append("\n\n_Removed %d statement(s) containing figures that do not appear in the data the tools "
                    .formatted(figures.removedStatements().size())
                    + "returned (the model mistyped, recalculated, converted or invented them): "
                    + String.join(", ", figures.ungroundedFigures()) + "._");
        }
        if (!counts.removedStatements().isEmpty()) {
            text.append("\n\n_Removed %d statement(s) with counts the screen does not state (the model miscounted "
                    .formatted(counts.removedStatements().size())
                    + "the table): " + String.join(", ", counts.wrongCounts()) + "._");
        }
        if (!claims.removedSentences().isEmpty()) {
            text.append("\n\n_Removed %d statement(s) making claims the data does not support: %s_"
                    .formatted(claims.removedSentences().size(), String.join(" | ", claims.removedSentences())));
        }
        return new Checked(text.toString(), figures.removedStatements().size() + counts.removedStatements().size()
                + claims.removedSentences().size());
    }
}
