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

    /**
     * Earnings yield: 100 / P/E, the percentage of the price that trailing earnings represent.
     * Only for a positive P/E: a loss-making company has no meaningful P/E to invert.
     */
    public static Optional<BigDecimal> earningsYieldPercent(BigDecimal priceToEarnings) {
        if (priceToEarnings == null || priceToEarnings.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(HUNDRED.divide(priceToEarnings, MATH).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * sum(numerators) / sum(denominators) as a multiple, e.g. operating cash flow over net profit across
     * the same fiscal years. Needs the same number of each and a positive denominator sum.
     */
    public static Optional<BigDecimal> sumRatio(List<BigDecimal> numerators, List<BigDecimal> denominators) {
        if (numerators == null || denominators == null || numerators.isEmpty()
                || numerators.size() != denominators.size()
                // not contains(null): immutable lists throw on it rather than answering
                || numerators.stream().anyMatch(java.util.Objects::isNull)
                || denominators.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        return multiple(numerators.stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                denominators.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** The arithmetic mean, to 2 decimal places; empty for no values or a missing one. */
    public static Optional<BigDecimal> mean(List<BigDecimal> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        return Optional.of(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP));
    }

    /** The median, to 2 decimal places: the mean of the two middle values for an even count; empty for none. */
    public static Optional<BigDecimal> median(List<BigDecimal> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        BigDecimal median = sorted.size() % 2 == 1 ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).divide(TWO, 2, RoundingMode.HALF_UP);
        return Optional.of(median.setScale(2, RoundingMode.HALF_UP));
    }

    /** The lowest value; empty for none or a missing one. */
    public static Optional<BigDecimal> minimum(List<BigDecimal> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        return values.stream().min(BigDecimal::compareTo);
    }

    /** The highest value; empty for none or a missing one. */
    public static Optional<BigDecimal> maximum(List<BigDecimal> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        return values.stream().max(BigDecimal::compareTo);
    }

    /**
     * Where {@code value} sits among {@code values} counted from the highest: 1 for the highest, and one more than
     * the number of values strictly above it, so equal values share a rank. Empty when {@code value} is not one of
     * them.
     */
    public static Optional<Integer> rankFromHighest(List<BigDecimal> values, BigDecimal value) {
        if (values == null || value == null || values.stream().noneMatch(v -> v != null && v.compareTo(value) == 0)) {
            return Optional.empty();
        }
        return Optional.of(1 + (int) values.stream().filter(v -> v != null && v.compareTo(value) > 0).count());
    }

    /**
     * Dividends paid as a percentage of the same year's operating cash flow. The cash-flow statement
     * reports dividends as an outflow, so {@code dividendsPaid} must be zero or negative; a positive figure
     * is not what the field is understood to mean and gives no result. Needs positive operating cash flow.
     */
    public static Optional<BigDecimal> dividendsToOperatingCashFlowPercent(BigDecimal dividendsPaid,
                                                                           BigDecimal operatingCashFlow) {
        if (dividendsPaid == null || dividendsPaid.signum() > 0) {
            return Optional.empty();
        }
        return ratioPercent(dividendsPaid.negate(), operatingCashFlow);
    }

    /**
     * The number of equity shares: paid-up equity capital (in crore) x 1,00,00,000 / face value per share.
     * Empty unless the face value is positive.
     */
    public static Optional<BigDecimal> shareCount(BigDecimal paidUpCapitalCrore, BigDecimal faceValue) {
        if (paidUpCapitalCrore == null || faceValue == null || faceValue.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(paidUpCapitalCrore.multiply(FinancialUnits.RUPEES_PER_CRORE)
                .divide(faceValue, 0, RoundingMode.HALF_UP));
    }

    /** The sum of statement lines, e.g. EBIT as profit before tax plus finance costs. Empty if any is missing. */
    public static Optional<BigDecimal> sum(List<BigDecimal> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        return Optional.of(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * Operating profit from an Ind-AS statement of profit and loss: revenue from operations less the
     * expenses other than finance costs, so before interest and without other income or exceptional items.
     */
    public static Optional<BigDecimal> operatingProfit(BigDecimal revenue, BigDecimal totalExpenses,
                                                       BigDecimal financeCosts) {
        if (revenue == null || totalExpenses == null || financeCosts == null) {
            return Optional.empty();
        }
        return Optional.of(revenue.subtract(totalExpenses.subtract(financeCosts)));
    }

    /** Capital employed: total assets minus current liabilities, at the same balance-sheet date. */
    public static Optional<BigDecimal> capitalEmployed(BigDecimal totalAssets, BigDecimal currentLiabilities) {
        if (totalAssets == null || currentLiabilities == null) {
            return Optional.empty();
        }
        return Optional.of(totalAssets.subtract(currentLiabilities));
    }

    /**
     * EBIT over average capital employed (opening and closing), or closing when no opening balance is
     * available - the same rule as ROE. Needs positive capital employed.
     */
    public static Optional<BigDecimal> returnOnCapitalEmployedPercent(BigDecimal ebit,
                                                                      BigDecimal closingAssets,
                                                                      BigDecimal closingCurrentLiabilities,
                                                                      BigDecimal openingAssets,
                                                                      BigDecimal openingCurrentLiabilities) {
        Optional<BigDecimal> closing = capitalEmployed(closingAssets, closingCurrentLiabilities);
        if (closing.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal opening = capitalEmployed(openingAssets, openingCurrentLiabilities).orElse(null);
        BigDecimal basis = averageOrClosing(opening, closing.get());
        return basis != null && basis.signum() > 0 ? ratioPercent(ebit, basis) : Optional.empty();
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
