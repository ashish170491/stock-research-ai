package com.ashish.stockresearch.verdict;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code app.verdict}: the rule that turns screening results into a rating. Percentages are of the rules in
 * a pillar. The pillars are two screening presets: the quality preset's rules, and the price rules the
 * price preset adds to them.
 *
 * @param buyQuality      quality share at or above which, with {@code buyPrice}, the rating is BUY
 * @param buyPrice        price share at or above which, with {@code buyQuality}, the rating is BUY
 * @param sellBelowQuality quality share below which the rating is SELL
 * @param minimumCover    share of a pillar's rules that must be checkable on reliable data to rate at all
 */
@ConfigurationProperties("app.verdict")
public record VerdictProperties(String qualityPreset, String pricePreset, int buyQuality, int buyPrice,
                                int sellBelowQuality, int minimumCover) {

    @ConstructorBinding
    public VerdictProperties {
        qualityPreset = blank(qualityPreset) ? "quality-compounder" : qualityPreset;
        pricePreset = blank(pricePreset) ? "reasonable-value" : pricePreset;
        buyQuality = buyQuality == 0 ? 80 : buyQuality;
        buyPrice = buyPrice == 0 ? 60 : buyPrice;
        sellBelowQuality = sellBelowQuality == 0 ? 50 : sellBelowQuality;
        minimumCover = minimumCover == 0 ? 70 : minimumCover;
    }

    public static VerdictProperties defaults() {
        return new VerdictProperties(null, null, 0, 0, 0, 0);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
