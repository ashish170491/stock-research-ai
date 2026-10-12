package com.ashish.stockresearch.moat;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.RuleOutcome;
import com.ashish.stockresearch.screening.StockScreener;

import java.util.List;

/**
 * The signs of a lasting edge looked for in one company's own figures, each decided by a screening rule
 * ({@link StockScreener}) - what a durable advantage usually leaves in the numbers, never a finding that the
 * company has one.
 *
 * @param signs empty when the preset defines no signs for the company's industry group
 * @param notes how the rules were applied to this company, e.g. a sign checked over a shorter span
 */
public record MoatSigns(FundamentalsSnapshot snapshot, IndustryGroup group, List<StockScreener.Decision> signs,
                        List<String> notes, MoatProperties settings) {

    public MoatSigns {
        signs = List.copyOf(signs);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public int total() {
        return signs.size();
    }

    /** Signs whose rule was met. */
    public int found() {
        return count(RuleOutcome.PASS);
    }

    /** Signs decided on a VALID, checked figure, whether met or not. */
    public int checkable() {
        return found() + count(RuleOutcome.FAIL);
    }

    public List<StockScreener.Decision> withOutcome(RuleOutcome outcome) {
        return signs.stream().filter(sign -> sign.result().outcome() == outcome).toList();
    }

    private int count(RuleOutcome outcome) {
        return (int) signs.stream().filter(sign -> sign.result().outcome() == outcome).count();
    }
}
