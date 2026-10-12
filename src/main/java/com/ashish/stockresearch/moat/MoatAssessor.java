package com.ashish.stockresearch.moat;

import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.Rule;
import com.ashish.stockresearch.screening.ScreeningCriteria;
import com.ashish.stockresearch.screening.ScreeningPresets;
import com.ashish.stockresearch.screening.StockScreener;
import com.ashish.stockresearch.verdict.VerdictRenderer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Looks for the signs of a lasting edge in a company's snapshot: the moat preset's rules for its industry group,
 * each decided exactly as a screen decides it ({@link StockScreener}) - only a VALID, cross-checked figure can meet
 * or miss one, and a sign that cannot be checked is never counted as found. A model never sees, chooses or words
 * the signs.
 */
@Component
public class MoatAssessor {

    private final ScreeningCriteria preset;
    private final MoatProperties settings;

    public MoatAssessor(ScreeningPresets presets, MoatProperties settings) {
        this.settings = settings;
        this.preset = presets.find(settings.preset()).orElseThrow(() -> new IllegalStateException(
                "app.moat.preset '%s' is not a screening preset".formatted(settings.preset())));
    }

    public MoatSigns assess(FundamentalsSnapshot snapshot) {
        List<Rule> rules = preset.rulesFor(snapshot.industryGroup()).orElse(List.of());
        Set<String> notes = new LinkedHashSet<>();
        List<StockScreener.Decision> signs = rules.stream().map(rule -> {
            StockScreener.Decision decision = StockScreener.evaluate(rule, snapshot, settings.shorterSpanYears());
            if (decision.onShorterSpan()) {
                notes.add("%s was checked over %d years because the %d-year figure is not available: %s.".formatted(
                        capitalised(rule.metric().series().orElseThrow().description()), settings.shorterSpanYears(),
                        rule.metric().years(), VerdictRenderer.plainReason(snapshot.metric(rule.metric()))));
            }
            return decision;
        }).toList();
        return new MoatSigns(snapshot, snapshot.industryGroup(), signs, List.copyOf(notes), settings);
    }

    /** Why there are no signs for a group the preset does not cover. */
    public String whyNoSigns(MoatSigns signs) {
        return preset.whyNotScreened(signs.group());
    }

    private static String capitalised(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
