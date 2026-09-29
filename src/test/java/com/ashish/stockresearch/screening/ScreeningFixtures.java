package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/** Hand-built snapshots for screening tests: no provider, no database. */
final class ScreeningFixtures {

    static final LocalDate DATE = LocalDate.of(2026, 9, 28);

    private ScreeningFixtures() {
    }

    static SnapshotMetric valid(ScreeningMetric metric, String value) {
        BigDecimal v = new BigDecimal(value);
        return new SnapshotMetric(metric, v, metric.unit(), FinancialUnits.display(v, metric.unit()), DataStatus.VALID,
                null, "FY26", "Yahoo Finance", null, null);
    }

    /** A value in the given status. A DATA_CONFLICT keeps the provider's value, as a REPORTED point does. */
    static SnapshotMetric withStatus(ScreeningMetric metric, DataStatus status, String keptValue) {
        BigDecimal v = keptValue == null ? null : new BigDecimal(keptValue);
        return new SnapshotMetric(metric, v, metric.unit(), v == null ? status.name()
                : FinancialUnits.display(v, metric.unit()), status, "test: " + status, "FY26", "Yahoo Finance", null,
                null);
    }

    static SnapshotMetric unchecked(ScreeningMetric metric, String value) {
        BigDecimal v = new BigDecimal(value);
        return new SnapshotMetric(metric, v, metric.unit(), FinancialUnits.display(v, metric.unit()), DataStatus.VALID,
                null, "As of 2026-09-28", "Yahoo Finance", null, "could not be checked against share price / EPS");
    }

    static Builder stock(String symbol, IndustryGroup group) {
        return new Builder(symbol, group);
    }

    static final class Builder {
        private final String symbol;
        private final IndustryGroup group;
        private final Map<ScreeningMetric, SnapshotMetric> metrics = new EnumMap<>(ScreeningMetric.class);

        private Builder(String symbol, IndustryGroup group) {
            this.symbol = symbol;
            this.group = group;
        }

        Builder with(ScreeningMetric metric, String value) {
            metrics.put(metric, valid(metric, value));
            return this;
        }

        Builder with(SnapshotMetric metric) {
            metrics.put(metric.metric(), metric);
            return this;
        }

        FundamentalsSnapshot build() {
            return new FundamentalsSnapshot(symbol, symbol + " Limited", group, "test classification", DATE,
                    Instant.parse("2026-09-28T13:10:00Z"), metrics);
        }
    }
}
