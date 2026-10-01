package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.ValuationResult;
import com.ashish.stockresearch.research.model.ValuationSnapshot;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.research.sector.SectorContext;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Captures one company's screening metrics from the research layer - the same verified, status-carrying
 * figures a research report uses. Nothing is re-derived from raw provider data here; the few metrics the
 * research layer does not calculate come from {@link SnapshotCalculator}.
 */
@Component
public class SnapshotBuilder {

    private final StockResearchService research;
    private final SectorClassifier sectorClassifier;
    private final Clock clock;

    public SnapshotBuilder(StockResearchService research, SectorClassifier sectorClassifier, Clock clock) {
        this.research = research;
        this.sectorClassifier = sectorClassifier;
        this.clock = clock;
    }

    /** Thrown when nothing at all could be retrieved for a company, so there is no snapshot to store. */
    public static class NothingRetrievedException extends RuntimeException {
        NothingRetrievedException(String message) {
            super(message);
        }
    }

    public FundamentalsSnapshot build(Universes.Member member, LocalDate snapshotDate) {
        String symbol = member.symbol();
        CompanyProfileResult profile = research.getCompanyProfile(symbol);
        FinancialSummaryResult financials = research.getFinancialSummary(symbol);
        ValuationResult valuation = research.getValuation(symbol);
        if (!profile.success() && !financials.success() && !valuation.success()) {
            throw new NothingRetrievedException("profile: %s; financials: %s; valuation: %s".formatted(
                    profile.message(), financials.message(), valuation.message()));
        }

        SectorContext sector = profile.success()
                ? sectorClassifier.classify(profile.companyProfile().sector(), profile.companyProfile().industry())
                : SectorContext.of(IndustryGroup.UNKNOWN, "The company profile could not be retrieved: "
                        + profile.message());
        Map<ScreeningMetric, SnapshotMetric> metrics = new EnumMap<>(ScreeningMetric.class);
        financialMetrics(financials, metrics);
        valuationMetrics(valuation, metrics);

        String name = profile.success() && profile.companyProfile().companyName() != null
                ? profile.companyProfile().companyName() : member.companyName();
        return new FundamentalsSnapshot(symbol, name, sector.group(), sector.basis(), snapshotDate,
                clock.instant(), metrics);
    }

    private static void financialMetrics(FinancialSummaryResult result, Map<ScreeningMetric, SnapshotMetric> metrics) {
        List<ScreeningMetric> financial = List.of(ScreeningMetric.ROE_3Y_AVG_PERCENT, ScreeningMetric.ROE_5Y_AVG_PERCENT,
                ScreeningMetric.ROE_PERCENT, ScreeningMetric.ROA_PERCENT, ScreeningMetric.ROCE_PERCENT,
                ScreeningMetric.OPERATING_MARGIN_PERCENT, ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE,
                ScreeningMetric.REVENUE_CAGR_3Y_PERCENT, ScreeningMetric.REVENUE_CAGR_5Y_PERCENT,
                ScreeningMetric.NET_PROFIT_CAGR_3Y_PERCENT, ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT,
                ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE, ScreeningMetric.OCF_TO_PAT_5Y_MULTIPLE,
                ScreeningMetric.DIVIDENDS_TO_OCF_PERCENT);
        if (!result.success()) {
            financial.forEach(metric -> metrics.put(metric, SnapshotMetric.unavailable(metric,
                    "The financial summary could not be retrieved: " + result.message())));
            return;
        }
        FinancialSummary summary = result.financialSummary();
        List<AnnualFinancials> annual = summary.reported().annualHistory();
        List<AnnualRatios> ratios = summary.calculated().annual();
        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        annual.forEach(year -> year.points().forEach(sources::add));

        for (ScreeningMetric metric : List.of(ScreeningMetric.ROE_3Y_AVG_PERCENT, ScreeningMetric.ROE_5Y_AVG_PERCENT)) {
            metrics.put(metric, SnapshotMetric.of(metric, SnapshotCalculator.roeAverage(ratios, metric.years(), sources)));
        }
        latest(ratios, AnnualRatios::returnOnEquityPercent, ScreeningMetric.ROE_PERCENT, metrics);
        latest(ratios, AnnualRatios::returnOnAssetsPercent, ScreeningMetric.ROA_PERCENT, metrics);
        latest(ratios, AnnualRatios::returnOnCapitalEmployedPercent, ScreeningMetric.ROCE_PERCENT, metrics);
        latest(ratios, AnnualRatios::operatingMarginPercent, ScreeningMetric.OPERATING_MARGIN_PERCENT, metrics);
        latest(ratios, AnnualRatios::debtToEquityMultiple, ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE, metrics);
        for (ScreeningMetric metric : List.of(ScreeningMetric.REVENUE_CAGR_3Y_PERCENT,
                ScreeningMetric.REVENUE_CAGR_5Y_PERCENT)) {
            metrics.put(metric, SnapshotMetric.of(metric, SnapshotCalculator.cagr(annual, metric.years(),
                    AnnualFinancials::revenue, "revenue", "revenueCagr%dyPercent".formatted(metric.years()), sources)));
        }
        for (ScreeningMetric metric : List.of(ScreeningMetric.NET_PROFIT_CAGR_3Y_PERCENT,
                ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT)) {
            metrics.put(metric, SnapshotMetric.of(metric, SnapshotCalculator.cagr(annual, metric.years(),
                    AnnualFinancials::netProfit, "net profit", "netProfitCagr%dyPercent".formatted(metric.years()),
                    sources)));
        }
        metrics.put(ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE, SnapshotMetric.of(ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE,
                summary.calculated().ocfToPat3yMultiple()));
        metrics.put(ScreeningMetric.OCF_TO_PAT_5Y_MULTIPLE, SnapshotMetric.of(ScreeningMetric.OCF_TO_PAT_5Y_MULTIPLE,
                summary.calculated().ocfToPat5yMultiple()));
        metrics.put(ScreeningMetric.DIVIDENDS_TO_OCF_PERCENT, SnapshotMetric.of(ScreeningMetric.DIVIDENDS_TO_OCF_PERCENT,
                SnapshotCalculator.dividendsToOperatingCashFlow(annual, sources)));
    }

    private static void latest(List<AnnualRatios> ratios, Function<AnnualRatios, FinancialDataPoint> field,
                               ScreeningMetric metric, Map<ScreeningMetric, SnapshotMetric> metrics) {
        metrics.put(metric, ratios.isEmpty()
                ? SnapshotMetric.unavailable(metric, "no fiscal-year statements are available")
                : SnapshotMetric.of(metric, field.apply(ratios.get(ratios.size() - 1))));
    }

    private static void valuationMetrics(ValuationResult result, Map<ScreeningMetric, SnapshotMetric> metrics) {
        Map<ScreeningMetric, Function<ValuationSnapshot, FinancialDataPoint>> fields = Map.of(
                ScreeningMetric.TRAILING_PE_MULTIPLE, ValuationSnapshot::trailingPe,
                ScreeningMetric.PRICE_TO_BOOK_MULTIPLE, ValuationSnapshot::priceToBook,
                ScreeningMetric.EARNINGS_YIELD_PERCENT, ValuationSnapshot::earningsYieldPercent,
                ScreeningMetric.DIVIDEND_YIELD_PERCENT, ValuationSnapshot::dividendYieldPercent);
        fields.forEach((metric, field) -> {
            if (!result.success()) {
                metrics.put(metric, SnapshotMetric.unavailable(metric,
                        "The valuation could not be retrieved: " + result.message()));
                return;
            }
            FinancialDataPoint point = field.apply(result.valuation());
            metrics.put(metric, SnapshotMetric.of(metric, point,
                    result.valuation().uncheckedMetrics().get(point.metric())));
        });
    }
}
