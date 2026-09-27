package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.HistoricalPerformanceService;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.HeadlineFinancials;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.provider.UnsupportedShareholdingProvider;
import com.ashish.stockresearch.research.report.Observation;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end regression over the whole evidence pipeline - Yahoo payloads,
 * providers, calculation services, report assembly, rendering and the
 * interpretation filter - using real Yahoo Finance responses for Tech
 * Mahindra and HDFC Bank captured in September 2026 (src/test/resources/
 * fixtures/yahoo). No network, no model: the model's output is represented by
 * canned interpretations containing the exact mistakes this pipeline must stop.
 */
class ResearchPipelineRegressionTest {

    private static final Clock SEPT_2026 = Clock.fixed(Instant.parse("2026-09-27T06:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private final ResearchReportRenderer renderer = new ResearchReportRenderer();
    private final UnsupportedClaimFilter filter = new UnsupportedClaimFilter();

    /** Runs the full pipeline for one company against its recorded Yahoo responses. */
    private StockResearchReport report(String symbol) {
        String name = symbol.toLowerCase();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://query1.finance.yahoo.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(ExpectedCount.manyTimes(), requestTo(containsString("/v10/finance/quoteSummary/" + symbol + ".NS")))
                .andRespond(withSuccess(fixture(name + "-quotesummary.json"), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(containsString("/timeseries/" + symbol + ".NS")))
                .andRespond(withSuccess(fixture(name + "-timeseries.json"), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(containsString("/v8/finance/chart/" + symbol + ".NS")))
                .andRespond(withSuccess(fixture(name + "-chart-5y.json"), MediaType.APPLICATION_JSON));

        YahooSymbolResolver resolver = mock(YahooSymbolResolver.class);
        when(resolver.resolve(any())).thenReturn(Optional.of(
                new YahooSymbolResolver.Resolution(symbol + ".NS", symbol, "NSE", null)));
        YahooCrumbProvider crumbs = mock(YahooCrumbProvider.class);
        when(crumbs.get()).thenReturn(Optional.of(new YahooCrumbProvider.Credentials("A3=c", "crumb", Instant.MAX)));

        RestClient client = builder.build();
        YahooQuoteSummaryClient quoteSummary = new YahooQuoteSummaryClient(client, crumbs);
        FinancialDataValidator validator = new FinancialDataValidator(SEPT_2026);
        StockResearchService research = new StockResearchService(
                new YahooFinanceCompanyProfileProvider(resolver, quoteSummary, validator),
                new YahooFinanceFinancialDataProvider(resolver, quoteSummary, validator),
                new UnsupportedShareholdingProvider(),
                new YahooFinanceHistoricalMarketDataProvider(client, resolver),
                new FinancialMetricsService(), new HistoricalPerformanceService());
        return new ResearchReportService(research, validator, new SectorClassifier()).build(symbol);
    }

    private static String fixture(String file) {
        try {
            return new ClassPathResource("fixtures/yahoo/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static FinancialSummary financials(StockResearchReport report) {
        assertThat(report.financials().success()).as(report.financials().message()).isTrue();
        return report.financials().financialSummary();
    }

    private static List<FinancialDataPoint> everyPoint(StockResearchReport report) {
        FinancialSummary f = financials(report);
        HeadlineFinancials h = f.reported().headline();
        List<FinancialDataPoint> points = new ArrayList<>(List.of(h.revenue(), h.netProfit(), h.ebitda(),
                h.freeCashFlow(), h.totalDebt(), h.totalCash(), h.operatingMarginPercent(), h.netProfitMarginPercent(),
                h.returnOnEquityPercent(), h.returnOnAssetsPercent(), h.debtToEquityPercent(),
                h.quarterlyRevenueGrowthYoyPercent(), h.quarterlyEarningsGrowthYoyPercent(),
                h.returnOnCapitalEmployedPercent(), f.calculated().revenueCagrPercent(),
                f.calculated().netProfitCagrPercent(), f.calculated().netProfitMarginTtmPercent(),
                report.companyProfile().companyProfile().marketCap()));
        for (AnnualRatios r : f.calculated().annual()) {
            points.addAll(List.of(r.revenueGrowthYoyPercent(), r.netProfitGrowthYoyPercent(), r.netProfitMarginPercent(),
                    r.operatingMarginPercent(), r.returnOnEquityPercent(), r.returnOnAssetsPercent(),
                    r.debtToEquityMultiple()));
        }
        f.reported().annualHistory().forEach(y -> points.addAll(List.of(y.revenue(), y.netProfit(),
                y.operatingIncome(), y.shareholdersEquity(), y.totalAssets(), y.totalDebt())));
        return points;
    }

    private static String section(String markdown, String from, String to) {
        return markdown.substring(markdown.indexOf(from), markdown.indexOf(to));
    }

    // --- Source attribution ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TECHM", "HDFCBANK"})
    void attributesEveryFigureToYahooFinanceAndNeverToFilingsOrExchangeData(String symbol) {
        StockResearchReport report = report(symbol);
        String markdown = renderer.render(report, "x");
        String evidence = renderer.renderEvidence(report);

        assertThat(report.sources()).isNotEmpty().allSatisfy(source -> {
            assertThat(source.source()).isEqualTo("Yahoo Finance");
            assertThat(source.sourceType().name()).isEqualTo("MARKET_DATA_PROVIDER");
        });
        assertThat(everyPoint(report)).filteredOn(p -> p.source() != null)
                .allSatisfy(p -> assertThat(p.source().source()).isEqualTo("Yahoo Finance"));
        for (String text : List.of(markdown, evidence)) {
            assertThat(text).doesNotContainIgnoringCase("filing").doesNotContainIgnoringCase("exchange data")
                    .doesNotContainIgnoringCase("audited");
        }
    }

    // --- Units: percentage vs ratio vs multiple ------------------------------------------------

    @Test
    void keepsTechMahindrasDebtToEquityAsThePercentageYahooDeclaredItToBe() {
        FinancialDataPoint debtToEquity = financials(report("TECHM")).reported().headline().debtToEquityPercent();

        // Yahoo sends raw 7.269 with fmt "7.27%": percentage points - not 7.27x, not 727%.
        assertThat(debtToEquity.rawValue()).isEqualByComparingTo("7.269");
        assertThat(debtToEquity.providerUnit()).isEqualTo(ProviderUnit.PERCENTAGE);
        assertThat(debtToEquity.unit()).isEqualTo(Unit.PERCENT);
        assertThat(debtToEquity.value()).isEqualByComparingTo("7.27");
        assertThat(debtToEquity.display()).isEqualTo("7.27%");
        assertThat(debtToEquity.source().sourceField()).isEqualTo("financialData.debtToEquity");
    }

    @Test
    void calculatesTechMahindrasDebtToEquityAsAMultipleFromSameDateBalances() {
        AnnualRatios fy26 = financials(report("TECHM")).calculated().annual().getLast();

        // 2,186.20 crore debt / 29,615.40 crore equity at 2026-03-31 = 0.07x
        assertThat(fy26.period().label()).isEqualTo("FY26");
        assertThat(fy26.debtToEquityMultiple().unit()).isEqualTo(Unit.MULTIPLE);
        assertThat(fy26.debtToEquityMultiple().display()).isEqualTo("0.07x");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TECHM", "HDFCBANK"})
    void neverTreatsAPercentageAsAMultipleOrTheReverse(String symbol) {
        StockResearchReport report = report(symbol);

        assertThat(everyPoint(report)).filteredOn(FinancialDataPoint::available).allSatisfy(p -> {
            if (p.unit() == Unit.PERCENT) {
                assertThat(p.display()).as(p.metric()).endsWith("%");
                if (p.calculationStatus() == CalculationStatus.REPORTED) {
                    assertThat(p.providerUnit()).as(p.metric()).isIn(ProviderUnit.FRACTION, ProviderUnit.PERCENTAGE);
                }
            }
            if (p.unit() == Unit.MULTIPLE) {
                assertThat(p.display()).as(p.metric()).endsWith("x");
                assertThat(p.calculation()).as(p.metric()).contains("a multiple, not a percentage");
            }
            if (p.unit() == Unit.INR_CRORE && p.calculationStatus() == CalculationStatus.REPORTED) {
                assertThat(p.providerUnit()).as(p.metric()).isEqualTo(ProviderUnit.WHOLE_CURRENCY_UNITS);
                assertThat(p.value()).as(p.metric()).isEqualByComparingTo(p.rawValue().movePointLeft(7)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
            }
        });
        assertThat(renderer.render(report, "x")).doesNotContain("7.27x");
    }

    @Test
    void keepsEveryMetricsSourceOfTruthFields() {
        FinancialDataPoint margin = financials(report("TECHM")).reported().headline().netProfitMarginPercent();

        assertThat(margin.metric()).isEqualTo("netProfitMarginPercent");
        assertThat(margin.source().source()).isEqualTo("Yahoo Finance");
        assertThat(margin.source().sourceField()).isEqualTo("financialData.profitMargins");
        assertThat(margin.period().label()).isEqualTo("TTM to Q1 FY27 (ending 2026-06-30)");
        assertThat(margin.rawValue()).isEqualByComparingTo("0.086780004");
        assertThat(margin.providerUnit()).isEqualTo(ProviderUnit.FRACTION);
        assertThat(margin.value()).isEqualByComparingTo("8.68");
        assertThat(margin.unit()).isEqualTo(Unit.PERCENT);
        assertThat(margin.calculationStatus()).isEqualTo(CalculationStatus.REPORTED);
    }

    @Test
    void normalisesBothMarketCapsWithoutATenfoldError() {
        assertThat(report("TECHM").companyProfile().companyProfile().marketCap().display())
                .isEqualTo("₹1.37 lakh crore (₹1,37,141 crore)");
        assertThat(report("HDFCBANK").companyProfile().companyProfile().marketCap().display())
                .isEqualTo("₹11.34 lakh crore (₹11,34,183 crore)");
    }

    // --- Leverage labels -----------------------------------------------------------------------

    @Test
    void neverLabelsTechMahindrasDebtToEquityAsHighLeverage() {
        StockResearchReport report = report("TECHM");
        String markdown = renderer.render(report, "x");
        String evidence = renderer.renderEvidence(report);

        assertThat(markdown).doesNotContainIgnoringCase("leverage");

        UnsupportedClaimFilter.Result screened = filter.filter(
                "Debt-to-equity was 7.27% at 2026-06-30. A debt-to-equity of 7.27 indicates high leverage. "
                        + "The company appears highly leveraged. Total debt-to-equity remained low at 7.27% (0.07x).",
                evidence);
        assertThat(screened.text()).isEqualTo("Debt-to-equity was 7.27% at 2026-06-30.");
        assertThat(screened.text()).doesNotContainIgnoringCase("leverage");
    }

    // --- Growth terminology ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TECHM", "HDFCBANK"})
    void neverDescribesDifferingCagrsAsAcceleration(String symbol) {
        StockResearchReport report = report(symbol);
        String observations = section(renderer.render(report, "x"), "## 2. OBSERVATIONS", "## 3.");

        assertThat(Stream.concat(report.observations().stream(), report.withheldObservations().stream()))
                .extracting(Observation::statement)
                .allSatisfy(s -> assertThat(s).doesNotContainIgnoringCase("accelerat")
                        .doesNotContainIgnoringCase("decelerat").doesNotContainIgnoringCase("momentum"));
        assertThat(observations).doesNotContainIgnoringCase("accelerat");

        // Tech Mahindra's own business summary contains "acceleration" (a product name) - that must not
        // make an acceleration claim look supported.
        UnsupportedClaimFilter.Result screened = filter.filter(
                "Net profit grew at a lower compound rate than before. Profit growth accelerated as the CAGR rose.",
                renderer.renderEvidence(report));
        assertThat(screened.text()).isEqualTo("Net profit grew at a lower compound rate than before.");
    }

    @Test
    void usesCompoundRateWordingForCagrObservations() {
        assertThat(report("TECHM").observations()).extracting(Observation::statement)
                .anySatisfy(s -> assertThat(s).startsWith("Net profit grew at a compound annual rate of"));
    }

    // --- Conflicting revenue definitions --------------------------------------------------------

    @Test
    void reportsHdfcBanksConflictingRevenueDefinitionsWithoutReconcilingThem() {
        StockResearchReport report = report("HDFCBANK");
        FinancialSummary f = financials(report);

        List<DataQualityIssue> conflicts = report.dataQualityIssues().stream()
                .filter(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT).toList();
        assertThat(conflicts).anySatisfy(c -> assertThat(c.affectedFields())
                .contains("financialData.totalRevenue", "fundamentals-timeseries.annualTotalRevenue"));
        assertThat(conflicts).anySatisfy(c -> assertThat(c.affectedFields())
                .contains("financialData.totalRevenue", "earnings.financialsChart.quarterly.revenue"));

        // Both sides are kept exactly as Yahoo reported them - nothing picked, averaged or adjusted.
        assertThat(f.reported().headline().revenue().value()).isEqualByComparingTo("294964.43");
        assertThat(f.reported().annualHistory().getLast().revenue().value()).isEqualByComparingTo("192566.78");

        // No conclusion is drawn from any conflicting field.
        assertThat(report.observations()).allSatisfy(o ->
                assertThat(o.inputFields()).doesNotContainAnyElementsOf(report.conflictedFields()));
        assertThat(report.withheldObservations()).extracting(Observation::statement)
                .anySatisfy(s -> assertThat(s).startsWith("Revenue grew at a compound annual rate"));

        String markdown = renderer.render(report, "x");
        assertThat(markdown.indexOf("DATA CONFLICT - read before the figures below"))
                .isPositive().isLessThan(markdown.indexOf("## 1. FACTS"));
        assertThat(markdown).contains("₹2.95 lakh crore (₹2,94,964 crore) [DATA_CONFLICT]");
    }

    @Test
    void neverGivesTheModelTheConflictingValuesToDrawConclusionsFrom() {
        StockResearchReport report = report("HDFCBANK");
        String evidence = renderer.renderEvidence(report);
        String facts = evidence.substring(evidence.indexOf("### Company"), evidence.indexOf("OBSERVATIONS"));

        // The reader still sees them (tagged); the model sees only that they are withheld.
        assertThat(facts).doesNotContain("16.90%").doesNotContain("36.60%").doesNotContain("[DATA_CONFLICT]")
                .contains("WITHHELD - depends on a field in a DATA_CONFLICT");
        assertThat(renderer.render(report, "x")).contains("16.90% [DATA_CONFLICT]");
        // Non-conflicting facts are still given to the model.
        assertThat(facts).contains("8.90%");
    }

    @Test
    void raisesNoRevenueConflictForTechMahindraWhoseFieldsAgree() {
        StockResearchReport report = report("TECHM");

        // TTM revenue equals the sum of its four quarters, and FY26 is within a quarter's growth.
        assertThat(report.dataQualityIssues()).isEmpty();
        assertThat(report.withheldObservations()).isEmpty();
    }

    // --- Missing data ---------------------------------------------------------------------------

    @Test
    void neverInventsHdfcBanksMissingMetrics() {
        StockResearchReport report = report("HDFCBANK");
        HeadlineFinancials h = financials(report).reported().headline();

        for (FinancialDataPoint missing : List.of(h.ebitda(), h.freeCashFlow(), h.debtToEquityPercent(),
                h.returnOnCapitalEmployedPercent())) {
            assertThat(missing.available()).as(missing.metric()).isFalse();
            assertThat(missing.value()).as(missing.metric()).isNull();
        }
        String markdown = renderer.render(report, "x");
        assertThat(markdown).contains("| ebitda | UNAVAILABLE - Yahoo Finance did not publish financialData.ebitda")
                .contains("Shareholding data was not available from the configured provider.");
    }

    @Test
    void neverInventsTechMahindrasMissingMetrics() {
        HeadlineFinancials h = financials(report("TECHM")).reported().headline();

        for (FinancialDataPoint missing : List.of(h.returnOnEquityPercent(), h.returnOnAssetsPercent(),
                h.freeCashFlow(), h.returnOnCapitalEmployedPercent())) {
            assertThat(missing.available()).as(missing.metric()).isFalse();
            assertThat(missing.value()).as(missing.metric()).isNull();
        }
    }

    // --- Sector context and periods -------------------------------------------------------------

    @Test
    void identifiesEachCompanysSectorBeforeApplyingSectorContext() {
        StockResearchReport bank = report("HDFCBANK");
        StockResearchReport it = report("TECHM");

        assertThat(bank.sectorContext().group()).isEqualTo(IndustryGroup.BANK);
        assertThat(bank.sectorContext().sectorMetricsNotAvailable()).contains("net interest margin");
        assertThat(renderer.render(bank, "x"))
                .contains("Sector metrics NOT SUPPORTED BY CURRENT DATA SOURCE: net interest margin");
        assertThat(it.sectorContext().group()).isEqualTo(IndustryGroup.IT_SERVICES);
        assertThat(it.sectorContext().lessMeaningfulMetrics()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TECHM", "HDFCBANK"})
    void identifiesQ1Fy27AsTheLatestReportedQuarter(String symbol) {
        assertThat(report(symbol).latestReportedQuarter().label()).isEqualTo("Q1 FY27");
    }
}
