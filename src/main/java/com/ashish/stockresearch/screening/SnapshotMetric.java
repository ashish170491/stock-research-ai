package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Unit;

import java.math.BigDecimal;
import java.util.List;

/**
 * One metric as the snapshot stored it: the value and its {@link DataStatus} exactly as the research
 * layer produced them, with period, source and calculation, so a screening result can say what it was
 * decided on.
 *
 * @param uncheckedReason set when the value is VALID but was not cross-checked (a valuation multiple whose
 *                        price or per-share input was missing); such a value is shown but never passes a rule
 */
public record SnapshotMetric(
        ScreeningMetric metric,
        BigDecimal value,
        Unit unit,
        String display,
        DataStatus status,
        String statusReason,
        String periodLabel,
        String source,
        String calculation,
        String uncheckedReason
) {

    public SnapshotMetric {
        if (metric == null || status == null || display == null) {
            throw new IllegalArgumentException("a snapshot metric needs a metric, a status and a display");
        }
        if (status == DataStatus.VALID && value == null) {
            throw new IllegalArgumentException("a VALID snapshot metric needs a value");
        }
        if (status != DataStatus.VALID && (statusReason == null || statusReason.isBlank())) {
            throw new IllegalArgumentException("a " + status + " snapshot metric must say why");
        }
    }

    public static SnapshotMetric of(ScreeningMetric metric, FinancialDataPoint point) {
        return of(metric, point, null);
    }

    public static SnapshotMetric of(ScreeningMetric metric, FinancialDataPoint point, String uncheckedReason) {
        if (point == null) {
            return unavailable(metric, "not supplied by the research data");
        }
        return new SnapshotMetric(metric, point.value(), point.unit(), point.display(), point.status(),
                point.statusReason(), point.period().label(), source(point), point.calculation(),
                point.available() ? uncheckedReason : null);
    }

    public static SnapshotMetric unavailable(ScreeningMetric metric, String reason) {
        return new SnapshotMetric(metric, null, metric.unit(), "UNAVAILABLE", DataStatus.UNAVAILABLE, reason, null,
                null, null, null);
    }

    /** Only a VALID, checked value may decide a rule. */
    public boolean usable() {
        return status == DataStatus.VALID && value != null && uncheckedReason == null;
    }

    private static String source(FinancialDataPoint point) {
        if (point.source() == null) {
            return null;
        }
        List<String> fields = point.dependsOnFields();
        return fields.isEmpty() ? point.source().source()
                : "%s (%s)".formatted(point.source().source(), String.join(", ", fields));
    }
}
