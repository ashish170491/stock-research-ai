package com.ashish.stockresearch.verdict;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.RuleOutcome;
import com.ashish.stockresearch.screening.ScreeningResult.RuleResult;

import java.util.List;

/** A rating with everything it was decided on, so it can be shown and checked. */
public record Verdict(Rating rating, String reason, FundamentalsSnapshot snapshot, IndustryGroup group,
                      Pillar quality, Pillar price, VerdictProperties rule) {

    /** One set of screening rules and how the company did on them. */
    public record Pillar(String name, List<RuleResult> results) {

        public int total() {
            return results.size();
        }

        public int met() {
            return count(RuleOutcome.PASS);
        }

        /** Rules decided on a VALID, cross-checked figure, whether met or not. */
        public int checkable() {
            return count(RuleOutcome.PASS) + count(RuleOutcome.FAIL);
        }

        /** Percent of the rules met; a rule that could not be checked is not met. */
        public int metPercent() {
            return total() == 0 ? 0 : met() * 100 / total();
        }

        public int coverPercent() {
            return total() == 0 ? 0 : checkable() * 100 / total();
        }

        private int count(RuleOutcome outcome) {
            return (int) results.stream().filter(result -> result.outcome() == outcome).count();
        }
    }
}
