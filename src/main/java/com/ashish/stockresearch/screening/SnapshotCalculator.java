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

    private SnapshotCalculator() {
    }

    /**
     * Return on equity averaged over the last {@code years} consecutive fiscal years' calculated ROE. Every
     * year must be on the same basis - profit over average equity - so a year whose ROE could only be taken on
     * closing equity (no prior-year balance sheet) gives no average rather than one over mixed bases.
     */
    static FinancialDataPoint roeAverage(List<AnnualRatios> ratios, int years, CalculationVerifier.SourceValues sources) {
        String metric = "returnOnEquity%dyAveragePercent".formatted(years);
        if (ratios.size() < years) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null,
                    "needs %d fiscal years of return on equity; %d available".formatted(years, ratios.size()));
        }
        List<AnnualRatios> window = ratios.subList(ratios.size() - years, ratios.size());
        Optional<String> gap = notConsecutive(window.stream().map(AnnualRatios::period).toList());
        if (gap.isPresent()) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null, gap.get());
        }
        ReportingPeriod span = span(window.get(0).period(), window.get(window.size() - 1).period(), years);
        List<FinancialDataPoint> roe = window.stream().map(AnnualRatios::returnOnEquityPercent).toList();
        String calculation = "(%s return on equity) / %d".formatted(String.join(" + ",
                window.stream().map(year -> year.period().label()).toList()), years);
        FinancialDataPoint last = roe.get(roe.size() - 1);
        Optional<FinancialDataPoint> blocked = blocked(metric, Unit.PERCENT, span, last, Formula.MEAN, calculation, roe);
        if (blocked.isPresent()) {
            return blocked.get();
        }
        Optional<FinancialDataPoint> onClosing = roe.stream().filter(point -> !onAverageEquity(point)).findFirst();
        if (onClosing.isPresent()) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, span, last.source(), ("%s return on equity "
                    + "is on closing equity (no prior-year balance sheet), so the %d years are not on one basis")
                    .formatted(onClosing.get().period().label(), years));
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

    /**
     * The lowest of the last {@code years} consecutive fiscal years' calculated {@code name} (return on equity or
     * on capital employed): at or above a threshold, it held there in every one of those years. As for the average,
     * every year must be on average balances, so the years are on one basis.
     */
    static FinancialDataPoint lowest(List<AnnualRatios> ratios, int years, Function<AnnualRatios, FinancialDataPoint> field,
                                     String name, String metric, CalculationVerifier.SourceValues sources) {
        Optional<Window> window = window(ratios, years, field, name, metric, Unit.PERCENT, Formula.MINIMUM, sources);
        if (window.isEmpty() || window.get().blocked() != null) {
            return window.map(Window::blocked).orElseThrow();
        }
        Window w = window.get();
        Optional<FinancialDataPoint> onClosing = w.points().stream().filter(point -> !onAverageBalances(point))
                .findFirst();
        if (onClosing.isPresent()) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, w.span(), w.last().source(), ("%s %s is on "
                    + "closing balances (no prior-year balance sheet), so the %d years are not on one basis")
                    .formatted(onClosing.get().period().label(), name, years));
        }
        return verified(FinancialCalculator.minimum(w.values())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENT, w.span(), w.last().source(),
                        Formula.MINIMUM, "lowest of %s %s".formatted(w.labels(), name), w.inputs()))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.PERCENT, w.span(), w.last().source(),
                        "the lowest could not be calculated")), sources);
    }

    /**
     * How far a calculated percentage moved over the last {@code years} consecutive fiscal years: the highest minus
     * the lowest, in percentage points. Small when it held steady, whatever its level.
     */
    static FinancialDataPoint range(List<AnnualRatios> ratios, int years, Function<AnnualRatios, FinancialDataPoint> field,
                                    String name, String metric, CalculationVerifier.SourceValues sources) {
        Optional<Window> window = window(ratios, years, field, name, metric, Unit.PERCENTAGE_POINTS, Formula.RANGE,
                sources);
        if (window.isEmpty() || window.get().blocked() != null) {
            return window.map(Window::blocked).orElseThrow();
        }
        Window w = window.get();
        return verified(FinancialCalculator.range(w.values())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENTAGE_POINTS, w.span(),
                        w.last().source(), Formula.RANGE, "highest minus lowest of %s %s".formatted(w.labels(), name),
                        w.inputs()))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.PERCENTAGE_POINTS, w.span(),
                        w.last().source(), "the range could not be calculated")), sources);
    }

    /**
     * The last {@code years} fiscal years of one calculated ratio, ready to be reduced to one figure; or, as
     * {@code blocked}, why they cannot be: too few years, a gap, or a year that is not VALID.
     */
    private record Window(List<FinancialDataPoint> points, ReportingPeriod span, String labels,
                          List<CalculationInput> inputs, FinancialDataPoint blocked) {

        FinancialDataPoint last() {
            return points.get(points.size() - 1);
        }

        List<BigDecimal> values() {
            return points.stream().map(FinancialDataPoint::value).toList();
        }
    }

    private static Optional<Window> window(List<AnnualRatios> ratios, int years,
                                           Function<AnnualRatios, FinancialDataPoint> field, String name, String metric,
                                           Unit unit, Formula formula, CalculationVerifier.SourceValues sources) {
        if (ratios.size() < years) {
            return Optional.of(new Window(List.of(), null, null, List.of(), FinancialDataPoint.unavailable(metric, unit,
                    ReportingPeriod.unknown(), null, "needs %d fiscal years of %s; %d available".formatted(years, name,
                            ratios.size()))));
        }
        List<AnnualRatios> chosen = ratios.subList(ratios.size() - years, ratios.size());
        Optional<String> gap = notConsecutive(chosen.stream().map(AnnualRatios::period).toList());
        if (gap.isPresent()) {
            return Optional.of(new Window(List.of(), null, null, List.of(), FinancialDataPoint.unavailable(metric, unit,
                    ReportingPeriod.unknown(), null, gap.get())));
        }
        ReportingPeriod span = span(chosen.get(0).period(), chosen.get(chosen.size() - 1).period(), years);
        List<FinancialDataPoint> points = chosen.stream().map(field).toList();
        String labels = String.join(", ", chosen.stream().map(year -> year.period().label()).toList());
        FinancialDataPoint last = points.get(points.size() - 1);
        if (last == null) {
            return Optional.of(new Window(List.of(), null, null, List.of(), FinancialDataPoint.unavailable(metric, unit,
                    span, null, "an input is missing")));
        }
        Optional<FinancialDataPoint> blocked = blocked(metric, unit, span, last, formula,
                "%s of %s %s".formatted(formula.name().toLowerCase(java.util.Locale.ROOT), labels, name), points);
        if (blocked.isPresent()) {
            return Optional.of(new Window(points, span, labels, List.of(), blocked.get()));
        }
        // The yearly values are the application's own verified calculations: no provider field to reconcile, but
        // the figure is still recomputed from them.
        List<CalculationInput> inputs = points.stream().map(point -> new CalculationInput(
                point.period().label() + " " + name, null, point.value(), point.unit(), point.period().label())).toList();
        return Optional.of(new Window(points, span, labels, inputs, null));
    }

    /**
     * A return on average balances: its inputs include the prior year-end's balance as well as this year's - three
     * inputs for return on equity (profit, closing, opening equity), five for return on capital employed (EBIT and
     * the two year-ends' total assets and current liabilities).
     */
    private static boolean onAverageBalances(FinancialDataPoint point) {
        return switch (point.formula()) {
            case AVERAGE_BALANCE_RETURN_PERCENT -> point.inputs().size() == 3;
            case RETURN_ON_CAPITAL_EMPLOYED_PERCENT -> point.inputs().size() == 5;
            default -> false;
        };
    }

    /**
     * Profit over the average of opening and closing equity: besides the year's profit, its inputs are balances
     * at two different dates, the prior year-end's and this year's.
     */
    private static boolean onAverageEquity(FinancialDataPoint roe) {
        String year = roe.period().label();
        return roe.formula() == Formula.AVERAGE_BALANCE_RETURN_PERCENT && roe.inputs().stream()
                .map(CalculationInput::period).filter(period -> period != null && !period.equals(year))
                .distinct().count() == 2;
    }

    /** Compound annual growth over exactly the last {@code years} fiscal years ({@code years} + 1 consecutive year-ends). */
    static FinancialDataPoint cagr(List<AnnualFinancials> annual, int years,
                                   Function<AnnualFinancials, FinancialDataPoint> field, String name, String metric,
                                   CalculationVerifier.SourceValues sources) {
        if (annual.size() < years + 1) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null,
                    "a %d-year %s CAGR needs %d fiscal years; %d available".formatted(years, name, years + 1,
                            annual.size()));
        }
        List<AnnualFinancials> window = annual.subList(annual.size() - years - 1, annual.size());
        Optional<String> gap = notConsecutive(window.stream().map(AnnualFinancials::period).toList());
        if (gap.isPresent()) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, ReportingPeriod.unknown(), null, gap.get());
        }
        AnnualFinancials first = window.get(0);
        AnnualFinancials last = window.get(window.size() - 1);
        ReportingPeriod span = span(first.period(), last.period(), years);
        String calculation = "((%s %s / %s %s)^(1/%d) - 1) x 100".formatted(last.period().label(), name,
                first.period().label(), name, years);
        FinancialDataPoint start = field.apply(first);
        FinancialDataPoint end = field.apply(last);
        // Every year of the window must be usable, as for the research layer's CAGR: a disputed middle year
        // puts the series in doubt.
        Optional<FinancialDataPoint> blocked = blocked(metric, Unit.PERCENT, span, end, Formula.CAGR_PERCENT,
                calculation, window.stream().map(field).toList());
        if (blocked.isPresent()) {
            return blocked.get();
        }
        BigDecimal spanYears = BigDecimal.valueOf(years);
        return verified(FinancialCalculator.cagrPercent(start.value(), end.value(), spanYears)
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENT, span, end.source(),
                        Formula.CAGR_PERCENT, calculation,
                        List.of(CalculationInput.of(first.period().label() + " " + name, start),
                                CalculationInput.of(last.period().label() + " " + name, end),
                                CalculationInput.derived("years", spanYears, Unit.YEARS))))
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
