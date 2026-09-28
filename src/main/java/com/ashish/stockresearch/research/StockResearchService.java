package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.model.ValuationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * Business logic for the stock research toolkit: validates input, delegates
 * to the right provider, runs the Java calculation services over the
 * provider's facts, and converts every failure mode into a structured
 * result rather than letting an exception escape into the AI tool layer.
 *
 * One tool failing must never sink the others - each method here is
 * independent, so when the model calls four tools for a single question it
 * gets four independent outcomes and can report exactly which ones came back
 * empty.
 */
@Service
public class StockResearchService {

    private static final Logger log = LoggerFactory.getLogger(StockResearchService.class);

    /** Widest window we ask a provider for; beyond this the data thins out and adds no insight. */
    private static final int MAX_YEARS = 25;
    private static final int DEFAULT_YEARS = 5;

    private final CompanyProfileProvider companyProfileProvider;
    private final FinancialDataProvider financialDataProvider;
    private final ShareholdingProvider shareholdingProvider;
    private final HistoricalMarketDataProvider historicalMarketDataProvider;
    private final FinancialMetricsService financialMetricsService;
    private final HistoricalPerformanceService historicalPerformanceService;
    private final ValuationDataProvider valuationDataProvider;
    private final ValuationService valuationService;

    public StockResearchService(CompanyProfileProvider companyProfileProvider,
                                FinancialDataProvider financialDataProvider,
                                ShareholdingProvider shareholdingProvider,
                                HistoricalMarketDataProvider historicalMarketDataProvider,
                                FinancialMetricsService financialMetricsService,
                                HistoricalPerformanceService historicalPerformanceService,
                                ValuationDataProvider valuationDataProvider,
                                ValuationService valuationService) {
        this.companyProfileProvider = companyProfileProvider;
        this.financialDataProvider = financialDataProvider;
        this.shareholdingProvider = shareholdingProvider;
        this.historicalMarketDataProvider = historicalMarketDataProvider;
        this.financialMetricsService = financialMetricsService;
        this.historicalPerformanceService = historicalPerformanceService;
        this.valuationDataProvider = valuationDataProvider;
        this.valuationService = valuationService;
    }

    public CompanyProfileResult getCompanyProfile(String rawSymbol) {
        return execute("company profile", rawSymbol,
                symbol -> CompanyProfileResult.success(companyProfileProvider.getCompanyProfile(symbol)),
                CompanyProfileResult::failure);
    }

    public FinancialSummaryResult getFinancialSummary(String rawSymbol) {
        return execute("financial summary", rawSymbol,
                symbol -> FinancialSummaryResult.success(
                        financialMetricsService.summarize(financialDataProvider.getReportedFinancials(symbol))),
                FinancialSummaryResult::failure);
    }

    public ShareholdingResult getShareholding(String rawSymbol) {
        return execute("shareholding", rawSymbol,
                symbol -> ShareholdingResult.success(shareholdingProvider.getShareholding(symbol)),
                ShareholdingResult::failure);
    }

    /** Point-in-time valuation multiples, each checked against the share price it is quoted against. */
    public ValuationResult getValuation(String rawSymbol) {
        return execute("valuation", rawSymbol,
                symbol -> ValuationResult.success(valuationService.assess(valuationDataProvider.getReportedValuation(symbol))),
                ValuationResult::failure);
    }

    public HistoricalPerformanceResult getHistoricalPerformance(String rawSymbol, Integer years) {
        int window = normalizeYears(years);
        return execute("historical performance", rawSymbol,
                symbol -> HistoricalPerformanceResult.success(historicalPerformanceService.analyze(
                        historicalMarketDataProvider.getPriceHistory(symbol, window), window)),
                HistoricalPerformanceResult::failure);
    }

    /**
     * Clamps a requested lookback to something a provider can sensibly serve.
     * A model may pass nothing, a negative, or an absurd number of years;
     * none of those should surface as an error the user has to think about.
     */
    private int normalizeYears(Integer years) {
        if (years == null || years <= 0) {
            return DEFAULT_YEARS;
        }
        return Math.min(years, MAX_YEARS);
    }

    /**
     * Shared validate-call-translate wrapper. Provider exceptions become
     * statuses; anything unexpected is logged in full here but reported to
     * the model as a short, non-leaking message.
     */
    private <R> R execute(String capability,
                          String rawSymbol,
                          Function<String, R> call,
                          FailureFactory<R> onFailure) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return onFailure.create(ResearchStatus.INVALID_SYMBOL,
                    "No stock symbol or company name was provided.");
        }
        String symbol = rawSymbol.strip();

        try {
            return call.apply(symbol);
        } catch (ResearchDataUnavailableException ex) {
            log.warn("{} unavailable for '{}' [{}]: {}", capability, symbol, ex.status(), ex.getMessage());
            return onFailure.create(ex.status(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Unexpected error retrieving {} for '{}'", capability, symbol, ex);
            return onFailure.create(ResearchStatus.PROVIDER_ERROR,
                    "Unexpected error retrieving %s for '%s'. No data could be retrieved - do not assume zero."
                            .formatted(capability, symbol));
        }
    }

    /** Narrow view of each result record's {@code failure} factory. */
    @FunctionalInterface
    private interface FailureFactory<R> {
        R create(ResearchStatus status, String message);
    }
}
