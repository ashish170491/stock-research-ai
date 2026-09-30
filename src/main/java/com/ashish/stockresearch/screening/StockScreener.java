package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningResult.NotScreened;
import com.ashish.stockresearch.screening.ScreeningResult.RuleResult;
import com.ashish.stockresearch.screening.ScreeningResult.StockScreening;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The judge: decides, for every stock and every rule that applies to its industry group, PASS, FAIL or
 * INSUFFICIENT_DATA - in code, against explicit criteria, so the model only ever reports the decision.
 *
 * <p>It reads nothing but the snapshots it is given; it has no data provider and makes no calls.
 * Only a VALID, cross-checked value in the rule's own unit can PASS or FAIL. Anything else - UNAVAILABLE,
 * DATA_CONFLICT, INVALID, unchecked, or a unit mismatch - is INSUFFICIENT_DATA and never counts as met.
 *
 * <p>Ranking, which is not a judgement of the stocks: first those meeting every criterion, then by the
 * share of their criteria met (groups have different numbers of rules), then by fewer INSUFFICIENT_DATA
 * results, then by the tie-break metric (a stock without a usable tie-break value last), then by symbol.
 */
@Component
public class StockScreener {

    public ScreeningResult screen(ScreeningCriteria criteria, List<FundamentalsSnapshot> snapshots) {
        List<StockScreening> screened = new ArrayList<>();
        List<NotScreened> notScreened = new ArrayList<>();
        int filteredOut = 0;
        for (FundamentalsSnapshot snapshot : snapshots) {
            IndustryGroup group = snapshot.industryGroup();
            if (!criteria.includes(group)) {
                filteredOut++;
                continue;
            }
            Optional<List<Rule>> rules = criteria.rulesFor(group);
            if (rules.isEmpty()) {
                notScreened.add(new NotScreened(snapshot.symbol(), snapshot.companyName(), group, whyNot(group,
                        snapshot)));
                continue;
            }
            List<RuleResult> results = rules.get().stream()
                    .map(rule -> evaluate(rule, snapshot.metric(rule.metric())))
                    .toList();
            screened.add(new StockScreening(snapshot.symbol(), snapshot.companyName(), group, results,
                    snapshot.metric(criteria.tieBreak().metric())));
        }
        screened.sort(ranking(criteria.tieBreak()));
        return new ScreeningResult(criteria, List.copyOf(screened), List.copyOf(notScreened), filteredOut);
    }

    static RuleResult evaluate(Rule rule, SnapshotMetric metric) {
        if (metric.status() != DataStatus.VALID || metric.value() == null) {
            return new RuleResult(rule, RuleOutcome.INSUFFICIENT_DATA, metric,
                    "%s is %s: %s".formatted(rule.metric().shortLabel(), metric.status(), metric.statusReason()));
        }
        if (metric.uncheckedReason() != null) {
            return new RuleResult(rule, RuleOutcome.INSUFFICIENT_DATA, metric,
                    "%s was not cross-checked (%s)".formatted(rule.metric().shortLabel(), metric.uncheckedReason()));
        }
        if (metric.unit() != rule.metric().unit()) {
            return new RuleResult(rule, RuleOutcome.INSUFFICIENT_DATA, metric,
                    "%s is in %s but the rule is in %s; units are never mixed".formatted(rule.metric().shortLabel(),
                            metric.unit(), rule.metric().unit()));
        }
        RuleOutcome outcome = rule.operator().holds(metric.value(), rule.threshold()) ? RuleOutcome.PASS
                : RuleOutcome.FAIL;
        return new RuleResult(rule, outcome, metric, null);
    }

    private static String whyNot(IndustryGroup group, FundamentalsSnapshot snapshot) {
        if (group == IndustryGroup.UNKNOWN) {
            return "its industry group is UNKNOWN (%s), so the criteria that apply cannot be chosen"
                    .formatted(snapshot.classificationBasis());
        }
        return "the preset defines no criteria for %s companies".formatted(group);
    }

    private static Comparator<StockScreening> ranking(ScreeningCriteria.TieBreak tieBreak) {
        Comparator<StockScreening> tie = (a, b) -> {
            boolean aUsable = a.tieBreak().usable() && a.tieBreak().unit() == tieBreak.metric().unit();
            boolean bUsable = b.tieBreak().usable() && b.tieBreak().unit() == tieBreak.metric().unit();
            if (aUsable != bUsable) {
                return aUsable ? -1 : 1;
            }
            if (!aUsable) {
                return 0;
            }
            int order = a.tieBreak().value().compareTo(b.tieBreak().value());
            return tieBreak.higherFirst() ? -order : order;
        };
        return Comparator.comparing(StockScreening::meetsAll).reversed()
                // passes(a) / rules(a) against passes(b) / rules(b), without dividing
                .thenComparing((a, b) -> Long.compare(b.count(RuleOutcome.PASS) * a.results().size(),
                        a.count(RuleOutcome.PASS) * b.results().size()))
                .thenComparingLong(s -> s.count(RuleOutcome.INSUFFICIENT_DATA))
                .thenComparing(tie)
                .thenComparing(StockScreening::symbol);
    }
}
