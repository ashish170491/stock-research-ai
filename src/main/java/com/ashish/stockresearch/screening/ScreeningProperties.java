package com.ashish.stockresearch.screening;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code app.screening}: the snapshot schedule and the screening presets.
 *
 * @param presets by name; each preset's {@code rules} are keyed by {@code default} (non-financial
 *                companies) or an industry group name (BANK, NBFC, INSURANCE, ...)
 */
@ConfigurationProperties("app.screening")
public record ScreeningProperties(Snapshot snapshot, Map<String, Preset> presets) {

    public ScreeningProperties {
        snapshot = snapshot == null ? new Snapshot(null, null) : snapshot;
        // in the order configured, which is the order they are listed in
        presets = presets == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(presets));
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
