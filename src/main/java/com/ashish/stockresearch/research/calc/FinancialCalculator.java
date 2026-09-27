package com.ashish.stockresearch.research.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every derived financial number in the application is calculated here, in
 * Java, and handed to the model as a finished result. The model explains
 * these figures; it never recomputes them.
 *
 * Each method returns empty when its inputs cannot support the result
 * (missing value, zero or negative base, zero-length period). Empty means
 * "not computable" and must be reported as such - never as zero.
 *
 * All percentages are rounded half-up to 2 decimal places.
 */
public final class FinancialCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");
    private static final MathContext MATH = MathContext.DECIMAL64;

    public record DatedValue(LocalDate date, BigDecimal value) {
    }

    /**
     * Largest peak-to-trough fall.
     *
     * @param percent negative percentage (-25.00 = a 25% fall); 0.00 if the series never fell
     */
    public record Drawdown(BigDecimal percent, LocalDate peakDate, BigDecimal peakValue,
                           LocalDate troughDate, BigDecimal troughValue) {
    }

    /**
     * One calendar year's return, measured from the previous year's last
     * close to this year's last close.
     *
     * @param returnPercent null for the first year of a series (no prior year-end close)
     * @param yearToDate    true when the year is not finished in the series, so the return is
     *                      partial and must not be presented as a full-year figure
     */
    public record AnnualReturn(int year, LocalDate fromDate, LocalDate toDate, BigDecimal startValue,
                               BigDecimal endValue, BigDecimal returnPercent, boolean yearToDate) {
    }

    private FinancialCalculator() {
    }

    /** Period-on-period growth, e.g. year-on-year: (current / previous - 1) x 100. Needs a positive base. */
    public static Optional<BigDecimal> growthPercent(BigDecimal previous, BigDecimal current) {
        if (previous == null || current == null || previous.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(current.divide(previous, MATH).subtract(BigDecimal.ONE)
                .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP));
    }

    /** Year-on-year growth; an alias that names the intent at call sites. */
    public static Optional<BigDecimal> yoyGrowthPercent(BigDecimal previousYear, BigDecimal currentYear) {
        return growthPercent(previousYear, currentYear);
    }

    /** Total return between two prices. Same arithmetic as growth, kept separate for readability. */
    public static Optional<BigDecimal> totalReturnPercent(BigDecimal start, BigDecimal end) {
        return growthPercent(start, end);
    }

    /**
     * Compound annual growth rate: ((end / start)^(1 / years) - 1) x 100.
     * Undefined when either value is non-positive (a sign change has no
     * meaningful compound rate) or when {@code years} is not positive.
     */
    public static Optional<BigDecimal> cagrPercent(BigDecimal start, BigDecimal end, BigDecimal years) {
        if (start == null || end == null || years == null
                || start.signum() <= 0 || end.signum() <= 0 || years.signum() <= 0) {
            return Optional.empty();
        }
        double growth = end.divide(start, MATH).doubleValue();
        double cagr = Math.pow(growth, 1.0 / years.doubleValue()) - 1.0;
        if (!Double.isFinite(cagr)) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(cagr).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP));
    }

    /** numerator / denominator x 100, for a positive denominator. */
    public static Optional<BigDecimal> ratioPercent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(numerator.divide(denominator, MATH).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP));
    }

    /** numerator / denominator as a multiple (0.07 = 0.07x), for a positive denominator. */
    public static Optional<BigDecimal> multiple(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(numerator.divide(denominator, 2, RoundingMode.HALF_UP));
    }

    /** Total debt / shareholders' equity as a multiple. Both balances must be at the same date. */
    public static Optional<BigDecimal> debtToEquityMultiple(BigDecimal totalDebt, BigDecimal equity) {
        return multiple(totalDebt, equity);
    }

    /** Net profit / revenue. Both must cover the same period. */
    public static Optional<BigDecimal> profitMarginPercent(BigDecimal netProfit, BigDecimal revenue) {
        return ratioPercent(netProfit, revenue);
    }

    /** Operating income / revenue. Both must cover the same period. */
    public static Optional<BigDecimal> operatingMarginPercent(BigDecimal operatingIncome, BigDecimal revenue) {
        return ratioPercent(operatingIncome, revenue);
    }

    /**
     * Net income over average shareholders' equity (opening and closing).
     * When no opening balance is available the closing balance is used; the
     * caller must say which basis applied.
     */
    public static Optional<BigDecimal> returnOnEquityPercent(BigDecimal netIncome, BigDecimal openingEquity,
                                                             BigDecimal closingEquity) {
        return ratioPercent(netIncome, averageOrClosing(openingEquity, closingEquity));
    }

    /** Net income over average total assets, with the same opening/closing rule as ROE. */
    public static Optional<BigDecimal> returnOnAssetsPercent(BigDecimal netIncome, BigDecimal openingAssets,
                                                             BigDecimal closingAssets) {
        return ratioPercent(netIncome, averageOrClosing(openingAssets, closingAssets));
    }

    /** Elapsed years between two dates (days / 365.25), to 4 decimal places. */
    public static BigDecimal yearsBetween(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end);
        return BigDecimal.valueOf(days).divide(DAYS_PER_YEAR, 4, RoundingMode.HALF_UP);
    }

    /** Largest fall from a running peak. Empty for an empty series. */
    public static Optional<Drawdown> maxDrawdown(List<DatedValue> series) {
        if (series == null || series.isEmpty()) {
            return Optional.empty();
        }
        DatedValue peak = series.get(0);
        Drawdown worst = new Drawdown(BigDecimal.ZERO.setScale(2), peak.date(), peak.value(), peak.date(), peak.value());
        for (DatedValue point : series) {
            if (point.value().compareTo(peak.value()) > 0) {
                peak = point;
            }
            BigDecimal fall = point.value().divide(peak.value(), MATH).subtract(BigDecimal.ONE).multiply(HUNDRED);
            if (fall.compareTo(worst.percent()) < 0) {
                worst = new Drawdown(fall.setScale(2, RoundingMode.HALF_UP), peak.date(), peak.value(),
                        point.date(), point.value());
            }
        }
        return Optional.of(worst);
    }

    /**
     * Calendar-year returns from a date-ordered series, using each year's
     * last observation. The first year has no prior year-end, so its return
     * is null. The final year is flagged year-to-date when the series stops
     * before the last trading week of December.
     */
    public static List<AnnualReturn> calendarYearReturns(List<DatedValue> series) {
        Map<Integer, DatedValue> lastByYear = new LinkedHashMap<>();
        for (DatedValue point : series) {
            lastByYear.put(point.date().getYear(), point);
        }
        List<AnnualReturn> returns = new ArrayList<>();
        DatedValue previous = null;
        for (Map.Entry<Integer, DatedValue> entry : lastByYear.entrySet()) {
            DatedValue close = entry.getValue();
            boolean yearToDate = !(close.date().getMonth() == Month.DECEMBER && close.date().getDayOfMonth() >= 24);
            returns.add(new AnnualReturn(entry.getKey(),
                    previous == null ? null : previous.date(),
                    close.date(),
                    previous == null ? null : previous.value(),
                    close.value(),
                    previous == null ? null : growthPercent(previous.value(), close.value()).orElse(null),
                    yearToDate));
            previous = close;
        }
        return List.copyOf(returns);
    }

    private static BigDecimal averageOrClosing(BigDecimal opening, BigDecimal closing) {
        if (closing == null) {
            return null;
        }
        return opening == null ? closing : opening.add(closing).divide(TWO, MATH);
    }
}
