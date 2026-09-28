package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.ValuationService;
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
import com.ashish.stockresearch.research.report.WithheldObservation;
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
                new FinancialMetricsService(), new HistoricalPerformanceService(),
                new YahooFinanceValuationProvider(resolver, quoteSummary),
                new ValuationService(java.math.BigDecimal.valueOf(5)));
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
                f.calculated().ocfToPat3yMultiple(), f.calculated().ocfToPat5yMultiple(),
                report.companyProfile().companyProfile().marketCap()));
        for (AnnualRatios r : f.calculated().annual()) {
            points.addAll(List.of(r.revenueGrowthYoyPercent(), r.netProfitGrowthYoyPercent(), r.netProfitMarginPercent(),
                    r.operatingMarginPercent(), r.returnOnEquityPercent(), r.returnOnAssetsPercent(),
                    r.debtToEquityMultiple(), r.returnOnCapitalEmployedPercent()));
        }
        f.reported().annualHistory().forEach(y -> points.addAll(List.of(y.revenue(), y.netProfit(),
                y.operatingIncome(), y.shareholdersEquity(), y.totalAssets(), y.totalDebt(), y.ebit(),
                y.currentLiabilities(), y.operatingCashFlow())));
        assertThat(report.valuation().success()).as(report.valuation().message()).isTrue();
        points.addAll(report.valuation().valuation().reported().points());
        points.addAll(report.valuation().valuation().metrics());
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
                if (p.calculationStatus() == CalculationStatus.REPORTED) {
                    // A provider multiple (P/E, price-to-book) whose fmt Yahoo confirmed as a plain multiple.
                    assertThat(p.providerUnit()).as(p.metric()).isEqualTo(ProviderUnit.MULTIPLE);
                } else {
                    assertThat(p.calculation()).as(p.metric()).contains("a multiple, not a percentage");
                }
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

        assertThat(Stream.concat(report.observations().stream().map(Observation::statement),
                        report.withheldObservations().stream().map(WithheldObservation::subject)))
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
        assertThat(report.withheldObservations()).extracting(WithheldObservation::subject)
                .anySatisfy(s -> assertThat(s).startsWith("Revenue CAGR"));

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
        // Nor is anything calculated from them: the reader sees the status where a value would be.
        assertThat(renderer.render(report, "x")).doesNotContain("16.90%")
                .contains("| revenueCagrPercent | DATA_CONFLICT |");
        // Non-conflicting facts are still given to the model.
        assertThat(facts).contains("₹11.34 lakh crore").contains("-31.04%");
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

    // --- Cross-company regressions (September 2026 testing) -----------------------------------

    private static String answer(String file) {
        try {
            return new ClassPathResource("fixtures/model-answers/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** What the chat model is handed when it calls the profile and financial tools for this company. */
    private String chatEvidence(StockResearchReport report) {
        return renderer.renderCompanyProfileTool(report.companyProfile(), report.sectorContext())
                + renderer.renderFinancialSummaryTool(report.financials());
    }

    @Test
    void hdfcBankRevenueConflictsAreDetectedInEveryComparison() {
        List<DataQualityIssue> conflicts = financials(report("HDFCBANK")).dataQualityIssues().stream()
                .filter(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT).toList();

        assertThat(conflicts).extracting(DataQualityIssue::affectedFields).contains(
                List.of("financialData.totalRevenue", "earnings.financialsChart.quarterly.revenue"),
                List.of("financialData.totalRevenue", "fundamentals-timeseries.annualTotalRevenue"),
                List.of("fundamentals-timeseries.annualTotalRevenue", "earnings.financialsChart.yearly.revenue"));
        // Yahoo's two annual feeds also disagree on net profit for every fiscal year (by 2.8% to 12.3%).
        assertThat(conflicts).anySatisfy(c -> assertThat(c.message())
                .contains("different fiscal-year net profit").contains("FY23: ₹49,544.69 crore vs ₹44,108.71 crore"));
    }

    @Test
    void hdfcBankConflictedRevenueMetricsAreNeverCalculatedOrUsedForConclusions() {
        StockResearchReport report = report("HDFCBANK");
        FinancialSummary f = financials(report);
        AnnualRatios fy26 = f.calculated().annual().getLast();

        for (FinancialDataPoint metric : List.of(f.calculated().revenueCagrPercent(), fy26.revenueGrowthYoyPercent(),
                fy26.netProfitMarginPercent(), f.calculated().netProfitMarginTtmPercent(),
                f.calculated().latestQuarterRevenueGrowthQoqPercent())) {
            assertThat(metric.status()).as(metric.metric()).isEqualTo(com.ashish.stockresearch.research.model.DataStatus.DATA_CONFLICT);
            assertThat(metric.value()).as(metric.metric()).isNull();
        }
        assertThat(report.observations()).noneSatisfy(o -> assertThat(o.statement()).containsIgnoringCase("revenue"));
        assertThat(report.withheldObservations())
                .filteredOn(w -> w.topic().equals("revenue growth"))
                .extracting(WithheldObservation::reason).containsOnly(WithheldObservation.Reason.DATA_CONFLICT);
        assertThat(report.withheldTopics()).contains("revenue growth", "net profit margin");

        String markdown = renderer.render(report, "x");
        assertThat(section(markdown, "## 4. INTERPRETATION", "## 5. SOURCES"))
                .contains("**Conclusions withheld.** No interpretation was generated about: revenue growth");
        assertThat(renderer.renderEvidence(report))
                .contains("NOT GENERATED - write no interpretation, trend or conclusion about: revenue growth");
    }

    @Test
    void hdfcBankSectorGuidanceIsRespected() {
        StockResearchReport report = report("HDFCBANK");

        assertThat(report.sectorContext().group()).isEqualTo(IndustryGroup.BANK);
        // Debt-to-equity is calculated (0.81x, a fact) but never turned into an observation for a bank.
        assertThat(financials(report).calculated().annual().getLast().debtToEquityMultiple().display()).isEqualTo("0.81x");
        assertThat(report.observations()).noneSatisfy(o -> assertThat(o.statement()).contains("shareholders' equity"));
        assertThat(report.withheldObservations()).anySatisfy(w -> {
            assertThat(w.topic()).isEqualTo("debt-to-equity");
            assertThat(w.reason()).isEqualTo(WithheldObservation.Reason.SECTOR_CONTEXT);
            assertThat(w.detail()).contains("not a primary indicator").contains("BANK");
        });

        // The interpretation the model actually wrote for HDFC Bank in September 2026, screened again.
        UnsupportedClaimFilter.Result screened = filter.filter(answer("hdfcbank-interpretation.md"),
                renderer.renderEvidence(report), report.withheldTopics());
        assertThat(screened.text())
                .doesNotContain("stable capital structure")
                .doesNotContain("4.65% year-over-year increase in net profit")
                .doesNotContain("12.47% compound annual rate")
                .contains("limit confidence in financial performance comparisons");
        assertThat(screened.removedSentences()).anySatisfy(s -> assertThat(s).contains("0.81x"));
    }

    @Test
    void techMahindrasYahooFiguresAreInternallyConsistentAndEveryCalculationVerifies() {
        FinancialSummary f = financials(report("TECHM"));

        assertThat(f.dataQualityIssues()).isEmpty();
        assertThat(f.calculated().points()).filteredOn(p -> p.status() != com.ashish.stockresearch.research.model.DataStatus.UNAVAILABLE)
                .allSatisfy(p -> assertThat(p.status()).as(p.metric())
                        .isEqualTo(com.ashish.stockresearch.research.model.DataStatus.VALID));
        // FY26 revenue YoY reconciles with the displayed fiscal-year revenues: 56,815.40 / 52,988.30 - 1.
        AnnualRatios fy26 = f.calculated().annual().getLast();
        assertThat(fy26.revenueGrowthYoyPercent().display()).isEqualTo("7.22%");
        assertThat(fy26.revenueGrowthYoyPercent().inputs()).extracting(i -> i.value().toPlainString())
                .containsExactly("52988.30", "56815.40");
    }

    @Test
    void techMahindraChatAnswerWithInconsistentRevenueFiguresIsCaughtAndWithheld() {
        // The answer the model wrote for "Fundamental Overview of TECHM": revenue figures that are not
        // Yahoo's (FY23 was 53,290.20 crore, not 38,615.40), and a growth rate it computed itself.
        StockResearchReport report = report("TECHM");
        com.ashish.stockresearch.research.report.NumericClaimVerifier.Result checked =
                new com.ashish.stockresearch.research.report.NumericClaimVerifier()
                        .verify(answer("techm-fundamental-overview.md"), chatEvidence(report));

        assertThat(checked.ungroundedFigures()).contains("38,615.40", "36,815.40", "37,615.40", "50.7");
        assertThat(checked.text())
                .doesNotContain("38,615.40").doesNotContain("36,815.40").doesNotContain("37,615.40")
                .doesNotContain("50.7%")
                // Figures that are the application's survive, exactly as displayed.
                .contains("₹56,815.40 crore (↑7.22% YoY)")
                .contains("0.07x");
    }

    // --- Step 2: valuation, cash conversion and ROCE on the recorded Yahoo data -----------------

    @Test
    void techMahindrasCashConversionAndRoceAreCalculatedFromItsStatements() {
        StockResearchReport report = report("TECHM");
        FinancialSummary f = financials(report);

        // (6,376.10 + 5,786.10 + 6,172.10) / (2,357.80 + 4,251.50 + 4,811.40) crore, FY24 to FY26
        FinancialDataPoint ocfToPat = f.calculated().ocfToPat3yMultiple();
        assertThat(ocfToPat.status()).isEqualTo(com.ashish.stockresearch.research.model.DataStatus.VALID);
        assertThat(ocfToPat.period().label()).isEqualTo("FY24 to FY26 (3 years)");
        assertThat(ocfToPat.display()).isEqualTo("1.61x");
        // Yahoo supplies four fiscal years, so there is no five-year figure - and it says why.
        assertThat(f.calculated().ocfToPat5yMultiple().status())
                .isEqualTo(com.ashish.stockresearch.research.model.DataStatus.UNAVAILABLE);
        assertThat(f.calculated().ocfToPat5yMultiple().statusReason()).contains("4 available (FY23 to FY26)");
        FinancialDataPoint roce = f.calculated().annual().getLast().returnOnCapitalEmployedPercent();
        assertThat(roce.status()).isEqualTo(com.ashish.stockresearch.research.model.DataStatus.VALID);
        assertThat(roce.inputs()).hasSize(5);
        assertThat(report.observations()).anySatisfy(o -> assertThat(o.statement())
                .isEqualTo("Operating cash flow over FY24 to FY26 (3 years) was 1.61x net profit over the same years."));
        assertThat(report.observations()).anySatisfy(o -> assertThat(o.statement())
                .startsWith("Return on capital employed for FY26 was " + roce.display()));
    }

    @Test
    void hdfcBankGetsNoObservationFromCashConversionOrRoce() {
        StockResearchReport report = report("HDFCBANK");
        FinancialSummary f = financials(report);

        // Yahoo's two feeds disagree on HDFC Bank's fiscal-year net profit, so cash conversion is not calculated.
        assertThat(f.calculated().ocfToPat3yMultiple().status())
                .isEqualTo(com.ashish.stockresearch.research.model.DataStatus.DATA_CONFLICT);
        assertThat(f.calculated().ocfToPat3yMultiple().value()).isNull();
        // Whatever its status, a bank's operating cash flow (deposit and lending flows) gives no observation;
        // ResearchReportServiceTest shows the same for a VALID figure.
        assertThat(report.observations()).noneSatisfy(o -> assertThat(o.topic()).isIn("cash conversion",
                "return on capital employed"));
        assertThat(report.withheldObservations()).anySatisfy(w -> {
            assertThat(w.topic()).isEqualTo("cash conversion");
            assertThat(w.reason()).isEqualTo(WithheldObservation.Reason.SECTOR_CONTEXT);
        });
        // Yahoo publishes no EBIT or current liabilities for a bank, so there is no ROCE at all.
        assertThat(f.calculated().annual().getLast().returnOnCapitalEmployedPercent().status())
                .isEqualTo(com.ashish.stockresearch.research.model.DataStatus.UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TECHM", "HDFCBANK"})
    void theReportStatesValuationAsDatedFactsCheckedAgainstThePrice(String symbol) {
        StockResearchReport report = report(symbol);
        String markdown = renderer.render(report, "x");
        String evidence = renderer.renderEvidence(report);

        var valuation = report.valuation().valuation();
        assertThat(valuation.metrics()).allSatisfy(p -> assertThat(p.status()).as(p.metric())
                .isEqualTo(com.ashish.stockresearch.research.model.DataStatus.VALID));
        assertThat(valuation.dataQualityIssues()).isEmpty();
        for (String text : List.of(markdown, evidence)) {
            assertThat(text).contains("### Valuation (point in time)")
                    .contains("| trailingPe | " + valuation.trailingPe().display() + " | As of 2026-09-25 |")
                    .contains("no fair value, price target or recommendation");
        }
        assertThat(report.sources()).anySatisfy(s -> assertThat(s.covers()).contains("valuation multiples"));
    }
}
