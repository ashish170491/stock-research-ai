package com.ashish.stockresearch.research.critic;

import java.util.List;

/**
 * A second model's structured review of a draft interpretation against its own evidence pack: what it
 * found unsupported, what data gaps or risks it thinks the draft should have mentioned but did not, and
 * whether the draft needs revising. The critic never writes or revises the report itself - it only ever
 * returns this judgement.
 *
 * @param unsupportedClaims sentences in the draft the evidence pack does not support
 * @param missedDataGaps    data gaps or DATA_CONFLICTs in the evidence pack the draft should have
 *                          mentioned but did not
 * @param missingRisks      risks implied by a data gap, conflict or withheld topic the draft omits
 * @param verdict           ACCEPT or REVISE
 */
public record Critique(List<String> unsupportedClaims, List<String> missedDataGaps, List<String> missingRisks,
                       Verdict verdict) {

    public enum Verdict {
        ACCEPT, REVISE
    }

    public Critique {
        unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
        missedDataGaps = missedDataGaps == null ? List.of() : List.copyOf(missedDataGaps);
        missingRisks = missingRisks == null ? List.of() : List.copyOf(missingRisks);
        if (verdict == null) {
            verdict = Verdict.ACCEPT;
        }
    }

    /** What an unreachable or unparseable critic is treated as: never blocking the report on its own failure. */
    public static Critique accept() {
        return new Critique(List.of(), List.of(), List.of(), Verdict.ACCEPT);
    }

    public boolean needsRevision() {
        return verdict == Verdict.REVISE;
    }
}
