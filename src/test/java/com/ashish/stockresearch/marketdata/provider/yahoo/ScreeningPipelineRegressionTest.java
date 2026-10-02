package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.RuleOutcome;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import com.ashish.stockresearch.screening.ScreeningResult;
import com.ashish.stockresearch.screening.SnapshotBuilder;
import com.ashish.stockresearch.screening.StockScreener;
import com.ashish.stockresearch.screening.Universes;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Snapshots and screens built from the real Yahoo Finance responses for Tech Mahindra and HDFC Bank
 * (September 2026), through the same research layer a report uses.
 */
class ScreeningPipelineRegressionTest {

    private static FundamentalsSnapshot snapshot(String symbol) {
        return new SnapshotBuilder(ResearchPipelineRegressionTest.research(symbol), new SectorClassifier(),
                ResearchPipelineRegressionTest.SEPT_2026)
                .build(new Universes.Member(symbol, symbol + " Ltd.", "x"), LocalDate.of(2026, 9, 27));
    }

    // S12-2: a member is captured from its own NSE listing only
    @Test
    void neverStoresAnotherListingsFiguresUnderAMember() {
        // the resolver answers every symbol with Tech Mahindra, as a guessed ticker for an unknown symbol might
        SnapshotBuilder guessed = new SnapshotBuilder(ResearchPipelineRegressionTest.research("TECHM"),
                new SectorClassifier(), ResearchPipelineRegressionTest.SEPT_2026);
        SnapshotBuilder onBse = new SnapshotBuilder(ResearchPipelineRegressionTest.research("TECHM", "BSE"),
                new SectorClassifier(), ResearchPipelineRegressionTest.SEPT_2026);

        assertThatThrownBy(() -> guessed.build(new Universes.Member("DUMMYHEG", "Dummy HEG Ltd.", "Capital Goods"),
                LocalDate.of(2026, 9, 27)))
                .isInstanceOf(SnapshotBuilder.NothingRetrievedException.class)
                .hasMessage("the profile resolved to TECHM (NSE), not DUMMYHEG's own NSE listing, so nothing was stored");
        assertThatThrownBy(() -> onBse.build(new Universes.Member("TECHM", "Tech Mahindra Ltd.", "IT"),
                LocalDate.of(2026, 9, 27)))
                .isInstanceOf(SnapshotBuilder.NothingRetrievedException.class)
                .hasMessageContaining("resolved to TECHM (BSE), not TECHM's own NSE listing");
        // Yahoo's own fiscal years: none read from the filings
        assertThat(snapshot("TECHM").filedFiscalYears()).isZero();
    }

    private static Map<String, RuleOutcome> outcomes(ScreeningResult result, String symbol) {
        return result.ranked().stream().filter(stock -> stock.symbol().equals(symbol)).findFirst().orElseThrow()
                .results().stream().collect(Collectors.toMap(r -> r.rule().label(), ScreeningResult.RuleResult::outcome));
    }

    @Test
    void capturesTechMahindrasMetricsFromItsStatementsAndValuation() {
        FundamentalsSnapshot techm = snapshot("TECHM");

        assertThat(techm.industryGroup()).isEqualTo(IndustryGroup.IT_SERVICES);
        // Yahoo's four fiscal years, with no filings: every metric over at most 3 years is VALID, and none
        // over 5 is measured over fewer years than it says
        Set<ScreeningMetric> fiveYear = Set.of(ScreeningMetric.ROE_5Y_AVG_PERCENT, ScreeningMetric.REVENUE_CAGR_5Y_PERCENT,
                ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT, ScreeningMetric.OCF_TO_PAT_5Y_MULTIPLE);
        assertThat(techm.metrics().values()).filteredOn(m -> !fiveYear.contains(m.metric()))
                .allSatisfy(m -> assertThat(m.status()).as(m.metric().name()).isEqualTo(DataStatus.VALID));
        assertThat(fiveYear).allSatisfy(m -> assertThat(techm.metric(m).status()).as(m.name())
                .isEqualTo(DataStatus.UNAVAILABLE));
        assertThat(techm.metric(ScreeningMetric.REVENUE_CAGR_5Y_PERCENT).statusReason())
                .isEqualTo("a 5-year revenue CAGR needs 6 fiscal years; 4 available");
        // FY26 dividends paid 4,025.50 crore out of 6,172.00 crore operating cash flow
        assertThat(techm.metric(ScreeningMetric.DIVIDENDS_TO_OCF_PERCENT).value()).isEqualByComparingTo("65.22");
        assertThat(techm.metric(ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE).display()).isEqualTo("1.61x");
        assertThat(techm.metric(ScreeningMetric.REVENUE_CAGR_3Y_PERCENT).periodLabel()).isEqualTo("FY23 to FY26 (3 years)");
        assertThat(techm.metric(ScreeningMetric.ROE_3Y_AVG_PERCENT).periodLabel()).isEqualTo("FY24 to FY26 (3 years)");
        assertThat(techm.metric(ScreeningMetric.TRAILING_PE_MULTIPLE).usable()).isTrue();
        assertThat(techm.metric(ScreeningMetric.EARNINGS_YIELD_PERCENT).display()).isEqualTo("3.74%");
    }

    @Test
    void screensTechMahindraOnTheNonFinancialQualityRules() {
        ScreeningResult result = new StockScreener().screen(
                ScreeningPresetsTest.presets().find("quality-compounder").orElseThrow(), List.of(snapshot("TECHM")));

        assertThat(outcomes(result, "TECHM")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ROE 3y avg ≥ 15.00%", RuleOutcome.FAIL, // 13.76%
                "ROCE ≥ 15.00%", RuleOutcome.PASS, // 20.72%
                // Yahoo's four years cannot give a 5-year growth rate
                "Revenue CAGR 5y ≥ 10.00%", RuleOutcome.INSUFFICIENT_DATA,
                "Profit CAGR 5y ≥ 10.00%", RuleOutcome.INSUFFICIENT_DATA,
                "OCF/PAT 3y ≥ 0.80x", RuleOutcome.PASS, // 1.61x
                "D/E ≤ 0.50x", RuleOutcome.PASS)); // 0.07x
    }

    // HDFC Bank's two Yahoo net-profit feeds disagree, so its ROE and ROA are DATA_CONFLICT: the bank
    // rules cannot be assessed, and its (VALID) debt-to-equity is never evaluated.
    @Test
    void neverPassesHdfcBankOnDisputedProfitsAndNeverTestsItsDebtToEquity() {
        FundamentalsSnapshot hdfc = snapshot("HDFCBANK");
        ScreeningResult result = new StockScreener().screen(
                ScreeningPresetsTest.presets().find("quality-compounder").orElseThrow(), List.of(hdfc));

        assertThat(hdfc.industryGroup()).isEqualTo(IndustryGroup.BANK);
        assertThat(hdfc.metric(ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE).status()).isEqualTo(DataStatus.VALID);
        assertThat(hdfc.metric(ScreeningMetric.ROE_PERCENT).status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(outcomes(result, "HDFCBANK")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ROA ≥ 1.00%", RuleOutcome.INSUFFICIENT_DATA, "ROE ≥ 14.00%", RuleOutcome.INSUFFICIENT_DATA));
    }

    @Test
    void screensDividendsPaidAgainstCashFlowOnlyForTheNonFinancialCompany() {
        ScreeningResult result = new StockScreener().screen(ScreeningPresetsTest.presets().find("dividend").orElseThrow(),
                List.of(snapshot("TECHM"), snapshot("HDFCBANK")));

        assertThat(outcomes(result, "TECHM")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Dividend yield ≥ 2.00%", RuleOutcome.PASS, // 3.29%
                "Dividends/OCF ≤ 100.00%", RuleOutcome.PASS, // 65.22%
                "Profit CAGR 5y > 0.00%", RuleOutcome.INSUFFICIENT_DATA)); // four fiscal years
        assertThat(outcomes(result, "HDFCBANK")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Dividend yield ≥ 2.00%", RuleOutcome.FAIL, // 1.77%
                "Profit CAGR 5y > 0.00%", RuleOutcome.INSUFFICIENT_DATA));
    }
}
