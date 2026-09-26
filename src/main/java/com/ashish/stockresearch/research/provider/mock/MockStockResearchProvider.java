package com.ashish.stockresearch.research.provider.mock;

import com.ashish.stockresearch.research.CompanyProfileProvider;
import com.ashish.stockresearch.research.FinancialDataProvider;
import com.ashish.stockresearch.research.HistoricalMarketDataProvider;
import com.ashish.stockresearch.research.model.CalendarYearReturn;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Development-only research provider, mirroring
 * {@code MockMarketDataProvider}. Returns clearly labelled sample data so
 * the multi-tool calling flow can be exercised end-to-end with no network
 * access, and only when the "mock" profile is explicitly enabled.
 *
 * Every figure here is MADE UP. The source string and provenance note say
 * so in every object, so a model reading this data cannot mistake it for
 * research.
 */
@Component
@Profile("mock")
public class MockStockResearchProvider
        implements CompanyProfileProvider, FinancialDataProvider, HistoricalMarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(MockStockResearchProvider.class);
    private static final String SOURCE =
            "MOCK-SAMPLE-DATA (not real research data; run without the 'mock' profile for real data)";
    private static final String NOTE =
            "THIS IS FABRICATED SAMPLE DATA FOR DEVELOPMENT. Do not present any of these figures to a user "
                    + "as factual information about this company.";

    @Override
    public CompanyProfile getCompanyProfile(String symbolOrName) {
        String symbol = normalize(symbolOrName);
        warn("company profile", symbol);
        return new CompanyProfile(symbol, "NSE", symbol + " Limited (sample)", "Sample Sector",
                "Sample Industry", "India", "https://example.invalid", 10_000,
                new BigDecimal("1000000000"), "INR",
                "Sample business summary for development purposes only.",
                provenance(DataFreshness.REFERENCE, null));
    }

    @Override
    public FinancialSummary getFinancialSummary(String symbolOrName) {
        String symbol = normalize(symbolOrName);
        warn("financial summary", symbol);
        return new FinancialSummary(symbol, "NSE", symbol + " Limited (sample)", "INR",
                "Sample trailing twelve months (NOT REAL)",
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 3, 31),
                new BigDecimal("100000000"), new BigDecimal("15000000"), new BigDecimal("22000000"),
                new BigDecimal("20.00"), new BigDecimal("15.00"), new BigDecimal("18.00"),
                null, new BigDecimal("12.00"),
                new BigDecimal("5000000"), new BigDecimal("25.00"), new BigDecimal("8000000"),
                new BigDecimal("9000000"), new BigDecimal("10.00"), new BigDecimal("8.00"),
                List.of("returnOnCapitalEmployedPercent"),
                provenance(DataFreshness.PERIODIC_FILING, LocalDate.of(2026, 6, 30)));
    }

    @Override
    public HistoricalPerformance getHistoricalPerformance(String symbolOrName, int years) {
        String symbol = normalize(symbolOrName);
        warn("historical performance", symbol);
        LocalDate end = LocalDate.of(2026, 9, 1);
        return new HistoricalPerformance(symbol, "NSE", "INR", end.minusYears(years), end, years,
                new BigDecimal(years).setScale(2), new BigDecimal("100.00"), new BigDecimal("180.00"),
                new BigDecimal("80.00"), new BigDecimal("12.47"), new BigDecimal("190.00"),
                new BigDecimal("95.00"), new BigDecimal("-20.00"),
                List.of(new CalendarYearReturn(end.getYear(), new BigDecimal("180.00"), new BigDecimal("10.00"))),
                "Fabricated sample series (NOT REAL PRICES)",
                provenance(DataFreshness.HISTORICAL, end));
    }

    private DataProvenance provenance(DataFreshness freshness, LocalDate asOf) {
        return new DataProvenance(SOURCE, freshness, Instant.now(), asOf, NOTE);
    }

    private String normalize(String symbolOrName) {
        return symbolOrName.strip().toUpperCase();
    }

    private void warn(String capability, String symbol) {
        log.warn("Using MOCK research provider - {} for '{}' is SAMPLE DATA, not real information",
                capability, symbol);
    }
}
