package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.ashish.stockresearch.research.model.AnnualCrossCheckFigures;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalculatedFinancials;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.HeadlineFinancials;
import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.QuarterlyResult;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
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
 * Turns a provider's reported figures into a {@link FinancialSummary}, in
 * four steps that always run in this order:
 *
 * <ol>
 *   <li><b>Source checks.</b> Figures that should agree are compared - TTM against the sum of its
 *       four quarters, TTM against the latest fiscal year, and each fiscal year against the
 *       provider's second annual feed. A disagreement is a DATA_CONFLICT naming the fields involved.
 *       Figures in different currencies are never compared; that is reported instead.</li>
 *   <li><b>Conflict marking.</b> Every reported value that depends on a conflicting field is marked
 *       DATA_CONFLICT. Its value stays visible but it is no longer usable.</li>
 *   <li><b>Calculation.</b> TTM net margin, quarter-on-quarter growth, per-year YoY growth, margins,
 *       ROE, ROA, debt-to-equity and CAGR are calculated only from VALID inputs. A metric with a
 *       DATA_CONFLICT, INVALID or UNAVAILABLE input is not calculated; it takes that status and names
 *       the input. Each calculated value records its formula and the exact input values used. The
 *       provider's own derived ratios are then compared with the application's figures; a
 *       disagreement is a further conflict, and steps 2-3 are repeated.</li>
 *   <li><b>Verification.</b> Every calculated value is reconciled against the source values and
 *       recomputed independently ({@link CalculationVerifier}). One that does not reproduce is
 *       INVALID and reported as a CALCULATION_INVALID issue.</li>
 * </ol>
 *
 * Nothing here chooses between conflicting values, and nothing is calculated from a value that is
 * not VALID.
 */
@Service
public class FinancialMetricsService {

    /** A calculated and a provider-published margin further apart than this suggests different definitions. */
    static final BigDecimal MARGIN_TOLERANCE_PP = BigDecimal.ONE;
    /** Same-period figures from two provider fields should agree closely; beyond this they conflict. */
    static final BigDecimal SAME_PERIOD_TOLERANCE_PERCENT = BigDecimal.TEN;
    /** TTM and latest-fiscal-year revenue normally differ by at most a quarter's growth; far beyond that means different definitions. */
    static final BigDecimal REVENUE_DEFINITION_TOLERANCE_PERCENT = BigDecimal.valueOf(25);
    /** Two feeds of one provider reporting the same fiscal year's figure should match; beyond this they conflict. */
    static final BigDecimal SAME_YEAR_TOLERANCE_PERCENT = BigDecimal.valueOf(2);
    /** A provider growth rate further than this from the application's figure for the same quarters contradicts it. */
    static final BigDecimal GROWTH_TOLERANCE_PP = BigDecimal.ONE;

    public FinancialSummary summarize(ReportedFinancials reported) {
        List<DataQualityIssue> issues = new ArrayList<>(currencyChecks(reported));
        issues.addAll(sourceChecks(reported));

        ReportedFinancials marked = markConflicts(reported, issues);
        CalculatedFinancials calculated = calculate(marked);

        List<DataQualityIssue> contradictions = providerDerivedChecks(marked, calculated);
        if (!contradictions.isEmpty()) {
            issues.addAll(contradictions);
            marked = markConflicts(marked, issues);
            calculated = markConflicts(calculate(marked), issues);
        }

        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        marked.headline().points().forEach(sources::add);
        marked.recentQuarters().forEach(q -> q.points().forEach(sources::add));
        marked.annualHistory().forEach(y -> y.points().forEach(sources::add));
        CalculatedFinancials verified = calculated.map(point -> CalculationVerifier.verify(point, sources));
        verified.points().stream()
                .filter(point -> point.status() == DataStatus.INVALID)
                .forEach(point -> issues.add(DataQualityIssue.invalidCalculation(
                        "%s (%s) failed verification and is withheld. %s".formatted(point.metric(),
                                point.period().label(), point.statusReason()), point.dependsOnFields())));

        List<DataGap> gaps = new ArrayList<>(marked.dataGaps());
        if (!marked.latestReportedQuarter().known()) {
            gaps.add(new DataGap("Financials", "latestReportedQuarter",
                    "The provider did not state the latest reported quarter; the reporting period is UNKNOWN"));
        }
        gaps.addAll(gapsFrom("Financials (reported)", marked.headline().points()));
        gaps.addAll(gapsFrom("Financials (calculated)", List.of(verified.netProfitMarginTtmPercent(),
                verified.latestQuarterRevenueGrowthQoqPercent(), verified.latestQuarterNetProfitGrowthQoqPercent(),
                verified.revenueCagrPercent(), verified.netProfitCagrPercent())));

        return new FinancialSummary(marked, verified, List.copyOf(gaps), List.copyOf(issues));
    }

    // --- Step 1: source checks ----------------------------------------------------------------

    /** Figures in different currencies are never compared or combined; say so rather than skip silently. */
    private List<DataQualityIssue> currencyChecks(ReportedFinancials reported) {
        Unit headlineUnit = reported.headline().revenue().unit();
        LinkedHashSet<String> mismatched = new LinkedHashSet<>();
        List<String> fields = new ArrayList<>();
        reported.recentQuarters().stream().map(QuarterlyResult::revenue)
                .filter(p -> p.unit() != null && headlineUnit != null && p.unit() != headlineUnit)
                .findFirst().ifPresent(p -> {
                    mismatched.add("quarterly figures in " + currencyOf(p.unit()));
                    fields.add(p.source().sourceField());
                });
        reported.annualHistory().stream().map(AnnualFinancials::revenue)
                .filter(p -> p.unit() != null && headlineUnit != null && p.unit() != headlineUnit)
                .findFirst().ifPresent(p -> {
                    mismatched.add("fiscal-year figures in " + currencyOf(p.unit()));
                    fields.add(p.source().sourceField());
                });
        if (mismatched.isEmpty()) {
            return List.of();
        }
        fields.add(0, reported.headline().revenue().source().sourceField());
        fields.removeIf(java.util.Objects::isNull);
        return List.of(DataQualityIssue.warning(("The source reports these figures in different currencies: headline "
                + "(TTM) figures in %s, %s. No exchange rate is sourced, so the application never converts between "
                + "currencies: figures in different currencies are not compared, combined or used together in any "
                + "calculation.").formatted(currencyOf(headlineUnit), String.join(", ", mismatched)), fields));
    }

    private List<DataQualityIssue> sourceChecks(ReportedFinancials reported) {
        HeadlineFinancials headline = reported.headline();
        List<QuarterlyResult> quarters = reported.recentQuarters();
        List<AnnualFinancials> annual = reported.annualHistory();
        List<DataQualityIssue> issues = new ArrayList<>();

        sumOfFourQuarters(quarters, headline.revenue().period(), QuarterlyResult::revenue)
                .ifPresent(sum -> samePeriodConflict(headline.revenue(), sum, "revenue").ifPresent(issues::add));
        sumOfFourQuarters(quarters, headline.netProfit().period(), QuarterlyResult::netProfit)
                .ifPresent(sum -> samePeriodConflict(headline.netProfit(), sum, "net profit").ifPresent(issues::add));

        FinancialDataPoint ttmRevenue = headline.revenue();
        Optional<FinancialDataPoint> latestAnnualRevenue = annual.isEmpty()
                ? Optional.empty() : Optional.of(annual.get(annual.size() - 1).revenue());
        latestAnnualRevenue
                .filter(fy -> fy.value() != null && ttmRevenue.value() != null && fy.unit() == ttmRevenue.unit()
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
                        List.of(ttmRevenue.source().sourceField(), fy.source().sourceField()))));

        sameYearConflicts(annual, reported.annualCrossCheckFigures(), AnnualFinancials::revenue,
                AnnualCrossCheckFigures::revenue, "revenue").ifPresent(issues::add);
        sameYearConflicts(annual, reported.annualCrossCheckFigures(), AnnualFinancials::netProfit,
                AnnualCrossCheckFigures::netProfit, "net profit").ifPresent(issues::add);
        return issues;
    }

    private record QuarterSum(BigDecimal value, Unit unit, List<FinancialDataPoint> points, String labels) {
    }

    /** The four reported quarters making up exactly the TTM period, if they are all present, consecutive and in one unit. */
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
        if (points.stream().anyMatch(p -> p.value() == null || p.unit() != points.get(0).unit())) {
            return Optional.empty();
        }
        BigDecimal sum = points.stream().map(FinancialDataPoint::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        String labels = last4.get(0).period().label() + " to " + last4.get(3).period().label();
        return Optional.of(new QuarterSum(sum, points.get(0).unit(), points, labels));
    }

    private Optional<DataQualityIssue> samePeriodConflict(FinancialDataPoint ttm, QuarterSum quarters, String name) {
        if (ttm.value() == null || ttm.unit() != quarters.unit() || quarters.value().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal gap = FinancialDataValidator.divergencePercent(ttm.value(), quarters.value());
        if (gap.compareTo(SAME_PERIOD_TOLERANCE_PERCENT) <= 0) {
            return Optional.empty();
        }
        return Optional.of(DataQualityIssue.conflict(("TTM %s (%s, %s) and the sum of the four reported quarters %s "
                + "(%s, %s) cover the same period but differ by %s%%. Yahoo Finance uses different definitions for "
                + "these fields. Neither is chosen, and no conclusion should be drawn from either.")
                .formatted(name, ttm.display(), ttm.source().sourceField(), quarters.labels(),
                        FinancialUnits.display(quarters.value().setScale(2, RoundingMode.HALF_UP), quarters.unit()),
                        quarters.points().get(0).source().sourceField(), gap.toPlainString()),
                List.of(ttm.source().sourceField(), quarters.points().get(0).source().sourceField())));
    }

    /** Every fiscal year both feeds report, compared; one conflict listing each year that disagrees. */
    private Optional<DataQualityIssue> sameYearConflicts(List<AnnualFinancials> annual,
                                                         List<AnnualCrossCheckFigures> crossCheck,
                                                         Function<AnnualFinancials, FinancialDataPoint> primary,
                                                         Function<AnnualCrossCheckFigures, FinancialDataPoint> secondary,
                                                         String name) {
        List<String> disagreements = new ArrayList<>();
        String primaryField = null;
        String secondaryField = null;
        for (AnnualCrossCheckFigures other : crossCheck) {
            for (AnnualFinancials year : annual) {
                if (!year.period().known() || !other.period().known() || !year.period().end().equals(other.period().end())) {
                    continue;
                }
                FinancialDataPoint a = primary.apply(year);
                FinancialDataPoint b = secondary.apply(other);
                if (a.value() == null || b.value() == null || a.unit() != b.unit() || b.value().signum() <= 0) {
                    continue;
                }
                BigDecimal gap = FinancialDataValidator.divergencePercent(a.value(), b.value());
                if (gap.compareTo(SAME_YEAR_TOLERANCE_PERCENT) > 0) {
                    disagreements.add("%s: %s vs %s (%s%%)".formatted(year.period().label(), a.display(), b.display(),
                            gap.toPlainString()));
                    primaryField = a.source().sourceField();
                    secondaryField = b.source().sourceField();
                }
            }
        }
        if (disagreements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(DataQualityIssue.conflict(("Two Yahoo Finance feeds report different fiscal-year %s for the "
                + "same years (%s vs %s): %s. Neither is chosen, and nothing is calculated or concluded from %s.")
                .formatted(name, primaryField, secondaryField, String.join("; ", disagreements), name),
                List.of(primaryField, secondaryField)));
    }

    // --- Step 2: conflict marking ------------------------------------------------------------

    private static Map<String, String> conflictedFields(List<DataQualityIssue> issues) {
        Map<String, String> fields = new LinkedHashMap<>();
        issues.stream().filter(issue -> issue.type() == DataQualityIssue.Type.DATA_CONFLICT)
                .forEach(issue -> issue.affectedFields().forEach(field -> fields.putIfAbsent(field,
                        "%s disagrees with another source field for the same figure; neither value is chosen"
                                .formatted(field))));
        return fields;
    }

    private static FinancialDataPoint mark(FinancialDataPoint point, Map<String, String> conflicted) {
        return point.dependsOnFields().stream().filter(conflicted::containsKey).findFirst()
                .map(field -> point.inConflict(conflicted.get(field)))
                .orElse(point);
    }

    private ReportedFinancials markConflicts(ReportedFinancials reported, List<DataQualityIssue> issues) {
        Map<String, String> conflicted = conflictedFields(issues);
        if (conflicted.isEmpty()) {
            return reported;
        }
        return reported.withFigures(
                reported.headline().map(p -> mark(p, conflicted)),
                reported.recentQuarters().stream().map(q -> q.map(p -> mark(p, conflicted))).toList(),
                reported.annualHistory().stream().map(y -> y.map(p -> mark(p, conflicted))).toList());
    }

    private CalculatedFinancials markConflicts(CalculatedFinancials calculated, List<DataQualityIssue> issues) {
        Map<String, String> conflicted = conflictedFields(issues);
        return calculated.map(p -> mark(p, conflicted));
    }

    // --- Step 3: calculation -----------------------------------------------------------------

    private CalculatedFinancials calculate(ReportedFinancials reported) {
        HeadlineFinancials headline = reported.headline();
        List<AnnualFinancials> annual = reported.annualHistory();
        return new CalculatedFinancials(
                ttmNetMargin(headline),
                quarterOnQuarter(reported.recentQuarters(), QuarterlyResult::revenue, "revenue",
                        "latestQuarterRevenueGrowthQoqPercent"),
                quarterOnQuarter(reported.recentQuarters(), QuarterlyResult::netProfit, "net profit",
                        "latestQuarterNetProfitGrowthQoqPercent"),
                cagr(annual, AnnualFinancials::revenue, "revenue", "revenueCagrPercent"),
                cagr(annual, AnnualFinancials::netProfit, "net profit", "netProfitCagrPercent"),
                annualRatios(annual));
    }

    /** Why a calculation cannot use these inputs: the first input that is not VALID, or inputs in different units. */
    private record Blocked(DataStatus status, String reason) {
    }

    private Optional<Blocked> blocked(FinancialDataPoint... inputs) {
        Blocked worst = null;
        for (FinancialDataPoint input : inputs) {
            if (input == null) {
                return Optional.of(new Blocked(DataStatus.UNAVAILABLE, "input missing"));
            }
            if (!input.available()) {
                // A conflict is described once, in the data-quality issues; repeating it here adds nothing.
                String why = input.status() == DataStatus.DATA_CONFLICT ? "" : ": " + input.statusReason();
                Blocked candidate = new Blocked(input.status(), "input %s (%s, %s) is %s%s".formatted(input.metric(),
                        input.source() != null ? input.source().sourceField() : "no source field",
                        input.period().label(), input.status(), why));
                if (worst == null || rank(candidate.status()) > rank(worst.status())) {
                    worst = candidate;
                }
            }
        }
        if (worst != null) {
            return Optional.of(worst);
        }
        Unit unit = inputs[0].unit();
        for (FinancialDataPoint input : inputs) {
            if (input.unit() != unit) {
                return Optional.of(new Blocked(DataStatus.UNAVAILABLE, ("inputs are in different units (%s vs %s); "
                        + "currencies are never converted or mixed").formatted(unit, input.unit())));
            }
        }
        return Optional.empty();
    }

    /**
     * A missing input outranks the others - the metric could not exist whatever the rest said - then a
     * conflict, then an invalid input.
     */
    private static int rank(DataStatus status) {
        return switch (status) {
            case UNAVAILABLE -> 3;
            case DATA_CONFLICT -> 2;
            case INVALID -> 1;
            case VALID -> 0;
        };
    }

    private FinancialDataPoint notCalculated(String metric, Unit unit, ReportingPeriod period, SourceInfo source,
                                             Formula formula, String calculation, Blocked blocked, String what,
                                             FinancialDataPoint... inputs) {
        return FinancialDataPoint.notCalculated(metric, unit, period, source, blocked.status(), formula, calculation,
                fields(inputs), "%s not calculated: %s".formatted(what, blocked.reason()));
    }

    private FinancialDataPoint ttmNetMargin(HeadlineFinancials headline) {
        String metric = "netProfitMarginTtmPercent";
        FinancialDataPoint revenue = headline.revenue();
        FinancialDataPoint profit = headline.netProfit();
        ReportingPeriod period = revenue.period();
        String calculation = "net profit / revenue x 100, both %s".formatted(period.label());
        Optional<Blocked> blocked = blocked(profit, revenue);
        if (blocked.isPresent()) {
            return notCalculated(metric, Unit.PERCENT, period, revenue.source(), Formula.RATIO_PERCENT, calculation,
                    blocked.get(), "TTM net profit margin", profit, revenue);
        }
        return FinancialCalculator.profitMarginPercent(profit.value(), revenue.value())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENT, period, revenue.source(),
                        Formula.RATIO_PERCENT, calculation,
                        List.of(CalculationInput.of(period.label() + " net profit", profit),
                                CalculationInput.of(period.label() + " revenue", revenue))))
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
        String calculation = "(%s %s / %s %s - 1) x 100".formatted(latest.period().label(), name,
                previous.period().label(), name);
        Optional<Blocked> blocked = blocked(before, after).or(() -> consecutive(previous.period(), latest.period())
                ? Optional.empty()
                : Optional.of(new Blocked(DataStatus.UNAVAILABLE, "%s and %s are not consecutive quarters".formatted(
                        previous.period().label(), latest.period().label()))));
        if (blocked.isPresent()) {
            return notCalculated(metricName, Unit.PERCENT, latest.period(), after.source(), Formula.GROWTH_PERCENT,
                    calculation, blocked.get(), "Quarter-on-quarter " + name + " growth", before, after);
        }
        return FinancialCalculator.growthPercent(before.value(), after.value())
                .map(value -> FinancialDataPoint.calculated(metricName, value, Unit.PERCENT, latest.period(),
                        after.source(), Formula.GROWTH_PERCENT, calculation,
                        List.of(CalculationInput.of(previous.period().label() + " " + name, before),
                                CalculationInput.of(latest.period().label() + " " + name, after))))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, latest.period(),
                        after.source(), "Quarter-on-quarter %s growth not calculated: the earlier quarter's value is "
                                .formatted(name) + "not positive"));
    }

    /**
     * CAGR between the first and last fiscal years in the series. If either end, or any year between them,
     * is not VALID the CAGR is not calculated - skipping a disputed year would silently choose a value.
     */
    private FinancialDataPoint cagr(List<AnnualFinancials> annual, Function<AnnualFinancials, FinancialDataPoint> metric,
                                    String name, String metricName) {
        List<AnnualFinancials> reported = annual.stream().filter(year -> metric.apply(year).value() != null).toList();
        if (reported.size() < 2) {
            return FinancialDataPoint.unavailable(metricName, Unit.PERCENT, ReportingPeriod.unknown(), null,
                    "%s CAGR needs at least two fiscal years of %s; %d available"
                            .formatted(capitalise(name), name, reported.size()));
        }
        AnnualFinancials first = reported.get(0);
        AnnualFinancials last = reported.get(reported.size() - 1);
        FinancialDataPoint start = metric.apply(first);
        FinancialDataPoint end = metric.apply(last);
        BigDecimal years = fiscalYearsBetween(first.period(), last.period());
        ReportingPeriod span = new ReportingPeriod(PeriodType.MULTI_YEAR, first.period().start(), last.period().end(),
                "%s to %s (%s years)".formatted(first.period().label(), last.period().label(), years.toPlainString()),
                null, null);
        String calculation = "((%s %s / %s %s)^(1/%s) - 1) x 100".formatted(last.period().label(), name,
                first.period().label(), name, years.toPlainString());
        FinancialDataPoint[] seriesPoints = reported.stream().map(metric).toArray(FinancialDataPoint[]::new);
        Optional<Blocked> blocked = blocked(seriesPoints);
        if (blocked.isPresent()) {
            return notCalculated(metricName, Unit.PERCENT, span, end.source(), Formula.CAGR_PERCENT, calculation,
                    blocked.get(), capitalise(name) + " CAGR", start, end);
        }
        return FinancialCalculator.cagrPercent(start.value(), end.value(), years)
                .map(value -> FinancialDataPoint.calculated(metricName, value, Unit.PERCENT, span, end.source(),
                        Formula.CAGR_PERCENT, calculation,
                        List.of(CalculationInput.of(first.period().label() + " " + name, start),
                                CalculationInput.of(last.period().label() + " " + name, end),
                                CalculationInput.derived("years", years, Unit.YEARS))))
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
        String calculation = "(%s %s / %s %s - 1) x 100".formatted(year.period().label(), name,
                prior.period().label(), name);
        Optional<Blocked> blocked = blocked(previous, current);
        if (blocked.isPresent()) {
            return notCalculated(metricName, Unit.PERCENT, year.period(), current.source(), Formula.GROWTH_PERCENT,
                    calculation, blocked.get(), "YoY " + name + " growth", previous, current);
        }
        return FinancialCalculator.yoyGrowthPercent(previous.value(), current.value())
                .map(value -> FinancialDataPoint.calculated(metricName, value, Unit.PERCENT, year.period(),
                        current.source(), Formula.GROWTH_PERCENT, calculation,
                        List.of(CalculationInput.of(prior.period().label() + " " + name, previous),
                                CalculationInput.of(year.period().label() + " " + name, current))))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(),
                        current.source(), "YoY %s growth not calculated: the prior year's value is not positive"
                                .formatted(name)));
    }

    private FinancialDataPoint margin(AnnualFinancials year, FinancialDataPoint numerator, String ratio, String name,
                                      String metricName) {
        String label = year.period().label();
        String calculation = "%s %s / %s revenue x 100".formatted(label, name, label);
        Optional<Blocked> blocked = blocked(numerator, year.revenue());
        if (blocked.isPresent()) {
            return notCalculated(metricName, Unit.PERCENT, year.period(), year.revenue().source(), Formula.RATIO_PERCENT,
                    calculation, blocked.get(), capitalise(ratio), numerator, year.revenue());
        }
        return FinancialCalculator.ratioPercent(numerator.value(), year.revenue().value())
                .map(value -> FinancialDataPoint.calculated(metricName, value, Unit.PERCENT, year.period(),
                        numerator.source(), Formula.RATIO_PERCENT, calculation,
                        List.of(CalculationInput.of(label + " " + name, numerator),
                                CalculationInput.of(label + " revenue", year.revenue()))))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(),
                        numerator.source(), "%s not calculated: revenue is not positive".formatted(capitalise(ratio))));
    }

    private FinancialDataPoint averageBalanceReturn(AnnualFinancials prior, AnnualFinancials year,
                                                    Function<AnnualFinancials, FinancialDataPoint> balance,
                                                    String ratio, String balanceName, String metricName) {
        FinancialDataPoint closing = balance.apply(year);
        FinancialDataPoint opening = prior != null ? balance.apply(prior) : null;
        // An opening balance that exists but is not VALID blocks the average rather than being skipped.
        boolean hasOpening = opening != null && (opening.value() != null || opening.status() != DataStatus.UNAVAILABLE);
        Optional<Blocked> blocked = hasOpening ? blocked(year.netProfit(), closing, opening)
                : blocked(year.netProfit(), closing);
        String label = year.period().label();
        String basis = hasOpening
                ? "average %s (%s and %s closing)".formatted(balanceName, prior.period().label(), label)
                : "closing %s at %s (no prior-year balance available, so not averaged)".formatted(balanceName, label);
        String calculation = "%s net profit / %s x 100".formatted(label, basis);
        if (blocked.isPresent()) {
            return hasOpening
                    ? notCalculated(metricName, Unit.PERCENT, year.period(), closing.source(),
                            Formula.AVERAGE_BALANCE_RETURN_PERCENT, calculation, blocked.get(), capitalise(ratio),
                            year.netProfit(), closing, opening)
                    : notCalculated(metricName, Unit.PERCENT, year.period(), closing.source(),
                            Formula.AVERAGE_BALANCE_RETURN_PERCENT, calculation, blocked.get(), capitalise(ratio),
                            year.netProfit(), closing);
        }
        BigDecimal openingValue = hasOpening ? opening.value() : null;
        Optional<BigDecimal> value = balanceName.contains("equity")
                ? FinancialCalculator.returnOnEquityPercent(year.netProfit().value(), openingValue, closing.value())
                : FinancialCalculator.returnOnAssetsPercent(year.netProfit().value(), openingValue, closing.value());
        List<CalculationInput> inputs = new ArrayList<>(List.of(
                CalculationInput.of(label + " net profit", year.netProfit()),
                CalculationInput.of(label + " closing " + balanceName, closing)));
        if (hasOpening) {
            inputs.add(CalculationInput.of(prior.period().label() + " closing " + balanceName, opening));
        }
        return value
                .map(result -> FinancialDataPoint.calculated(metricName, result, Unit.PERCENT, year.period(),
                        closing.source(), Formula.AVERAGE_BALANCE_RETURN_PERCENT, calculation, inputs))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, Unit.PERCENT, year.period(), closing.source(),
                        "%s not calculated: %s is not positive".formatted(capitalise(ratio), balanceName)));
    }

    /** Both balances at the same fiscal-year end, so no period mismatch; expressed as a multiple, never a percentage. */
    private FinancialDataPoint debtToEquity(AnnualFinancials year) {
        String metric = "debtToEquityMultiple";
        FinancialDataPoint debt = year.totalDebt();
        FinancialDataPoint equity = year.shareholdersEquity();
        String label = year.period().label();
        String calculation = "%s closing total debt / %s closing shareholders' equity (a multiple, not a percentage)"
                .formatted(label, label);
        Optional<Blocked> blocked = blocked(debt, equity);
        if (blocked.isPresent()) {
            return notCalculated(metric, Unit.MULTIPLE, year.period(), equity.source(), Formula.MULTIPLE, calculation,
                    blocked.get(), "Debt-to-equity", debt, equity);
        }
        return FinancialCalculator.debtToEquityMultiple(debt.value(), equity.value())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.MULTIPLE, year.period(), debt.source(),
                        Formula.MULTIPLE, calculation,
                        List.of(CalculationInput.of(label + " total debt", debt),
                                CalculationInput.of(label + " shareholders' equity", equity))))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, year.period(), debt.source(),
                        "Debt-to-equity not calculated: shareholders' equity is not positive"));
    }

    /**
     * The provider's own derived figures are trusted only while they agree with what the application
     * calculates from the same source numbers. A contradiction is a DATA_CONFLICT on both sides.
     */
    private List<DataQualityIssue> providerDerivedChecks(ReportedFinancials reported, CalculatedFinancials calculated) {
        List<DataQualityIssue> issues = new ArrayList<>();
        HeadlineFinancials headline = reported.headline();

        FinancialDataPoint calculatedMargin = calculated.netProfitMarginTtmPercent();
        FinancialDataPoint reportedMargin = headline.netProfitMarginPercent();
        if (calculatedMargin.available() && reportedMargin.available()
                && calculatedMargin.value().subtract(reportedMargin.value()).abs().compareTo(MARGIN_TOLERANCE_PP) > 0) {
            issues.add(DataQualityIssue.conflict(("Calculated TTM net profit margin (%s) differs from Yahoo Finance's "
                    + "published margin (%s) by more than %s percentage point. The two use different profit or revenue "
                    + "definitions; neither is chosen.").formatted(calculatedMargin.display(), reportedMargin.display(),
                    MARGIN_TOLERANCE_PP), fields(calculatedMargin, reportedMargin)));
        }

        yearAgoGrowth(reported.recentQuarters(), QuarterlyResult::revenue)
                .flatMap(growth -> contradiction(headline.quarterlyRevenueGrowthYoyPercent(), growth, "revenue"))
                .ifPresent(issues::add);
        yearAgoGrowth(reported.recentQuarters(), QuarterlyResult::netProfit)
                .flatMap(growth -> contradiction(headline.quarterlyEarningsGrowthYoyPercent(), growth, "net profit"))
                .ifPresent(issues::add);
        return issues;
    }

    private record Growth(BigDecimal percent, String calculation, List<String> fields) {
    }

    /** Latest quarter vs the same quarter a year earlier, when both are reported, VALID and in one unit. */
    private Optional<Growth> yearAgoGrowth(List<QuarterlyResult> quarters,
                                           Function<QuarterlyResult, FinancialDataPoint> metric) {
        if (quarters.isEmpty()) {
            return Optional.empty();
        }
        QuarterlyResult latest = quarters.get(quarters.size() - 1);
        return quarters.stream()
                .filter(q -> q.period().known() && latest.period().known()
                        && q.period().end().equals(latest.period().end().minusYears(1)))
                .findFirst()
                .filter(yearAgo -> blocked(metric.apply(yearAgo), metric.apply(latest)).isEmpty())
                .flatMap(yearAgo -> FinancialCalculator.growthPercent(metric.apply(yearAgo).value(),
                                metric.apply(latest).value())
                        .map(percent -> new Growth(percent, "%s vs %s".formatted(latest.period().label(),
                                yearAgo.period().label()), fields(metric.apply(yearAgo), metric.apply(latest)))));
    }

    private Optional<DataQualityIssue> contradiction(FinancialDataPoint providerGrowth, Growth growth, String name) {
        if (!providerGrowth.available()
                || providerGrowth.value().subtract(growth.percent()).abs().compareTo(GROWTH_TOLERANCE_PP) <= 0) {
            return Optional.empty();
        }
        List<String> affected = new ArrayList<>(providerGrowth.dependsOnFields());
        affected.addAll(growth.fields());
        return Optional.of(DataQualityIssue.conflict(("Yahoo Finance's year-on-year quarterly %s growth (%s, %s) "
                + "contradicts the %s%% the application calculates from the reported quarters (%s). The provider "
                + "figure is not used, and neither is chosen.").formatted(name, providerGrowth.display(),
                providerGrowth.source().sourceField(), growth.percent().toPlainString(), growth.calculation()),
                List.copyOf(new LinkedHashSet<>(affected))));
    }

    // --- Helpers -----------------------------------------------------------------------------

    /** The distinct provider fields a set of points depends on. */
    private static List<String> fields(FinancialDataPoint... points) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (FinancialDataPoint point : points) {
            if (point != null) {
                fields.addAll(point.dependsOnFields());
            }
        }
        return List.copyOf(fields);
    }

    private static String currencyOf(Unit unit) {
        return switch (unit) {
            case INR, INR_CRORE, INR_LAKH_CRORE, INR_PER_SHARE -> "INR";
            case USD_MILLION -> "USD";
            default -> String.valueOf(unit);
        };
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

    private static List<DataGap> gapsFrom(String area, List<FinancialDataPoint> points) {
        return points.stream()
                .filter(point -> point.status() == DataStatus.UNAVAILABLE)
                .map(point -> new DataGap(area, point.metric(), point.statusReason()))
                .toList();
    }

    private static String capitalise(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
