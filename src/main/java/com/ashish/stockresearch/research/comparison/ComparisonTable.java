package com.ashish.stockresearch.research.comparison;

import com.ashish.stockresearch.glossary.MetricQuestion;
import com.ashish.stockresearch.research.model.CurrencyInfo;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.SnapshotMetric;

import java.math.BigDecimal;
import java.util.List;

/**
 * Two to five companies aligned on the same metrics: one row per metric, one cell per company. A cell is
 * comparable only when its period and currency match the row's reference (its first VALID cell); anything
 * else is marked NOT_COMPARABLE rather than shown beside a figure it cannot be read against.
 */
public record ComparisonTable(List<String> symbols, List<Row> rows, List<Gap> gaps) {

    /** One company's value of a row's metric, exactly as its own report already has it - nothing here is
     * recalculated. */
    public record Figure(BigDecimal value, Unit unit, String display, DataStatus status, String statusReason,
                         String periodLabel, CurrencyInfo currency) {

        public static Figure of(FinancialDataPoint point) {
            if (point == null) {
                return null;
            }
            return new Figure(point.value(), point.unit(), point.display(), point.status(), point.statusReason(),
                    point.period() != null && point.period().known() ? point.period().label() : null, point.currency());
        }

        public static Figure of(SnapshotMetric metric) {
            if (metric == null) {
                return null;
            }
            return new Figure(metric.value(), metric.unit(), metric.display(), metric.status(), metric.statusReason(),
                    metric.periodLabel(), null);
        }
    }

    public record Cell(String symbol, Figure value, boolean comparable, String notComparableReason,
                       IndustryGroup group, Figure groupMedian) {

        /** VALID and aligned with the row's reference - the only cells a rank or a gap between companies may use. */
        public boolean usable() {
            return comparable && value != null && value.status() == DataStatus.VALID;
        }
    }

    public record Row(String metricKey, String label, String howToRead, MetricQuestion question, List<Cell> cells) {
    }

    /** A company whose report could not be built at all: kept in the comparison, with why, instead of dropped. */
    public record Gap(String symbol, String reason) {
    }
}
