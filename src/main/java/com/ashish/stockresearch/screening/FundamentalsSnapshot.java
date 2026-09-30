package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * One company's screening metrics as captured by a snapshot run. Screening reads only these, so a
 * screen takes milliseconds and never calls a data provider during a chat.
 *
 * @param classificationBasis how the industry group was decided (the provider's sector and industry)
 * @param snapshotDate        the date of the run, in India
 */
public record FundamentalsSnapshot(
        String symbol,
        String companyName,
        IndustryGroup industryGroup,
        String classificationBasis,
        LocalDate snapshotDate,
        Instant capturedAt,
        Map<ScreeningMetric, SnapshotMetric> metrics
) {

    public FundamentalsSnapshot {
        if (symbol == null || symbol.isBlank() || industryGroup == null || snapshotDate == null) {
            throw new IllegalArgumentException("a snapshot needs a symbol, an industry group and a date");
        }
        EnumMap<ScreeningMetric, SnapshotMetric> copy = new EnumMap<>(ScreeningMetric.class);
        if (metrics != null) {
            copy.putAll(metrics);
        }
        metrics = java.util.Collections.unmodifiableMap(copy);
    }

    /** The stored metric, or UNAVAILABLE if this snapshot did not record it. */
    public SnapshotMetric metric(ScreeningMetric metric) {
        SnapshotMetric stored = metrics.get(metric);
        return stored != null ? stored : SnapshotMetric.unavailable(metric, "not recorded in the snapshot of "
                + snapshotDate);
    }
}
