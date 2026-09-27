package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalculatedFinancials;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.HeadlineFinancials;
import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.QuarterlyResult;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.Unit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Calculates derived financial metrics from a provider's reported figures:
 * TTM net margin, quarter-on-quarter growth, per-year YoY growth, margins,
 * ROE, ROA and debt-to-equity, and multi-year CAGR. Provider-agnostic - it
 * only reads {@link ReportedFinancials}.
 *
 * Each result is a CALCULATED {@link FinancialDataPoint} stating its formula
 * and the provider fields it was calculated from, or an UNAVAILABLE one
 * stating why it could not be calculated.
 *
 * It also cross-checks figures that should agree. A disagreement becomes a
 * DATA_CONFLICT naming the fields involved: both values stay visible,
 * neither is chosen, and nothing further may be concluded from them.
 */
@Service
public class FinancialMetricsService {

    /** A calculated and a provider-published margin further apart than this suggests different definitions. */
    static final BigDecimal MARGIN_TOLERANCE_PP = BigDecimal.ONE;
    /** Same-period figures from two provider fields should agree closely; beyond this they conflict. */
    static final BigDecimal SAME_PERIOD_TOLERANCE_PERCENT = BigDecimal.TEN;
    /** TTM and latest-fiscal-year revenue normally differ by at most a quarter's growth; far beyond that means different definitions. */
    static final BigDecimal REVENUE_DEFINITION_TOLERANCE_PERCENT = BigDecimal.valueOf(25);

    public FinancialSummary summarize(ReportedFinancials reported) {
        HeadlineFinancials headline = reported.headline();
        List<AnnualFinancials> annual = reported.annualHistory();

        CalculatedFinancials calculated = new CalculatedFinancials(
                ttmNetMargin(headline),
                quarterOnQuarter(reported.recentQuarters(), QuarterlyResult::revenue, "revenue",
                        "latestQuarterRevenueGrowthQoqPercent"),
                quarterOnQuarter(reported.recentQuarters(), QuarterlyResult::netProfit, "net profit",
                        "latestQuarterNetProfitGrowthQoqPercent"),
                cagr(annual, AnnualFinancials::revenue, "revenue", "revenueCagrPercent"),
                cagr(annual, AnnualFinancials::netProfit, "net profit", "netProfitCagrPercent"),
                annualRatios(annual));

        List<DataGap> gaps = new ArrayList<>(reported.dataGaps());
        if (!reported.latestReportedQuarter().known()) {
            gaps.add(new DataGap("Financials", "latestReportedQuarter",
                    "The provider did not state the latest reported quarter; the reporting period is UNKNOWN"));
        }
        gaps.addAll(gapsFrom("Financials (reported)", headlinePoints(headline)));
        gaps.addAll(gapsFrom("Financials (calculated)", calculatedPoints(calculated)));

        return new FinancialSummary(reported, calculated, List.copyOf(gaps),
                crossChecks(headline, reported.recentQuarters(), annual, calculated));
    }

    private FinancialDataPoint ttmNetMargin(HeadlineFinancials headline) {
        String metric = "netProfitMarginTtmPercent";
        FinancialDataPoint revenue = headline.revenue();
        FinancialDataPoint profit = headline.netProfit();
        ReportingPeriod period = revenue.period();
        Optional<String> mismatch = inputMismatch(revenue, profit);
        if (mismatch.isPresent()) {
            return FinancialDataPoint.unavailable(metric, Unit.PERCENT, period, revenue.source(),
                    "TTM net profit margin not calculated: " + mismatch.get());
        }
        return FinancialCalculator.profitMarginPercent(profit.value(), revenue.value())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENT, period, revenue.source(),
                        "net profit / revenue x 100, both %s".formatted(period.label()), inputs(profit, revenue)))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.PERCENT, period, revenue.source(),
                        "TTM net profit margin not calculated: revenue is not positive"));
    }

    private FinancialDataPoint quarterOnQuarter(List<QuarterlyResult> quarters,
                                                Function<QuarterlyResult, FinancialDataPoint> metric, String name,
                                                String metricName) {
        if (quarters.size() < 2) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, ReportingPeriod.unknown(), null,
                    "Quarter-on-quarter %s growth needs two reported quarters; the provider supplied %d"
                            .formatted(name, quarters.size()));
        }
        QuarterlyResult previous = quarters.get(quarters.size() - 2);
        QuarterlyResult latest = quarters.get(quarters.size() - 1);
        FinancialDataPoint before = metric.apply(previous);
        FinancialDataPoint after = metric.apply(latest);
        Optional<String> problem = inputMismatch(before, after)
                .or(() -> consecutive(previous.period(), latest.period())
                        ? Optional.empty()
                        : Optional.of("%s and %s are not consecutive quarters".formatted(
                                previous.period().label(), latest.period().label())));
        if (problem.isPresent()) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, latest.period(), after.source(),
                    "Quarter-on-quarter %s growth not calculated: %s".formatted(name, problem.get()));
        }
        return FinancialCalculator.growthPercent(before.value(), after.value())
                .map(value -> FinancialDataPoint.calculated(metricName, value, Unit.PERCENT, latest.period(),
                        after.source(), "(%s %s / %s %s - 1) x 100".formatted(latest.period().label(), name,
                                previous.period().label(), name), inputs(before, after)))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, latest.period(),
                        after.source(), "Quarter-on-quarter %s growth not calculated: the earlier quarter's value is "
                                .formatted(name) + "not positive"));
    }

    private FinancialDataPoint cagr(List<AnnualFinancials> annual, Function<AnnualFinancials, FinancialDataPoint> metric,
                                    String name, String metricName) {
        List<AnnualFinancials> usable = annual.stream().filter(year -> metric.apply(year).available()).toList();
        if (usable.size() < 2) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, ReportingPeriod.unknown(), null,
                    "%s CAGR needs at least two fiscal years of %s; %d available"
                            .formatted(capitalise(name), name, usable.size()));
        }
        AnnualFinancials first = usable.get(0);
        AnnualFinancials last = usable.get(usable.size() - 1);
        FinancialDataPoint start = metric.apply(first);
        FinancialDataPoint end = metric.apply(last);
        BigDecimal years = fiscalYearsBetween(first.period(), last.period());
        ReportingPeriod span = new ReportingPeriod(PeriodType.MULTI_YEAR, first.period().start(), last.period().end(),
                "%s to %s (%s years)".formatted(first.period().label(), last.period().label(), years.toPlainString()),
                null, null);
        Optional<String> mismatch = inputMismatch(start, end);
        if (mismatch.isPresent()) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, span, end.source(),
                    "%s CAGR not calculated: %s".formatted(capitalise(name), mismatch.get()));
        }
        return FinancialCalculator.cagrPercent(start.value(), end.value(), years)
                .map(value -> FinancialDataPoint.calculated(metricName, value, Unit.PERCENT, span, end.source(),
                        "((%s %s / %s %s)^(1/%s) - 1) x 100".formatted(last.period().label(), name,
                                first.period().label(), name, years.toPlainString()), inputs(start, end)))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, span, end.source(),
                        "%s CAGR is undefined: the start or end value is not positive".formatted(capitalise(name))));
    }

    private List<AnnualRatios> annualRatios(List<AnnualFinancials> annual) {
        List<AnnualRatios> ratios = new ArrayList<>();
        for (int i = 0; i < annual.size(); i++) {
            AnnualFinancials year = annual.get(i);
            AnnualFinancials prior = i > 0 && consecutiveYears(annual.get(i - 1).period(), year.period())
                    ? annual.get(i - 1) : null;
            ratios.add(new AnnualRatios(
                    year.period(),
                    yoy(prior, year, AnnualFinancials::revenue, "revenue", "revenueGrowthYoyPercent"),
                    yoy(prior, year, AnnualFinancials::netProfit, "net profit", "netProfitGrowthYoyPercent"),
                    margin(year, year.netProfit(), "net profit margin", "net profit", "netProfitMarginPercent"),
                    margin(year, year.operatingIncome(), "operating margin", "operating income", "operatingMarginPercent"),
                    averageBalanceReturn(prior, year, AnnualFinancials::shareholdersEquity,
                            "return on equity", "shareholders' equity", "returnOnEquityPercent"),
                    averageBalanceReturn(prior, year, AnnualFinancials::totalAssets,
                            "return on assets", "total assets", "returnOnAssetsPercent"),
                    debtToEquity(year)));
        }
        return List.copyOf(ratios);
    }

    private FinancialDataPoint yoy(AnnualFinancials prior, AnnualFinancials year,
                                   Function<AnnualFinancials, FinancialDataPoint> metric, String name, String metricName) {
        FinancialDataPoint current = metric.apply(year);
        if (prior == null) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(), current.source(),
                    "No prior fiscal year in the data to measure %s growth against".formatted(name));
        }
        FinancialDataPoint previous = metric.apply(prior);
        Optional<String> mismatch = inputMismatch(previous, current);
        if (mismatch.isPresent()) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(), current.source(),
                    "YoY %s growth not calculated: %s".formatted(name, mismatch.get()));
        }
        return FinancialCalculator.yoyGrowthPercent(previous.value(), current.value())
                .map(value -> FinancialDataPoint.calculated(metricName, value, Unit.PERCENT, year.period(),
                        current.source(), "(%s %s / %s %s - 1) x 100".formatted(year.period().label(), name,
                                prior.period().label(), name), inputs(previous, current)))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(),
                        current.source(), "YoY %s growth not calculated: the prior year's value is not positive"
                                .formatted(name)));
    }

    private FinancialDataPoint margin(AnnualFinancials year, FinancialDataPoint numerator, String ratio, String name,
                                      String metricName) {
        Optional<String> mismatch = inputMismatch(year.revenue(), numerator);
        if (mismatch.isPresent()) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(), year.revenue().source(),
                    "%s not calculated: %s".formatted(capitalise(ratio), mismatch.get()));
        }
        return FinancialCalculator.ratioPercent(numerator.value(), year.revenue().value())
                .map(value -> FinancialDataPoint.calculated(metricName, value, Unit.PERCENT, year.period(),
                        numerator.source(), "%s %s / %s revenue x 100".formatted(year.period().label(), name,
                                year.period().label()), inputs(numerator, year.revenue())))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(),
                        numerator.source(), "%s not calculated: revenue is not positive".formatted(capitalise(ratio))));
    }

    private FinancialDataPoint averageBalanceReturn(AnnualFinancials prior, AnnualFinancials year,
                                                    Function<AnnualFinancials, FinancialDataPoint> balance,
                                                    String ratio, String balanceName, String metricName) {
        FinancialDataPoint closing = balance.apply(year);
        Optional<String> mismatch = inputMismatch(year.netProfit(), closing);
        if (mismatch.isPresent()) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(), closing.source(),
                    "%s not calculated: %s".formatted(capitalise(ratio), mismatch.get()));
        }
        FinancialDataPoint opening = prior != null ? balance.apply(prior) : null;
        boolean average = opening != null && opening.available() && opening.unit() == closing.unit();
        String basis = average
                ? "average %s (%s and %s closing)".formatted(balanceName, prior.period().label(), year.period().label())
                : "closing %s at %s (no prior-year balance available, so not averaged)"
                        .formatted(balanceName, year.period().label());
        BigDecimal openingValue = average ? opening.value() : null;
        Optional<BigDecimal> value = balanceName.contains("equity")
                ? FinancialCalculator.returnOnEquityPercent(year.netProfit().value(), openingValue, closing.value())
                : FinancialCalculator.returnOnAssetsPercent(year.netProfit().value(), openingValue, closing.value());
        List<String> inputFields = average ? inputs(year.netProfit(), closing, opening) : inputs(year.netProfit(), closing);
        return value
                .map(result -> FinancialDataPoint.calculated(metricName, result, Unit.PERCENT, year.period(),
                        closing.source(), "%s net profit / %s x 100".formatted(year.period().label(), basis), inputFields))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(), closing.source(),
                        "%s not calculated: %s is not positive".formatted(capitalise(ratio), balanceName)));
    }

    /** Both balances at the same fiscal-year end, so no period mismatch; expressed as a multiple, never a percentage. */
    private FinancialDataPoint debtToEquity(AnnualFinancials year) {
        String metric = "debtToEquityMultiple";
        FinancialDataPoint debt = year.totalDebt();
        FinancialDataPoint equity = year.shareholdersEquity();
        Optional<String> mismatch = inputMismatch(debt, equity);
        if (mismatch.isPresent()) {
            return FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, year.period(), equity.source(),
                    "Debt-to-equity not calculated: " + mismatch.get());
        }
        return FinancialCalculator.debtToEquityMultiple(debt.value(), equity.value())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.MULTIPLE, year.period(), debt.source(),
                        "%s closing total debt / %s closing shareholders' equity (a multiple, not a percentage)"
                                .formatted(year.period().label(), year.period().label()), inputs(debt, equity)))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, year.period(), debt.source(),
                        "Debt-to-equity not calculated: shareholders' equity is not positive"));
    }

    /** Cross-checks that surface definition or unit problems instead of letting two conflicting numbers pass silently. */
    private List<DataQualityIssue> crossChecks(HeadlineFinancials headline, List<QuarterlyResult> quarters,
                                               List<AnnualFinancials> annual, CalculatedFinancials calculated) {
        List<DataQualityIssue> issues = new ArrayList<>();

        FinancialDataPoint calculatedMargin = calculated.netProfitMarginTtmPercent();
        FinancialDataPoint reportedMargin = headline.netProfitMarginPercent();
        if (calculatedMargin.available() && reportedMargin.available()
                && calculatedMargin.value().subtract(reportedMargin.value()).abs().compareTo(MARGIN_TOLERANCE_PP) > 0) {
            issues.add(DataQualityIssue.conflict(("Calculated TTM net profit margin (%s) differs from Yahoo Finance's "
                    + "published margin (%s) by more than %s percentage point. The two use different profit or revenue "
                    + "definitions; neither is chosen.").formatted(calculatedMargin.display(), reportedMargin.display(),
                    MARGIN_TOLERANCE_PP), inputs(calculatedMargin, reportedMargin)));
        }

        sumOfFourQuarters(quarters, headline.revenue().period(), QuarterlyResult::revenue)
                .ifPresent(sum -> samePeriodConflict(headline.revenue(), sum, "revenue").ifPresent(issues::add));
        sumOfFourQuarters(quarters, headline.netProfit().period(), QuarterlyResult::netProfit)
                .ifPresent(sum -> samePeriodConflict(headline.netProfit(), sum, "net profit").ifPresent(issues::add));

        FinancialDataPoint ttmRevenue = headline.revenue();
        Optional<FinancialDataPoint> latestAnnualRevenue = annual.isEmpty()
                ? Optional.empty() : Optional.of(annual.get(annual.size() - 1).revenue());
        latestAnnualRevenue
                .filter(fy -> fy.available() && ttmRevenue.available() && fy.unit() == ttmRevenue.unit()
                        && fy.value().signum() > 0)
                .filter(fy -> FinancialDataValidator.divergencePercent(ttmRevenue.value(), fy.value())
                        .compareTo(REVENUE_DEFINITION_TOLERANCE_PERCENT) > 0)
                .ifPresent(fy -> issues.add(DataQualityIssue.conflict(("TTM revenue (%s, %s, %s) and %s annual revenue "
                        + "(%s, %s) differ by %s%%, more than one quarter's growth could explain. Yahoo Finance uses "
                        + "different revenue definitions for these fields. Neither is chosen; do not compare them or "
                        + "derive growth between them.")
                        .formatted(ttmRevenue.display(), ttmRevenue.period().label(), ttmRevenue.source().sourceField(),
                                fy.period().label(), fy.display(), fy.source().sourceField(),
                                FinancialDataValidator.divergencePercent(ttmRevenue.value(), fy.value()).toPlainString()),
                        inputs(ttmRevenue, fy))));
        return List.copyOf(issues);
    }

    private record QuarterSum(BigDecimal value, Unit unit, List<FinancialDataPoint> points, String labels) {
    }

    /** The four reported quarters making up exactly the TTM period, if they are all present and consecutive. */
    private Optional<QuarterSum> sumOfFourQuarters(List<QuarterlyResult> quarters, ReportingPeriod ttm,
                                                   Function<QuarterlyResult, FinancialDataPoint> metric) {
        if (!ttm.known() || ttm.type() != PeriodType.TRAILING_TWELVE_MONTHS || quarters.size() < 4) {
            return Optional.empty();
        }
        List<QuarterlyResult> last4 = quarters.subList(quarters.size() - 4, quarters.size());
        if (!last4.get(3).period().end().equals(ttm.end()) || !last4.get(0).period().start().equals(ttm.start())) {
            return Optional.empty();
        }
        for (int i = 1; i < 4; i++) {
            if (!consecutive(last4.get(i - 1).period(), last4.get(i).period())) {
                return Optional.empty();
            }
        }
        List<FinancialDataPoint> points = last4.stream().map(metric).toList();
        if (points.stream().anyMatch(p -> !p.available() || p.unit() != points.get(0).unit())) {
            return Optional.empty();
        }
        BigDecimal sum = points.stream().map(FinancialDataPoint::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        String labels = last4.get(0).period().label() + " to " + last4.get(3).period().label();
        return Optional.of(new QuarterSum(sum, points.get(0).unit(), points, labels));
    }

    private Optional<DataQualityIssue> samePeriodConflict(FinancialDataPoint ttm, QuarterSum quarters, String name) {
        if (!ttm.available() || ttm.unit() != quarters.unit() || quarters.value().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal gap = FinancialDataValidator.divergencePercent(ttm.value(), quarters.value());
        if (gap.compareTo(SAME_PERIOD_TOLERANCE_PERCENT) <= 0) {
            return Optional.empty();
        }
        List<String> fields = new ArrayList<>(ttm.dependsOnFields());
        quarters.points().forEach(p -> fields.addAll(p.dependsOnFields()));
        return Optional.of(DataQualityIssue.conflict(("TTM %s (%s, %s) and the sum of the four reported quarters %s "
                + "(%s, %s) cover the same period but differ by %s%%. Yahoo Finance uses different definitions for "
                + "these fields. Neither is chosen, and no conclusion should be drawn from either.")
                .formatted(name, ttm.display(), ttm.source().sourceField(), quarters.labels(),
                        com.ashish.stockresearch.research.calc.FinancialUnits.display(
                                quarters.value().setScale(2, RoundingMode.HALF_UP), quarters.unit()),
                        quarters.points().get(0).source().sourceField(), gap.toPlainString()),
                List.copyOf(new LinkedHashSet<>(fields))));
    }

    /** Why two inputs cannot be combined, if they cannot: missing, or in different units. */
    private Optional<String> inputMismatch(FinancialDataPoint a, FinancialDataPoint b) {
        if (a == null || !a.available()) {
            return Optional.of(a == null ? "input missing" : a.unavailableReason());
        }
        if (b == null || !b.available()) {
            return Optional.of(b == null ? "input missing" : b.unavailableReason());
        }
        if (a.unit() != b.unit()) {
            return Optional.of("inputs are in different units (%s vs %s)".formatted(a.unit(), b.unit()));
        }
        return Optional.empty();
    }

    /** The distinct provider fields a calculation depends on. */
    private static List<String> inputs(FinancialDataPoint... points) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (FinancialDataPoint point : points) {
            if (point != null) {
                fields.addAll(point.dependsOnFields());
            }
        }
        return List.copyOf(fields);
    }

    private boolean consecutive(ReportingPeriod earlier, ReportingPeriod later) {
        return earlier.known() && later.known() && later.start() != null
                && earlier.end().plusDays(1).equals(later.start());
    }

    private boolean consecutiveYears(ReportingPeriod earlier, ReportingPeriod later) {
        return earlier.known() && later.known() && earlier.end().plusYears(1).equals(later.end());
    }

    private BigDecimal fiscalYearsBetween(ReportingPeriod first, ReportingPeriod last) {
        if (first.fiscalYear() != null && last.fiscalYear() != null) {
            return BigDecimal.valueOf(last.fiscalYear() - first.fiscalYear());
        }
        return FinancialCalculator.yearsBetween(first.end(), last.end()).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, FinancialDataPoint> headlinePoints(HeadlineFinancials h) {
        Map<String, FinancialDataPoint> points = new LinkedHashMap<>();
        for (FinancialDataPoint point : List.of(h.revenue(), h.netProfit(), h.ebitda(), h.freeCashFlow(), h.totalDebt(),
                h.totalCash(), h.operatingMarginPercent(), h.netProfitMarginPercent(), h.returnOnEquityPercent(),
                h.returnOnAssetsPercent(), h.debtToEquityPercent(), h.quarterlyRevenueGrowthYoyPercent(),
                h.quarterlyEarningsGrowthYoyPercent(), h.returnOnCapitalEmployedPercent())) {
            points.put(point.metric(), point);
        }
        return points;
    }

    private static Map<String, FinancialDataPoint> calculatedPoints(CalculatedFinancials c) {
        Map<String, FinancialDataPoint> points = new LinkedHashMap<>();
        for (FinancialDataPoint point : List.of(c.netProfitMarginTtmPercent(), c.latestQuarterRevenueGrowthQoqPercent(),
                c.latestQuarterNetProfitGrowthQoqPercent(), c.revenueCagrPercent(), c.netProfitCagrPercent())) {
            points.put(point.metric(), point);
        }
        return points;
    }

    private static List<DataGap> gapsFrom(String area, Map<String, FinancialDataPoint> points) {
        return points.entrySet().stream()
                .filter(entry -> !entry.getValue().available())
                .map(entry -> new DataGap(area, entry.getKey(), entry.getValue().unavailableReason()))
                .toList();
    }

    private static String capitalise(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
