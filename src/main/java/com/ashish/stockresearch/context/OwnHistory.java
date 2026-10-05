package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A company's own value of one metric in each fiscal year the report has, the range of those that are VALID,
 * and where the latest year sits in it. A year that is not VALID - or that depends on a value in a
 * DATA_CONFLICT - is listed with its status and left out of the range. A range needs at least two VALID years.
 *
 * @param metric       the report's metric key ("returnOnEquityPercent")
 * @param years        every fiscal year, oldest first
 * @param filed        true when the years were read from the company's NSE filings
 * @param lowest       the lowest VALID year's value; UNAVAILABLE, with why, when fewer than two years are VALID
 * @param highest      the highest likewise
 * @param latestRankPoint the latest year's place counted from the highest among the VALID years, calculated and
 *                        verified; null when the latest year is not VALID or there is no range
 */
public record OwnHistory(String metric, List<Year> years, boolean filed, FinancialDataPoint lowest,
                         FinancialDataPoint highest, FinancialDataPoint latestRankPoint) {

    static final int MINIMUM_YEARS = 2;

    /**
     * @param point  the year's value as the report has it
     * @param usable true when it is VALID and depends on no value in a DATA_CONFLICT
     */
    public record Year(String label, FinancialDataPoint point, boolean usable) {

        /** The value, or the status that keeps it out of the range. */
        public String shown() {
            if (usable) {
                return point.display();
            }
            return point.status() == DataStatus.VALID ? DataStatus.DATA_CONFLICT.name() : point.status().name();
        }
    }

    public OwnHistory {
        years = List.copyOf(years);
    }

    /** The latest year's verified place among the VALID years; null when it has none. */
    public Integer latestRank() {
        return Ranks.place(latestRankPoint);
    }

    public List<Year> usableYears() {
        return years.stream().filter(Year::usable).toList();
    }

    public Year latest() {
        return years.get(years.size() - 1);
    }

    public boolean hasRange() {
        return lowest.status() == DataStatus.VALID && highest.status() == DataStatus.VALID;
    }

    /**
     * @param points   the metric's value in each fiscal year, oldest first
     * @param disputed values that depend on a value in a DATA_CONFLICT, which a VALID status alone does not show
     */
    public static Optional<OwnHistory> of(String metric, List<FinancialDataPoint> points, boolean filed,
                                          Predicate<FinancialDataPoint> disputed) {
        if (points.isEmpty()) {
            return Optional.empty();
        }
        List<Year> years = points.stream().map(point -> new Year(point.period().label(), point,
                point.status() == DataStatus.VALID && point.value() != null && !disputed.test(point))).toList();
        List<Year> usable = years.stream().filter(Year::usable).toList();
        ReportingPeriod period = years.size() == 1 ? points.get(0).period()
                : ReportingPeriod.multiYear(points.get(0).period().end(), points.get(points.size() - 1).period().end());
        if (usable.size() < MINIMUM_YEARS) {
            String reason = "a range needs at least %d VALID fiscal years; %d of the %d %s VALID".formatted(
                    MINIMUM_YEARS, usable.size(), years.size(), usable.size() == 1 ? "is" : "are");
            FinancialDataPoint none = FinancialDataPoint.unavailable(metric + " range", points.get(0).unit(), period,
                    points.get(points.size() - 1).source(), reason);
            return Optional.of(new OwnHistory(metric, years, filed, none, none, null));
        }
        List<BigDecimal> values = usable.stream().map(year -> year.point().value()).toList();
        // The yearly values are the application's own verified calculations: no provider field to reconcile,
        // but the lowest and highest are still recomputed from them.
        List<CalculationInput> inputs = usable.stream().map(year -> new CalculationInput(year.label() + " " + metric,
                null, year.point().value(), year.point().unit(), year.label())).toList();
        String labels = String.join(", ", usable.stream().map(Year::label).toList());
        // the range is of the VALID years, so it carries their source: a later year may have none
        FinancialDataPoint basis = usable.get(usable.size() - 1).point();
        FinancialDataPoint lowest = range(metric + " lowest", "lowest of " + labels, Formula.MINIMUM,
                FinancialCalculator::minimum, values, inputs, basis, period);
        FinancialDataPoint highest = range(metric + " highest", "highest of " + labels, Formula.MAXIMUM,
                FinancialCalculator::maximum, values, inputs, basis, period);
        Year latest = years.get(years.size() - 1);
        FinancialDataPoint rank = null;
        if (latest.usable()) {
            CalculationInput ranked = inputs.get(inputs.size() - 1);
            rank = Ranks.of(metric + " " + latest.label() + " place", ranked, inputs, period, basis.source(),
                    "%s's place from the highest of %s".formatted(latest.label(), labels),
                    new CalculationVerifier.SourceValues());
        }
        return Optional.of(new OwnHistory(metric, years, filed, lowest, highest, rank));
    }

    private static FinancialDataPoint range(String name, String calculation, Formula formula,
                                            Function<List<BigDecimal>, Optional<BigDecimal>> calculate,
                                            List<BigDecimal> values, List<CalculationInput> inputs,
                                            FinancialDataPoint last, ReportingPeriod period) {
        return CalculationVerifier.verify(calculate.apply(values)
                .map(value -> FinancialDataPoint.calculated(name, value, last.unit(), period, last.source(), formula,
                        calculation, inputs))
                .orElseGet(() -> FinancialDataPoint.unavailable(name, last.unit(), period, last.source(),
                        "could not be calculated")), new CalculationVerifier.SourceValues());
    }
}
