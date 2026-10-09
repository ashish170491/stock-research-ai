package com.ashish.stockresearch.verdict;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.Rule;
import com.ashish.stockresearch.screening.ScreeningCriteria;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.ScreeningPresets;
import com.ashish.stockresearch.screening.StockScreener;
import com.ashish.stockresearch.verdict.Verdict.Pillar;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The only code that rates a company, in Java and by fixed rules: how many of the quality preset's rules it
 * meets, and how many of the price rules the price preset adds. Each rule is decided exactly as a screen
 * decides it ({@link StockScreener#evaluate}) - only a VALID, cross-checked figure can meet or miss a rule,
 * and a rule that cannot be checked is never counted as met. When too few rules can be checked, there is
 * no rating. A model never sees, chooses or words the rating.
 */
@Component
public class VerdictEngine {

    private final ScreeningCriteria quality;
    private final ScreeningCriteria price;
    private final VerdictProperties rule;

    public VerdictEngine(ScreeningPresets presets, VerdictProperties rule) {
        this.rule = rule;
        this.quality = presets.find(rule.qualityPreset()).orElseThrow(() -> new IllegalStateException(
                "app.verdict.quality-preset '%s' is not a screening preset".formatted(rule.qualityPreset())));
        this.price = presets.find(rule.pricePreset()).orElseThrow(() -> new IllegalStateException(
                "app.verdict.price-preset '%s' is not a screening preset".formatted(rule.pricePreset())));
    }

    public Verdict assess(FundamentalsSnapshot snapshot) {
        IndustryGroup group = snapshot.industryGroup();
        List<Rule> qualityRules = quality.rulesFor(group).orElse(List.of());
        Set<ScreeningMetric> qualityMetrics = qualityRules.stream().map(Rule::metric).collect(Collectors.toSet());
        List<Rule> priceRules = price.rulesFor(group).orElse(List.of()).stream()
                .filter(candidate -> !qualityMetrics.contains(candidate.metric())).toList();
        Pillar qualityPillar = pillar("Quality of the business", qualityRules, snapshot);
        Pillar pricePillar = pillar("Price you pay", priceRules, snapshot);

        if (qualityRules.isEmpty() || priceRules.isEmpty()) {
            return new Verdict(Rating.NO_RATING, "the rules do not cover this kind of company (%s)"
                    .formatted(group == IndustryGroup.UNKNOWN ? "its industry could not be identified"
                            : group.name().toLowerCase().replace('_', ' ')), snapshot, group, qualityPillar,
                    pricePillar, rule);
        }
        if (qualityPillar.coverPercent() < rule.minimumCover() || pricePillar.coverPercent() < rule.minimumCover()) {
            return new Verdict(Rating.NO_RATING, ("too few of the checks could be run on reliable data (%d of %d "
                    + "quality, %d of %d price)").formatted(qualityPillar.checkable(), qualityPillar.total(),
                    pricePillar.checkable(), pricePillar.total()), snapshot, group, qualityPillar, pricePillar, rule);
        }
        int q = qualityPillar.metPercent();
        Rating rating = q < rule.sellBelowQuality() ? Rating.SELL
                : q >= rule.buyQuality() && pricePillar.metPercent() >= rule.buyPrice() ? Rating.BUY : Rating.HOLD;
        return new Verdict(rating, null, snapshot, group, qualityPillar, pricePillar, rule);
    }

    private static Pillar pillar(String name, List<Rule> rules, FundamentalsSnapshot snapshot) {
        return new Pillar(name, rules.stream()
                .map(rule -> StockScreener.evaluate(rule, snapshot.metric(rule.metric()))).toList());
    }
}
