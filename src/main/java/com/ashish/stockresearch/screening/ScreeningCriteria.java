package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A named set of screening rules, chosen per industry group because the same metric does not mean the
 * same thing for a bank as for a manufacturer.
 *
 * <ul>
 *   <li>{@code groupRules} - rules for a specific group (BANK, NBFC, INSURANCE, or any other).</li>
 *   <li>{@code nonFinancialRules} - rules for every other group except UNKNOWN. They never apply to a bank,
 *       NBFC, insurer or other financial company: a financial group with no rules of its own is not
 *       screened at all.</li>
 *   <li>A company whose group is UNKNOWN is never screened, since the right rules cannot be chosen.</li>
 * </ul>
 * Construction rejects a rule on a metric that is not a primary indicator for its group (for a bank:
 * debt-to-equity, operating margin, ROCE, cash-flow ratios), so no preset can evaluate one.
 *
 * @param industryGroups     groups to include; empty for all
 * @param notScreenedReasons why a group has no rules, where that is more than "the preset defines none" -
 *                           for criteria read from a request, "D/E is not a primary metric for BANK"
 */
public record ScreeningCriteria(
        String name,
        String description,
        List<Rule> nonFinancialRules,
        Map<IndustryGroup, List<Rule>> groupRules,
        TieBreak tieBreak,
        Set<IndustryGroup> industryGroups,
        Map<IndustryGroup, String> notScreenedReasons
) {

    /** Groups the non-financial rules never apply to. */
    public static final Set<IndustryGroup> FINANCIAL = EnumSet.of(IndustryGroup.BANK, IndustryGroup.NBFC,
            IndustryGroup.INSURANCE, IndustryGroup.FINANCIAL_OTHER);

    /** Orders stocks that met the same share of criteria. */
    public record TieBreak(ScreeningMetric metric, boolean higherFirst) {

        public TieBreak {
            if (metric == null) {
                throw new IllegalArgumentException("a tie-break needs a metric");
            }
        }
    }

    public ScreeningCriteria {
        if (name == null || name.isBlank() || tieBreak == null) {
            throw new IllegalArgumentException("screening criteria need a name and a tie-break");
        }
        nonFinancialRules = nonFinancialRules == null ? List.of() : List.copyOf(nonFinancialRules);
        groupRules = groupRules == null ? Map.of() : Map.copyOf(groupRules);
        industryGroups = industryGroups == null || industryGroups.isEmpty() ? Set.of() : Set.copyOf(industryGroups);
        notScreenedReasons = notScreenedReasons == null ? Map.of() : Map.copyOf(notScreenedReasons);
        if (groupRules.containsKey(IndustryGroup.UNKNOWN)) {
            throw new IllegalArgumentException("%s: no rules can apply to an UNKNOWN industry group".formatted(name));
        }
        for (IndustryGroup group : IndustryGroup.values()) {
            List<Rule> rules = rulesOf(group, nonFinancialRules, groupRules);
            for (Rule rule : rules) {
                if (group.isNonPrimary(rule.metric().sectorKey())) {
                    throw new IllegalArgumentException(("%s: %s is not a primary metric for %s, so it cannot be a "
                            + "screening rule for it").formatted(name, rule.metric(), group));
                }
            }
            if (!rules.isEmpty() && group.isNonPrimary(tieBreak.metric().sectorKey())) {
                throw new IllegalArgumentException("%s: the tie-break %s is not a primary metric for %s"
                        .formatted(name, tieBreak.metric(), group));
            }
        }
    }

    public ScreeningCriteria(String name, String description, List<Rule> nonFinancialRules,
                             Map<IndustryGroup, List<Rule>> groupRules, TieBreak tieBreak,
                             Set<IndustryGroup> industryGroups) {
        this(name, description, nonFinancialRules, groupRules, tieBreak, industryGroups, Map.of());
    }

    /** Why a group these criteria have no rules for is not screened. */
    public String whyNotScreened(IndustryGroup group) {
        return notScreenedReasons.getOrDefault(group,
                "the %s criteria define no rules for %s companies".formatted(name, group));
    }

    /** The rules for a group, or empty when the group is not screened by these criteria. */
    public Optional<List<Rule>> rulesFor(IndustryGroup group) {
        List<Rule> rules = rulesOf(group, nonFinancialRules, groupRules);
        return rules.isEmpty() ? Optional.empty() : Optional.of(rules);
    }

    public boolean includes(IndustryGroup group) {
        return industryGroups.isEmpty() || industryGroups.contains(group);
    }

    public ScreeningCriteria withIndustryGroups(Set<IndustryGroup> groups) {
        return new ScreeningCriteria(name, description, nonFinancialRules, groupRules, tieBreak, groups,
                notScreenedReasons);
    }

    private static List<Rule> rulesOf(IndustryGroup group, List<Rule> nonFinancial, Map<IndustryGroup, List<Rule>> byGroup) {
        if (group == IndustryGroup.UNKNOWN) {
            return List.of();
        }
        if (byGroup.containsKey(group)) {
            return byGroup.get(group);
        }
        return FINANCIAL.contains(group) ? List.of() : nonFinancial;
    }
}
