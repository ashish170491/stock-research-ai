package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A preset described for people choosing one: its criteria for each kind of company, and who it skips. */
public record PresetView(String name, String description, String tieBreak, List<RuleSet> ruleSets,
                         List<IndustryGroup> notScreened) {

    public record RuleSet(String title, List<IndustryGroup> groups, List<Criterion> criteria) {
    }

    public record Criterion(String label, String description) {
    }

    public static PresetView from(ScreeningCriteria criteria) {
        List<RuleSet> sets = new ArrayList<>();
        List<IndustryGroup> nonFinancial = Arrays.stream(IndustryGroup.values())
                .filter(group -> group != IndustryGroup.UNKNOWN && !ScreeningCriteria.FINANCIAL.contains(group)
                        && !criteria.groupRules().containsKey(group))
                .toList();
        if (!criteria.nonFinancialRules().isEmpty()) {
            sets.add(new RuleSet("Non-financial companies", nonFinancial, criteria(criteria.nonFinancialRules())));
        }
        criteria.groupRules().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> sets.add(new RuleSet(entry.getKey().name(), List.of(entry.getKey()),
                        criteria(entry.getValue()))));
        List<IndustryGroup> skipped = Arrays.stream(IndustryGroup.values())
                .filter(group -> criteria.rulesFor(group).isEmpty()).toList();
        return new PresetView(criteria.name(), criteria.description(),
                criteria.tieBreak().metric().description() + (criteria.tieBreak().higherFirst() ? ", higher first"
                        : ", lower first"), sets, skipped);
    }

    private static List<Criterion> criteria(List<Rule> rules) {
        return rules.stream().map(rule -> new Criterion(rule.label(), rule.describe())).toList();
    }
}
