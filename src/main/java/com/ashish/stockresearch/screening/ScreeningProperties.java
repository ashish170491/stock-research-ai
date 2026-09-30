package com.ashish.stockresearch.screening;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code app.screening}: the snapshot schedule, the screening presets, and how words in a screening
 * request are read.
 *
 * @param presets        by name; each preset's {@code rules} are keyed by {@code default} (non-financial
 *                       companies) or an industry group name (BANK, NBFC, INSURANCE, ...)
 * @param vagueTerms     words with no number ("low debt") and the preset threshold each is read as
 * @param judgementTerms words that judge a stock ("cheap", "undervalued"); never read as a threshold
 */
@ConfigurationProperties("app.screening")
public record ScreeningProperties(Snapshot snapshot, Map<String, Preset> presets, List<VagueTerm> vagueTerms,
                                  List<String> judgementTerms) {

    @ConstructorBinding
    public ScreeningProperties {
        snapshot = snapshot == null ? new Snapshot(null, null) : snapshot;
        // in the order configured, which is the order they are listed in
        presets = presets == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(presets));
        vagueTerms = vagueTerms == null ? List.of() : List.copyOf(vagueTerms);
        judgementTerms = judgementTerms == null ? List.of() : List.copyOf(judgementTerms);
    }

    public ScreeningProperties(Snapshot snapshot, Map<String, Preset> presets) {
        this(snapshot, presets, List.of(), List.of());
    }

    /**
     * A quality asked for without a number, read as a preset's own rule on each metric - so "low debt"
     * means whatever D/E threshold the preset uses, and changes with it.
     *
     * @param phrases the words, matched whole and case-insensitively ("low debt", "low leverage")
     * @param preset  the preset whose rules supply the thresholds
     * @param metrics the metrics the phrase is read as; the preset must have a rule on each
     */
    public record VagueTerm(List<String> phrases, String preset, List<ScreeningMetric> metrics) {

        public VagueTerm {
            phrases = phrases == null ? List.of() : List.copyOf(phrases);
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
        }
    }

    /**
     * @param cron                 when the nightly run starts, in India time
     * @param pauseBetweenSymbols  a gap between companies, on top of the Yahoo request throttle
     */
    public record Snapshot(String cron, Duration pauseBetweenSymbols) {

        public Snapshot {
            pauseBetweenSymbols = pauseBetweenSymbols == null ? Duration.ofSeconds(1) : pauseBetweenSymbols;
        }
    }

    /**
     * @param tieBreak       orders stocks that met the same share of criteria
     * @param tieBreakLowerFirst true when a lower tie-break value ranks first
     */
    public record Preset(String description, ScreeningMetric tieBreak, boolean tieBreakLowerFirst,
                         Map<String, List<Rule>> rules) {
    }
}
