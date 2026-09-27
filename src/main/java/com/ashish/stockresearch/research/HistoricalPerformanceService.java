package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.calc.FinancialCalculator.DatedValue;
import com.ashish.stockresearch.research.calc.FinancialCalculator.Drawdown;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.CalendarYearReturn;
import com.ashish.stockresearch.research.model.CurrencyInfo;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
import com.ashish.stockresearch.research.model.PriceHistory;
import com.ashish.stockresearch.research.model.PricePoint;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.Unit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Calculates historical price performance from a raw {@link PriceHistory}:
 * window selection, total return, CAGR, high/low, maximum drawdown and
 * calendar-year returns. Provider-agnostic, and the only place these
 * figures are produced - the model receives them finished.
 *
 * Window selection: the end is the last close in the series; the start is
 * the first trading day on or after (end - requested years). A provider may
 * return more history than asked for (e.g. when widening to a range token it
 * supports), and that extra history must not leak into a shorter window's
 * CAGR.
 *
 * Every calculated figure records the closes it was calculated from, and is
 * verified against the price series before it is returned: a figure that
 * does not reproduce from those closes is INVALID, never shown.
 */
@Service
public class HistoricalPerformanceService {

    static final String PRICE_FIELD = "adjusted daily close";

    public HistoricalPerformance analyze(PriceHistory history, int requestedYears) {
        if (history.currency() != null && !"INR".equalsIgnoreCase(history.currency())) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "Price history for '%s' is in %s, not INR; performance is not calculated rather than "
                            .formatted(history.symbol(), history.currency())
                            + "mislabelling the prices as rupees");
        }
        List<PricePoint> sorted = history.closes().stream()
                .sorted(Comparator.comparing(PricePoint::date))
                .toList();
        if (sorted.size() < 2) {
            throw tooShort(history, requestedYears);
        }
        LocalDate end = sorted.get(sorted.size() - 1).date();
        LocalDate earliestAllowed = end.minusYears(requestedYears);
        List<DatedValue> window = sorted.stream()
                .filter(point -> !point.date().isBefore(earliestAllowed))
                .map(point -> new DatedValue(point.date(), point.close()))
                .toList();
        if (window.size() < 2) {
            throw tooShort(history, requestedYears);
        }

        DatedValue first = window.get(0);
        DatedValue last = window.get(window.size() - 1);
        ReportingPeriod span = ReportingPeriod.multiYear(first.date(), last.date());
        BigDecimal exactYears = FinancialCalculator.yearsBetween(first.date(), last.date());
        SourceInfo source = new SourceInfo(history.provenance().source(), history.provenance().sourceType(),
                PRICE_FIELD, null, last.date());
        String windowText = "%s (%s) to %s (%s)".formatted(first.date(), first.value().toPlainString(),
                last.date(), last.value().toPlainString());

        CalculationInput startClose = close("start close", first);
        CalculationInput endClose = close("end close", last);
        FinancialDataPoint cagr = FinancialCalculator.cagrPercent(first.value(), last.value(), exactYears)
                .map(value -> FinancialDataPoint.calculated("priceCagrPercent", value, Unit.PERCENT, span, source,
                        Formula.CAGR_PERCENT, "((end close / start close)^(1/%s years) - 1) x 100, %s"
                                .formatted(exactYears.toPlainString(), windowText),
                        List.of(startClose, endClose, CalculationInput.derived("years", exactYears, Unit.YEARS))))
                .orElseGet(() -> FinancialDataPoint.unavailable("priceCagrPercent", Unit.PERCENT, span, source,
                        "CAGR is undefined over a window this short or with non-positive prices"));
        FinancialDataPoint totalReturn = FinancialCalculator.totalReturnPercent(first.value(), last.value())
                .map(value -> FinancialDataPoint.calculated("totalReturnPercent", value, Unit.PERCENT, span, source,
                        Formula.GROWTH_PERCENT, "(end close / start close - 1) x 100, " + windowText,
                        List.of(startClose, endClose)))
                .orElseGet(() -> FinancialDataPoint.unavailable("totalReturnPercent", Unit.PERCENT, span, source,
                        "Total return not calculated: the start price is not positive"));

        Drawdown drawdown = FinancialCalculator.maxDrawdown(window).orElseThrow();
        DatedValue high = window.stream().max(Comparator.comparing(DatedValue::value)).orElseThrow();
        DatedValue low = window.stream().min(Comparator.comparing(DatedValue::value)).orElseThrow();
        long days = ChronoUnit.DAYS.between(first.date(), last.date());

        CalculationVerifier.SourceValues closes = new CalculationVerifier.SourceValues();
        window.forEach(point -> closes.add(PRICE_FIELD, ReportingPeriod.pointInTime(point.date()).label(), point.value()));
        CurrencyInfo rupees = CurrencyInfo.unchanged("INR");

        return new HistoricalPerformance(
                history.symbol(),
                history.exchange(),
                span,
                requestedYears,
                verified(FinancialDataPoint.calculated("actualYears", exactYears.setScale(2, RoundingMode.HALF_UP),
                        Unit.YEARS, span, source, Formula.DAYS_TO_YEARS,
                        "days between %s and %s / 365.25".formatted(first.date(), last.date()),
                        List.of(CalculationInput.derived("days", BigDecimal.valueOf(days), null))), closes),
                FinancialDataPoint.reported("startPrice", first.value(), ProviderUnit.PER_SHARE, first.value(),
                        Unit.INR_PER_SHARE, ReportingPeriod.pointInTime(first.date()), source.withDates(null, first.date()))
                        .withCurrency(rupees),
                FinancialDataPoint.reported("endPrice", last.value(), ProviderUnit.PER_SHARE, last.value(),
                        Unit.INR_PER_SHARE, ReportingPeriod.pointInTime(last.date()), source).withCurrency(rupees),
                verified(totalReturn, closes),
                verified(cagr, closes),
                verified(FinancialDataPoint.calculated("periodHigh", high.value(), Unit.INR_PER_SHARE,
                        ReportingPeriod.pointInTime(high.date()), source, Formula.SELECTED_VALUE,
                        "highest close in window, on " + high.date(), List.of(close("highest close", high))), closes),
                verified(FinancialDataPoint.calculated("periodLow", low.value(), Unit.INR_PER_SHARE,
                        ReportingPeriod.pointInTime(low.date()), source, Formula.SELECTED_VALUE,
                        "lowest close in window, on " + low.date(), List.of(close("lowest close", low))), closes),
                verified(FinancialDataPoint.calculated("maxDrawdownPercent", drawdown.percent(), Unit.PERCENT, span,
                        source, Formula.DRAWDOWN_PERCENT, "largest fall from a running peak: %s on %s to %s on %s".formatted(
                                drawdown.peakValue().toPlainString(), drawdown.peakDate(),
                                drawdown.troughValue().toPlainString(), drawdown.troughDate()),
                        List.of(close("peak close", new DatedValue(drawdown.peakDate(), drawdown.peakValue())),
                                close("trough close", new DatedValue(drawdown.troughDate(), drawdown.troughValue())))),
                        closes),
                drawdown.peakDate(),
                drawdown.troughDate(),
                FinancialCalculator.calendarYearReturns(window).stream()
                        .map(r -> new CalendarYearReturn(r.year(), r.fromDate(), r.toDate(), r.startValue(),
                                r.endValue().setScale(2, RoundingMode.HALF_UP), r.returnPercent(), r.yearToDate()))
                        .toList(),
                history.basis(),
                withWindowNote(history.provenance(), requestedYears, exactYears, last.date()));
    }

    private static CalculationInput close(String name, DatedValue point) {
        return new CalculationInput(name, PRICE_FIELD, point.value(), Unit.INR_PER_SHARE,
                ReportingPeriod.pointInTime(point.date()).label());
    }

    private static FinancialDataPoint verified(FinancialDataPoint point, CalculationVerifier.SourceValues closes) {
        return CalculationVerifier.verify(point, closes);
    }

    private DataProvenance withWindowNote(DataProvenance provenance, int requestedYears, BigDecimal actualYears,
                                          LocalDate asOf) {
        String note = provenance.note();
        if (actualYears.compareTo(BigDecimal.valueOf(requestedYears).subtract(new BigDecimal("0.1"))) < 0) {
            note = note + " Requested %d years but the price history only covers %s years (likely a more recent listing)."
                    .formatted(requestedYears, actualYears.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        return new DataProvenance(provenance.source(), provenance.sourceType(), provenance.freshness(),
                provenance.retrievedAt(), provenance.publishedAt(), asOf, provenance.periodEnd(), note);
    }

    private ResearchDataUnavailableException tooShort(PriceHistory history, int years) {
        return new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                "Too little price history for '%s' to measure performance over %d year(s)"
                        .formatted(history.symbol(), years));
    }
}
