package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.QuarterlyResult;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Payloads use real Yahoo Finance values for HDFC Bank, with "today" pinned
 * to 26 September 2026: Q1 FY27 (ended 30 June) is the latest reported
 * quarter and Q2 FY27 (ends 30 September) has not been reported.
 */
class YahooFinanceFinancialDataProviderTest {

    private static final Clock SEPT_2026 = Clock.fixed(Instant.parse("2026-09-26T06:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private MockRestServiceServer server;
    private YahooSymbolResolver resolver;
    private YahooCrumbProvider crumbProvider;
    private YahooFinanceFinancialDataProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        server = MockRestServiceServer.bindTo(builder).build();

        resolver = mock(YahooSymbolResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of(
                new YahooSymbolResolver.Resolution("HDFCBANK.NS", "HDFCBANK", "NSE", null)));

        crumbProvider = mock(YahooCrumbProvider.class);
        when(crumbProvider.get()).thenReturn(Optional.of(
                new YahooCrumbProvider.Credentials("A3=cookie", "crumb123", Instant.MAX)));

        provider = new YahooFinanceFinancialDataProvider(resolver,
                new YahooQuoteSummaryClient(builder.build(), crumbProvider), new FinancialDataValidator(SEPT_2026));
    }

    private static final String HDFC_FINANCIALS = """
            {
              "totalRevenue": { "raw": 2949644288000, "fmt": "2.95T" },
              "ebitda": {},
              "totalDebt": { "raw": 5658713784320, "fmt": "5.66T" },
              "profitMargins": { "raw": 0.26787, "fmt": "26.79%" },
              "operatingMargins": { "raw": 0.33291999, "fmt": "33.29%" },
              "returnOnEquity": { "raw": 0.13838, "fmt": "13.84%" },
              "returnOnAssets": { "raw": 0.0175, "fmt": "1.75%" },
              "revenueGrowth": { "raw": 0.166, "fmt": "16.60%" },
              "earningsGrowth": { "raw": 0.181, "fmt": "18.10%" },
              "debtToEquity": { "raw": 10.211, "fmt": "10.21%" },
              "financialCurrency": "INR"
            }
            """;

    private static final String HDFC_STATISTICS = """
            {
              "netIncomeToCommon": { "raw": 790127706112, "fmt": "790.13B" },
              "mostRecentQuarter": { "raw": 1782777600, "fmt": "2026-06-30" },
              "lastFiscalYearEnd": { "raw": 1774915200, "fmt": "2026-03-31" }
            }
            """;

    /** Q4 FY26 and Q1 FY27 as Yahoo reports them, plus a future Q2 FY27 row that must be rejected. */
    private static final String HDFC_EARNINGS = """
            {
              "financialCurrency": "INR",
              "earningsChart": { "quarterly": [
                { "actual": { "raw": 12.45, "fmt": "12.45" }, "fiscalQuarter": "4Q2026", "calendarQuarter": "1Q2026",
                  "periodEndDate": { "raw": 1774915200 }, "reportedDate": { "raw": 1776503754 } },
                { "actual": { "raw": 12.35, "fmt": "12.35" }, "fiscalQuarter": "1Q2027", "calendarQuarter": "2Q2026",
                  "periodEndDate": { "raw": 1782777600 }, "reportedDate": { "raw": 1784365037 } },
                { "actual": { "raw": 12.24, "fmt": "12.24" }, "fiscalQuarter": "2Q2027", "calendarQuarter": "3Q2026",
                  "periodEndDate": { "raw": 1790726400 }, "reportedDate": null }
              ]},
              "financialsChart": { "quarterly": [
                { "date": "1Q2026", "revenue": { "raw": 462804500000, "fmt": "462.8B" }, "earnings": { "raw": 192210500000, "fmt": "192.21B" } },
                { "date": "2Q2026", "revenue": { "raw": 463555500000, "fmt": "463.56B" }, "earnings": { "raw": 190597200000, "fmt": "190.6B" } },
                { "date": "3Q2026", "revenue": { "raw": 470000000000, "fmt": "470B" }, "earnings": { "raw": 195000000000, "fmt": "195B" } }
              ]}
            }
            """;

    private static final String HDFC_TIMESERIES = """
            { "timeseries": { "result": [
              { "meta": { "type": ["annualTotalRevenue"] }, "annualTotalRevenue": [
                { "asOfDate": "2025-03-31", "periodType": "12M", "currencyCode": "INR", "reportedValue": { "raw": 1838641100000, "fmt": "1.84T" } },
                { "asOfDate": "2026-03-31", "periodType": "12M", "currencyCode": "INR", "reportedValue": { "raw": 1925667800000, "fmt": "1.93T" } }
              ]},
              { "meta": { "type": ["annualNetIncome"] }, "annualNetIncome": [
                { "asOfDate": "2025-03-31", "periodType": "12M", "currencyCode": "INR", "reportedValue": { "raw": 673508300000, "fmt": "673.51B" } },
                { "asOfDate": "2026-03-31", "periodType": "12M", "currencyCode": "INR", "reportedValue": { "raw": 704793400000, "fmt": "704.79B" } }
              ]},
              { "meta": { "type": ["annualOperatingIncome"] } },
              { "meta": { "type": ["annualStockholdersEquity"] }, "annualStockholdersEquity": [
                { "asOfDate": "2026-03-31", "periodType": "12M", "currencyCode": "INR", "reportedValue": { "raw": 8167395600000, "fmt": "8.17T" } }
              ]},
              { "meta": { "type": ["annualTotalAssets"] }, "annualTotalAssets": [
                null,
                { "asOfDate": "2026-03-31", "periodType": "12M", "currencyCode": "INR", "reportedValue": { "raw": 52621193200000, "fmt": "52.62T" } }
              ]}
            ], "error": null } }
            """;

    private String quoteSummary(String financialData, String keyStatistics, String earnings) {
        return """
                { "quoteSummary": { "result": [{
                    "price": { "longName": "HDFC Bank Limited" },
                    "financialData": %s,
                    "defaultKeyStatistics": %s,
                    "earnings": %s
                  }], "error": null } }
                """.formatted(financialData, keyStatistics, earnings);
    }

    private void respond(String quoteSummaryJson) {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/HDFCBANK.NS")))
                .andRespond(withSuccess(quoteSummaryJson, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/ws/fundamentals-timeseries/v1/finance/timeseries/HDFCBANK.NS")))
                .andRespond(withSuccess(HDFC_TIMESERIES, MediaType.APPLICATION_JSON));
    }

    private ReportedFinancials hdfc() {
        respond(quoteSummary(HDFC_FINANCIALS, HDFC_STATISTICS, HDFC_EARNINGS));
        return provider.getReportedFinancials("HDFC Bank");
    }

    @Test
    void normalisesRevenueOfTwoPointNineFiveTrillionRupeesToTwoPointNineFiveLakhCrore() {
        FinancialDataPoint revenue = hdfc().headline().revenue();

        assertThat(revenue.unit()).isEqualTo(Unit.INR_CRORE);
        assertThat(revenue.value()).isEqualByComparingTo("294964.43");
        assertThat(revenue.display()).isEqualTo("₹2.95 lakh crore (₹2,94,964 crore)");
        assertThat(revenue.calculationStatus()).isEqualTo(CalculationStatus.REPORTED);
        assertThat(revenue.rawValue()).isEqualByComparingTo("2949644288000");
        assertThat(revenue.providerUnit()).isEqualTo(ProviderUnit.WHOLE_CURRENCY_UNITS);
        assertThat(revenue.source().source()).isEqualTo("Yahoo Finance");
        assertThat(revenue.source().sourceField()).isEqualTo("financialData.totalRevenue");
        assertThat(revenue.source().sourceType()).isEqualTo(SourceType.MARKET_DATA_PROVIDER);
    }

    @Test
    void identifiesQ1Fy27AsTheLatestReportedQuarterWithItsPublicationDate() {
        ReportedFinancials financials = hdfc();

        assertThat(financials.latestReportedQuarter().label()).isEqualTo("Q1 FY27");
        assertThat(financials.latestReportedQuarter().end()).isEqualTo(LocalDate.of(2026, 6, 30));
        QuarterlyResult latest = financials.recentQuarters().get(financials.recentQuarters().size() - 1);
        assertThat(latest.revenue().source().publishedAt()).isEqualTo(LocalDate.of(2026, 7, 18));
        assertThat(latest.revenue().value()).isEqualByComparingTo("46355.55");
        assertThat(latest.earningsPerShare().display()).isEqualTo("₹12.35 per share");
        assertThat(financials.provenance().periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(financials.provenance().publishedAt()).isEqualTo(LocalDate.of(2026, 7, 18));
    }

    @Test
    void excludesAQuarterThatHasNotEndedAndRecordsWhy() {
        ReportedFinancials financials = hdfc();

        assertThat(financials.recentQuarters()).extracting(q -> q.period().label())
                .containsExactly("Q4 FY26", "Q1 FY27")
                .doesNotContain("Q2 FY27");
        assertThat(financials.dataGaps()).anySatisfy(gap -> {
            assertThat(gap.item()).isEqualTo("quarter Q2 FY27");
            assertThat(gap.reason()).contains("cannot have been reported");
        });
    }

    @Test
    void labelsTtmAndBalanceSheetFiguresWithTheirOwnPeriods() {
        ReportedFinancials financials = hdfc();

        assertThat(financials.headline().revenue().period().type()).isEqualTo(PeriodType.TRAILING_TWELVE_MONTHS);
        assertThat(financials.headline().revenue().period().label()).isEqualTo("TTM to Q1 FY27 (ending 2026-06-30)");
        assertThat(financials.headline().totalDebt().period().type()).isEqualTo(PeriodType.POINT_IN_TIME);
        assertThat(financials.headline().quarterlyRevenueGrowthYoyPercent().period().label()).isEqualTo("Q1 FY27");
        assertThat(financials.latestFiscalYear().label()).isEqualTo("FY26");
    }

    @Test
    void convertsRatiosOnlyAfterYahoosFormatConfirmsTheyAreFractions() {
        FinancialDataPoint margin = hdfc().headline().netProfitMarginPercent();

        assertThat(margin.rawValue()).isEqualByComparingTo("0.26787");
        assertThat(margin.providerUnit()).isEqualTo(ProviderUnit.FRACTION);
        assertThat(margin.value()).isEqualByComparingTo("26.79");
        assertThat(margin.unit()).isEqualTo(Unit.PERCENT);
        assertThat(margin.metric()).isEqualTo("netProfitMarginPercent");
    }

    @Test
    void keepsDebtToEquityAsPublishedWhenYahoosFormatShowsItIsAlreadyAPercentage() {
        FinancialDataPoint debtToEquity = hdfc().headline().debtToEquityPercent();

        // raw 10.211 with fmt "10.21%": percentage points, so NOT multiplied by 100 and NOT a multiple.
        assertThat(debtToEquity.providerUnit()).isEqualTo(ProviderUnit.PERCENTAGE);
        assertThat(debtToEquity.value()).isEqualByComparingTo("10.21");
        assertThat(debtToEquity.display()).isEqualTo("10.21%");
    }

    @Test
    void scalesDebtToEquityWhenYahoosFormatShowsItIsAFraction() {
        respond(quoteSummary("""
                { "debtToEquity": { "raw": 0.10211, "fmt": "10.21%" }, "financialCurrency": "INR" }
                """, HDFC_STATISTICS, "null"));

        FinancialDataPoint debtToEquity = provider.getReportedFinancials("HDFC Bank").headline().debtToEquityPercent();

        assertThat(debtToEquity.providerUnit()).isEqualTo(ProviderUnit.FRACTION);
        assertThat(debtToEquity.value()).isEqualByComparingTo("10.21");
    }

    @Test
    void reportsDebtToEquityAsUnverifiedWhenYahooDoesNotStateItsUnit() {
        respond(quoteSummary("""
                { "debtToEquity": { "raw": 7.269, "fmt": "7.27" }, "financialCurrency": "INR" }
                """, HDFC_STATISTICS, "null"));

        FinancialDataPoint debtToEquity = provider.getReportedFinancials("HDFC Bank").headline().debtToEquityPercent();

        assertThat(debtToEquity.available()).isFalse();
        assertThat(debtToEquity.value()).isNull();
        assertThat(debtToEquity.rawValue()).isEqualByComparingTo("7.269");
        assertThat(debtToEquity.display()).isEqualTo("UNAVAILABLE - metric semantics could not be verified");
    }

    @Test
    void refusesToNormaliseABareNumberWhoseMeaningYahooDidNotState() {
        respond(quoteSummary("""
                { "totalRevenue": 12345, "operatingMargins": 0.25, "financialCurrency": "INR" }
                """, HDFC_STATISTICS, "null"));

        ReportedFinancials financials = provider.getReportedFinancials("HDFC Bank");

        assertThat(financials.headline().revenue().statusReason()).startsWith("metric semantics could not be verified");
        assertThat(financials.headline().operatingMarginPercent().available()).isFalse();
    }

    @Test
    void readsFiscalYearStatementsFromTheTimeSeriesWithFiscalLabels() {
        ReportedFinancials financials = hdfc();

        assertThat(financials.annualHistory()).extracting(y -> y.period().label()).containsExactly("FY25", "FY26");
        AnnualFinancials fy26 = financials.annualHistory().get(1);
        assertThat(fy26.revenue().value()).isEqualByComparingTo("192566.78");
        assertThat(fy26.netProfit().value()).isEqualByComparingTo("70479.34");
        assertThat(fy26.totalAssets().value()).isEqualByComparingTo("5262119.32");
        assertThat(fy26.revenue().source().sourceField()).isEqualTo("fundamentals-timeseries.annualTotalRevenue");
        // FY25 has no equity in the payload: unavailable with a reason, not zero.
        assertThat(financials.annualHistory().get(0).shareholdersEquity().available()).isFalse();
        assertThat(fy26.operatingIncome().available()).isFalse();
    }

    @Test
    void neverPutsExampleNumbersInDataFieldsWhereAModelCouldMistakeThemForData() {
        ReportedFinancials financials = hdfc();

        assertThat(financials.headline().debtToEquityPercent().source().sourceField())
                .isEqualTo("financialData.debtToEquity");
    }

    @Test
    void reportsMissingEbitdaAndRoceAsUnavailableWithReasons() {
        ReportedFinancials financials = hdfc();

        assertThat(financials.headline().ebitda().available()).isFalse();
        assertThat(financials.headline().ebitda().value()).isNull();
        assertThat(financials.headline().ebitda().statusReason()).contains("financialData.ebitda");
        assertThat(financials.headline().returnOnCapitalEmployedPercent().available()).isFalse();
    }

    @Test
    void marksFilingDataAsPeriodicSoItIsNotPresentedAsCurrent() {
        ReportedFinancials financials = hdfc();

        assertThat(financials.provenance().freshness()).isEqualTo(DataFreshness.PERIODIC_FINANCIALS);
        assertThat(financials.provenance().source()).isEqualTo("Yahoo Finance");
        assertThat(financials.provenance().note()).contains("NOT live").doesNotContainIgnoringCase("filing");
    }

    @Test
    void keepsTheHeadlineFiguresWhenOnlyTheAnnualHistoryFails() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/HDFCBANK.NS")))
                .andRespond(withSuccess(quoteSummary(HDFC_FINANCIALS, HDFC_STATISTICS, HDFC_EARNINGS),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/ws/fundamentals-timeseries")))
                .andRespond(withServerError());

        ReportedFinancials financials = provider.getReportedFinancials("HDFC Bank");

        assertThat(financials.headline().revenue().available()).isTrue();
        assertThat(financials.annualHistory()).isEmpty();
        assertThat(financials.dataGaps()).anySatisfy(gap -> assertThat(gap.item()).isEqualTo("annualHistory"));
    }

    @Test
    void answersARepeatedRequestFromTheCacheWithoutCallingYahoo() {
        YahooFinanceFinancialDataProvider cached =
                YahooCacheConfigurationTest.cached(YahooFinanceFinancialDataProvider.class, provider);
        respond(quoteSummary(HDFC_FINANCIALS, HDFC_STATISTICS, HDFC_EARNINGS));

        ReportedFinancials first = cached.getReportedFinancials("HDFC Bank");
        ReportedFinancials second = cached.getReportedFinancials("hdfc bank");

        assertThat(second).isSameAs(first);
        server.verify(); // exactly one quoteSummary and one timeseries call
    }

    @Test
    void doesNotCacheAnAnswerWhoseAnnualHistoryFailedToLoad() {
        YahooFinanceFinancialDataProvider cached =
                YahooCacheConfigurationTest.cached(YahooFinanceFinancialDataProvider.class, provider);
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/HDFCBANK.NS")))
                .andRespond(withSuccess(quoteSummary(HDFC_FINANCIALS, HDFC_STATISTICS, HDFC_EARNINGS),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/ws/fundamentals-timeseries")))
                .andRespond(withServerError());
        respond(quoteSummary(HDFC_FINANCIALS, HDFC_STATISTICS, HDFC_EARNINGS));

        assertThat(cached.getReportedFinancials("HDFC Bank").annualHistory()).isEmpty();
        assertThat(cached.getReportedFinancials("HDFC Bank").annualHistory()).isNotEmpty();
        server.verify();
    }

    @Test
    void fallsBackToMostRecentQuarterWhenThereAreNoDatedQuarterlyResults() {
        respond(quoteSummary(HDFC_FINANCIALS, HDFC_STATISTICS, "null"));

        ReportedFinancials financials = provider.getReportedFinancials("HDFC Bank");

        assertThat(financials.latestReportedQuarter().label()).isEqualTo("Q1 FY27");
        assertThat(financials.recentQuarters()).isEmpty();
    }

    @Test
    void marksTheLatestQuarterUnknownWhenTheProviderGivesNoDateRatherThanGuessing() {
        respond(quoteSummary(HDFC_FINANCIALS, "{}", "null"));

        ReportedFinancials financials = provider.getReportedFinancials("HDFC Bank");

        assertThat(financials.latestReportedQuarter().known()).isFalse();
        assertThat(financials.headline().revenue().period().known()).isFalse();
        assertThat(financials.headline().revenue().period().label()).startsWith("UNKNOWN");
    }

    @Test
    void refusesToPresentAFutureMostRecentQuarterAsReported() {
        respond(quoteSummary(HDFC_FINANCIALS, """
                { "mostRecentQuarter": { "raw": 1790726400 }, "lastFiscalYearEnd": { "raw": 1774915200 } }
                """, "null"));

        ReportedFinancials financials = provider.getReportedFinancials("HDFC Bank");

        assertThat(financials.latestReportedQuarter().known()).isFalse();
        assertThat(financials.dataGaps()).anySatisfy(gap -> assertThat(gap.reason()).contains("Q2 FY27"));
    }

    @Test
    void normalisesUsdAccountsToUsdMillionsAndFlagsThemLoudly() {
        respond(quoteSummary("""
                { "totalRevenue": { "raw": 20298999808, "fmt": "20.3B" }, "financialCurrency": "USD" }
                """, HDFC_STATISTICS, "null"));

        ReportedFinancials financials = provider.getReportedFinancials("INFY");

        assertThat(financials.reportingCurrency()).isEqualTo("USD");
        assertThat(financials.headline().revenue().unit()).isEqualTo(Unit.USD_MILLION);
        assertThat(financials.headline().revenue().display()).isEqualTo("US$20299.00 million");
        assertThat(financials.provenance().note()).contains("reported in USD, not INR");
    }

    @Test
    void neverLabelsAnAmountAsRupeesWhenTheCurrencyIsUnknown() {
        respond(quoteSummary("""
                { "totalRevenue": { "raw": 100, "fmt": "100" } }
                """, HDFC_STATISTICS, "null"));

        FinancialDataPoint revenue = provider.getReportedFinancials("HDFC Bank").headline().revenue();

        assertThat(revenue.available()).isFalse();
        assertThat(revenue.unit()).isNull();
        assertThat(revenue.statusReason()).contains("currency is unknown");
    }

    @Test
    void reportsAYahooErrorPayloadAsMissingDataNotAsSuccess() {
        server.expect(requestTo(containsString("/v10/finance/quoteSummary/HDFCBANK.NS")))
                .andRespond(withSuccess("""
                        { "quoteSummary": { "result": null,
                          "error": { "code": "Not Found", "description": "No fundamentals" } } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.getReportedFinancials("HDFC Bank"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.DATA_NOT_AVAILABLE);
    }

    @Test
    void reportsAnAuthenticationFailureAsAProviderProblemNotAsMissingFinancials() {
        when(crumbProvider.get()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.getReportedFinancials("HDFC Bank"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.PROVIDER_UNAVAILABLE);
    }

    @Test
    void reportsAnUnresolvableSymbolAsNotFound() {
        when(resolver.resolve("NOTACOMPANY")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.getReportedFinancials("NOTACOMPANY"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.SYMBOL_NOT_FOUND);
    }

    // --- Currencies, derived ratios and the second annual feed -----------------------------------

    @Test
    void readsTheQuarterlyChartsInTheirOwnCurrencyNotTheHeadlineCurrency() {
        // The Infosys shape: financialData in USD, the earnings module's charts in INR.
        String usdHeadline = HDFC_FINANCIALS.replace("\"financialCurrency\": \"INR\"", "\"financialCurrency\": \"USD\"");
        respond(quoteSummary(usdHeadline, HDFC_STATISTICS, HDFC_EARNINGS));

        ReportedFinancials financials = provider.getReportedFinancials("HDFC Bank");
        QuarterlyResult latest = financials.recentQuarters().getLast();

        assertThat(financials.headline().revenue().unit()).isEqualTo(Unit.USD_MILLION);
        assertThat(financials.headline().revenue().currency().originalCurrency()).isEqualTo("USD");
        assertThat(latest.revenue().unit()).isEqualTo(Unit.INR_CRORE);
        assertThat(latest.revenue().value()).isEqualByComparingTo("46355.55");
        assertThat(latest.revenue().currency().originalCurrency()).isEqualTo("INR");
        assertThat(latest.earningsPerShare().display()).isEqualTo("₹12.35 per share");
    }

    @Test
    void neverLabelsQuarterlyChartsWhoseCurrencyYahooDidNotState() {
        respond(quoteSummary(HDFC_FINANCIALS, HDFC_STATISTICS,
                HDFC_EARNINGS.replace("\"financialCurrency\": \"INR\",", "")));

        QuarterlyResult latest = provider.getReportedFinancials("HDFC Bank").recentQuarters().getLast();

        assertThat(latest.revenue().available()).isFalse();
        assertThat(latest.revenue().statusReason()).contains("currency is unknown");
        assertThat(latest.earningsPerShare().available()).isFalse();
    }

    @Test
    void recordsTheCurrencyAndItsNormalisationOnEveryAmount() {
        FinancialDataPoint revenue = hdfc().headline().revenue();

        assertThat(revenue.currency().originalCurrency()).isEqualTo("INR");
        assertThat(revenue.currency().normalizedCurrency()).isEqualTo("INR");
        assertThat(revenue.currency().conversion())
                .isEqualTo(com.ashish.stockresearch.research.model.CurrencyInfo.Conversion.SCALED_SAME_CURRENCY);
        assertThat(revenue.rawValue()).isEqualByComparingTo("2949644288000");
        assertThat(revenue.value()).isEqualByComparingTo("294964.43");
    }

    @Test
    void recordsWhichProviderRatiosYahooDerivesFromItsRevenueFigure() {
        ReportedFinancials financials = hdfc();

        assertThat(financials.headline().netProfitMarginPercent().dependsOnFields())
                .contains("financialData.profitMargins", "financialData.totalRevenue");
        assertThat(financials.headline().operatingMarginPercent().dependsOnFields()).contains("financialData.totalRevenue");
        assertThat(financials.headline().quarterlyRevenueGrowthYoyPercent().dependsOnFields())
                .contains("financialData.totalRevenue");
        assertThat(financials.headline().returnOnEquityPercent().dependsOnFields())
                .containsExactly("financialData.returnOnEquity");
    }

    @Test
    void keepsTheEarningsModulesYearlyFiguresOnlyForCrossChecking() {
        String withYearly = HDFC_EARNINGS.replace("\"financialsChart\": { \"quarterly\": [", """
                "financialsChart": { "yearly": [
                    { "date": 2025, "revenue": { "raw": 2870217300000, "fmt": "2.87T" }, "earnings": { "raw": 707922500000, "fmt": "707.92B" } },
                    { "date": 2026, "revenue": { "raw": 1912186100000, "fmt": "1.91T" }, "earnings": { "raw": 760259700000, "fmt": "760.26B" } },
                    { "date": 2027, "revenue": { "raw": 1000000000000, "fmt": "1T" }, "earnings": { "raw": 1000000000, "fmt": "1B" } }
                  ], "quarterly": [""");
        respond(quoteSummary(HDFC_FINANCIALS, HDFC_STATISTICS, withYearly));

        ReportedFinancials financials = provider.getReportedFinancials("HDFC Bank");

        // FY27 has not ended, so it is dropped; FY25 and FY26 are placed by the fiscal-year end.
        assertThat(financials.annualCrossCheckFigures()).extracting(y -> y.period().label())
                .containsExactly("FY25", "FY26");
        assertThat(financials.annualCrossCheckFigures().get(0).revenue().value()).isEqualByComparingTo("287021.73");
        assertThat(financials.annualCrossCheckFigures().get(0).revenue().source().sourceField())
                .isEqualTo("earnings.financialsChart.yearly.revenue");
    }
}
