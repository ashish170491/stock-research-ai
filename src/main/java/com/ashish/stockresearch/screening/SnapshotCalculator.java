package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.Unit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The screening metrics the research layer does not already calculate, each over an exact span so that
 * every stock is measured the same way. All arithmetic is {@link FinancialCalculator}'s, every result
 * records its formula and inputs, and every result is reproduced by {@link CalculationVerifier} before it
 * is stored. An input that is not VALID - or a window of years that is short or not consecutive - gives
 * no value, never a partial one.
 */
final class SnapshotCalculator {

    static final int YEARS = 3;

    private SnapshotCalculator() {
    }

    /** Return on equity averaged over the last 3 consecutive fiscal years' calculated ROE. */
    static FinancialDataPoint roe3yAverage(List<AnnualRatios> ratios, CalculationVerifier.SourceValues sources) {
        String metric = "returnOnEquity3yAveragePercent";
        if (ratios.size() < YEARS) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null,
                    "needs %d fiscal years of return on equity; %d available".formatted(YEARS, ratios.size()));
        }
        List<AnnualRatios> window = ratios.subList(ratios.size() - YEARS, ratios.size());
        Optional<String> gap = notConsecutive(window.stream().map(AnnualRatios::period).toList());
        if (gap.isPresent()) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null, gap.get());
        }
        ReportingPeriod span = span(window.get(0).period(), window.get(window.size() - 1).period(), YEARS);
        List<FinancialDataPoint> roe = window.stream().map(AnnualRatios::returnOnEquityPercent).toList();
        String calculation = "(%s return on equity) / %d".formatted(String.join(" + ",
                window.stream().map(year -> year.period().label()).toList()), YEARS);
        FinancialDataPoint last = roe.get(roe.size() - 1);
        Optional<FinancialDataPoint> blocked = blocked(metric, Unit.PERCENT, span, last, Formula.MEAN, calculation, roe);
        if (blocked.isPresent()) {
            return blocked.get();
        }
        // The yearly ROE values are the application's own verified calculations, so they carry no provider
        // field to reconcile; the mean is still recomputed from them.
        List<CalculationInput> inputs = roe.stream().map(point -> new CalculationInput(
                point.period().label() + " return on equity", null, point.value(), Unit.PERCENT,
                point.period().label())).toList();
        return verified(FinancialCalculator.mean(roe.stream().map(FinancialDataPoint::value).toList())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENT, span, last.source(),
                        Formula.MEAN, calculation, inputs))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.PERCENT, span, last.source(),
                        "the average could not be calculated")), sources);
    }

    /** Compound annual growth over exactly the last 3 fiscal years (4 consecutive year-ends). */
    static FinancialDataPoint cagr3y(List<AnnualFinancials> annual, Function<AnnualFinancials, FinancialDataPoint> field,
                                     String name, String metric, CalculationVerifier.SourceValues sources) {
        if (annual.size() < YEARS + 1) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null,
                    "a %d-year %s CAGR needs %d fiscal years; %d available".formatted(YEARS, name, YEARS + 1,
                            annual.size()));
        }
        List<AnnualFinancials> window = annual.subList(annual.size() - YEARS - 1, annual.size());
        Optional<String> gap = notConsecutive(window.stream().map(AnnualFinancials::period).toList());
        if (gap.isPresent()) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null, gap.get());
        }
        AnnualFinancials first = window.get(0);
        AnnualFinancials last = window.get(window.size() - 1);
        ReportingPeriod span = span(first.period(), last.period(), YEARS);
        String calculation = "((%s %s / %s %s)^(1/%d) - 1) x 100".formatted(last.period().label(), name,
                first.period().label(), name, YEARS);
        FinancialDataPoint start = field.apply(first);
        FinancialDataPoint end = field.apply(last);
        // Every year of the window must be usable, as for the research layer's CAGR: a disputed middle year
        // puts the series in doubt.
        Optional<FinancialDataPoint> blocked = blocked(metric, Unit.PERCENT, span, end, Formula.CAGR_PERCENT,
                calculation, window.stream().map(field).toList());
        if (blocked.isPresent()) {
            return blocked.get();
        }
        BigDecimal years = BigDecimal.valueOf(YEARS);
        return verified(FinancialCalculator.cagrPercent(start.value(), end.value(), years)
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENT, span, end.source(),
                        Formula.CAGR_PERCENT, calculation,
                        List.of(CalculationInput.of(first.period().label() + " " + name, start),
                                CalculationInput.of(last.period().label() + " " + name, end),
                                CalculationInput.derived("years", years, Unit.YEARS))))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.PERCENT, span, end.source(),
                        "%s CAGR is undefined: the start or end value is not positive".formatted(name))), sources);
    }

    /** The latest fiscal year's dividends paid as a percentage of the same year's operating cash flow. */
    static FinancialDataPoint dividendsToOperatingCashFlow(List<AnnualFinancials> annual,
                                                           CalculationVerifier.SourceValues sources) {
        String metric = "dividendsToOcfPercent";
        if (annual.isEmpty()) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null,
                    "no fiscal-year statements are available");
        }
        AnnualFinancials year = annual.get(annual.size() - 1);
        String label = year.period().label();
        String calculation = ("-(%s dividends paid) / %s operating cash flow x 100 (dividends paid is reported as an "
                + "outflow)").formatted(label, label);
        FinancialDataPoint dividends = year.cashDividendsPaid();
        FinancialDataPoint cash = year.operatingCashFlow();
        Optional<FinancialDataPoint> blocked = blocked(metric, Unit.PERCENT, year.period(), cash,
                Formula.DIVIDENDS_TO_OPERATING_CASH_FLOW_PERCENT, calculation, List.of(dividends, cash));
        if (blocked.isPresent()) {
            return blocked.get();
        }
        return verified(FinancialCalculator.dividendsToOperatingCashFlowPercent(dividends.value(), cash.value())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENT, year.period(), cash.source(),
                        Formula.DIVIDENDS_TO_OPERATING_CASH_FLOW_PERCENT, calculation,
                        List.of(CalculationInput.of(label + " dividends paid", dividends),
                                CalculationInput.of(label + " operating cash flow", cash))))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.PERCENT, year.period(), cash.source(),
                        dividends.value().signum() > 0
                                ? "dividends paid is reported as a positive figure, not an outflow, so its meaning "
                                        + "is not established"
                                : "operating cash flow for %s is not positive".formatted(label))), sources);
    }

    /** The value unchanged if it verifies, or INVALID with the reason. */
    private static FinancialDataPoint verified(FinancialDataPoint point, CalculationVerifier.SourceValues sources) {
        return CalculationVerifier.verify(point, sources);
    }

    /**
     * Not calculated, with the status of the first input that is not VALID (a missing input outranks a
     * conflict, which outranks an invalid one), or because the inputs are in different units.
     */
    private static Optional<FinancialDataPoint> blocked(String metric, Unit unit, ReportingPeriod period,
                                                        FinancialDataPoint sourceOf, Formula formula,
                                                        String calculation, List<FinancialDataPoint> inputs) {
        FinancialDataPoint worst = null;
        for (FinancialDataPoint input : inputs) {
            if (input == null) {
                return Optional.of(FinancialDataPoint.unavailable(metric, unit, period, null, "an input is missing"));
            }
            if (!input.available() && (worst == null || rank(input.status()) > rank(worst.status()))) {
                worst = input;
            }
        }
        List<String> fields = new ArrayList<>();
        inputs.forEach(input -> input.dependsOnFields().stream().filter(f -> !fields.contains(f)).forEach(fields::add));
        if (worst != null) {
            String reason = "input %s (%s) is %s: %s".formatted(worst.metric(), worst.period().label(), worst.status(),
                    worst.statusReason());
            return Optional.of(worst.status() == DataStatus.UNAVAILABLE
                    ? FinancialDataPoint.unavailable(metric, unit, period, sourceOf.source(), reason)
                    : FinancialDataPoint.notCalculated(metric, unit, period, sourceOf.source(), worst.status(), formula,
                            calculation, fields, reason));
        }
        Unit first = inputs.get(0).unit();
        if (inputs.stream().anyMatch(input -> input.unit() != first)) {
            return Optional.of(FinancialDataPoint.unavailable(metric, unit, period, sourceOf.source(),
                    "inputs are in different units; currencies are never converted or mixed"));
        }
        return Optional.empty();
    }

    private static int rank(DataStatus status) {
        return switch (status) {
            case UNAVAILABLE -> 3;
            case DATA_CONFLICT -> 2;
            case INVALID -> 1;
            case VALID -> 0;
        };
    }

    private static Optional<String> notConsecutive(List<ReportingPeriod> periods) {
        for (int i = 1; i < periods.size(); i++) {
            Integer earlier = periods.get(i - 1).fiscalYear();
            Integer later = periods.get(i).fiscalYear();
            if (earlier == null || later == null || later - earlier != 1) {
                return Optional.of("needs consecutive fiscal years; %s and %s are not"
                        .formatted(periods.get(i - 1).label(), periods.get(i).label()));
            }
        }
        return Optional.empty();
    }

    /** A span labelled from the FiscalCalendar labels of its first and last years. */
    private static ReportingPeriod span(ReportingPeriod first, ReportingPeriod last, int years) {
        return new ReportingPeriod(PeriodType.MULTI_YEAR, first.start(), last.end(),
                "%s to %s (%d years)".formatted(first.label(), last.label(), years), null, null);
    }
}
