package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;

import java.util.List;

/**
 * Every screened stock with a result for every rule that applies to it, ranked; and the stocks the
 * criteria could not be applied to, with why.
 *
 * @param filteredOut stocks left out by the criteria's industry-group filter
 */
public record ScreeningResult(
        ScreeningCriteria criteria,
        List<StockScreening> ranked,
        List<NotScreened> notScreened,
        int filteredOut
) {

    /** The result of one rule for one stock, with the value it was decided on. */
    public record RuleResult(Rule rule, RuleOutcome outcome, SnapshotMetric metric, String reason) {
    }

    public record StockScreening(
            String symbol,
            String companyName,
            IndustryGroup industryGroup,
            List<RuleResult> results,
            SnapshotMetric tieBreak
    ) {

        public long count(RuleOutcome outcome) {
            return results.stream().filter(result -> result.outcome() == outcome).count();
        }

        public boolean meetsAll() {
            return count(RuleOutcome.PASS) == results.size();
        }
    }

    public record NotScreened(String symbol, String companyName, IndustryGroup industryGroup, String reason) {
    }
}
