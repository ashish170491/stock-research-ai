package com.ashish.stockresearch.research.report;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.ScreeningOutcome;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.StockScreener;
import com.ashish.stockresearch.screening.Universe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.ashish.stockresearch.screening.ScreeningFixtures.DATE;
import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningFixtures.withStatus;
import static com.ashish.stockresearch.screening.ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.REVENUE_CAGR_5Y_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROA_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROCE_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_3Y_AVG_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_PERCENT;
import static org.assertj.core.api.Assertions.assertThat;

/** Counts in an answer about a screen, checked against the application's own counts in the rendered screen. */
class ScreeningCountVerifierTest {

    private final ScreeningCountVerifier verifier = new ScreeningCountVerifier();

    /** TCS and SBIN meet every criterion; INFY has one it fails and one that cannot be assessed; HDFCLIFE is not screened. */
    static String screen() {
        List<FundamentalsSnapshot> snapshots = List.of(
                stock("TCS", IndustryGroup.IT_SERVICES).with(ROE_3Y_AVG_PERCENT, "40").with(ROE_PERCENT, "40")
                        .with(ROCE_PERCENT, "50").with(REVENUE_CAGR_5Y_PERCENT, "12").with(NET_PROFIT_CAGR_5Y_PERCENT, "11")
                        .with(OCF_TO_PAT_3Y_MULTIPLE, "1.1").with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build(),
                stock("INFY", IndustryGroup.IT_SERVICES).with(ROE_3Y_AVG_PERCENT, "30").with(ROE_PERCENT, "30")
                        .with(ROCE_PERCENT, "39").with(withStatus(REVENUE_CAGR_5Y_PERCENT, DataStatus.DATA_CONFLICT, null))
                        .with(NET_PROFIT_CAGR_5Y_PERCENT, "5").with(OCF_TO_PAT_3Y_MULTIPLE, "1.0")
                        .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build(),
                stock("SBIN", IndustryGroup.BANK).with(ROA_PERCENT, "1.07").with(ROE_PERCENT, "15.38").build(),
                stock("HDFCLIFE", IndustryGroup.INSURANCE).with(ROE_PERCENT, "12").build());
        var criteria = ScreeningPresetsTest.presets().find("quality-compounder").orElseThrow();
        SnapshotRun run = new SnapshotRun(1, Universe.NIFTY50, DATE, Instant.parse("2026-09-28T13:00:00Z"),
                Instant.parse("2026-09-28T13:01:00Z"), SnapshotRun.Status.COMPLETED, 50, 50, 0);
        return new ScreeningRenderer().render(new ScreeningOutcome(true, ScreeningOutcome.Status.OK, null,
                Universe.NIFTY50, run, new StockScreener().screen(criteria, snapshots), List.of(), 1));
    }

    @Test
    void theFixtureScreenStatesTheCountsTheTestsRelyOn() {
        assertThat(screen()).contains(ScreeningCountVerifier.SUMMARY_HEADING)
                .contains("- 2 of the 3 screened stocks meet every criterion")
                .contains("- 1 of the 3 have at least one criterion that could not be assessed")
                .contains("| 4 of 6 |");
    }

    @Test
    void keepsCountsTheScreenStates() {
        String answer = "2 of the 3 screened stocks meet every criterion: TCS and SBIN. 1 of the 3 has a criterion that "
                + "could not be assessed. INFY meets 4 of 6 criteria. 1 stock was not screened. No stocks were left out.";

        ScreeningCountVerifier.Result result = verifier.verify(answer, screen());

        assertThat(result.text()).isEqualTo(answer);
        assertThat(result.removedStatements()).isEmpty();
    }

    // The 2026-09-29 live answer: "only 12 stocks meeting all criteria" when the Summary said 3.
    @Test
    void removesACountOfStocksMeetingEveryCriterionThatIsNotTheSummarys() {
        ScreeningCountVerifier.Result result = verifier.verify("TCS and SBIN meet every criterion. Non-financial "
                + "sectors had wide data gaps, with only 12 stocks meeting all criteria. Three stocks meet every "
                + "criterion.", screen());

        assertThat(result.text()).isEqualTo("TCS and SBIN meet every criterion.");
        assertThat(result.wrongCounts()).containsExactly("12 stocks meeting all", "Three stocks meet every");
    }

    // The count of stocks with a criterion that could not be assessed, and of those not screened, name no stocks.
    @Test
    void checksTheCountsOfUnassessedAndUnscreenedStocksAgainstTheSummary() {
        ScreeningCountVerifier.Result result = verifier.verify("1 of the 3 have at least one criterion that could not "
                + "be assessed. 5 have at least one criterion that could not be assessed. Two have INSUFFICIENT_DATA "
                + "results. 1 was not screened. 2 were not screened.", screen());

        assertThat(result.text()).isEqualTo("1 of the 3 have at least one criterion that could not be assessed. "
                + "1 was not screened.");
        assertThat(result.wrongCounts()).containsExactly(
                "5 have at least one criterion that could not be assessed", "Two have INSUFFICIENT_DATA",
                "2 were not screened");
    }

    @Test
    void removesAPairOrACountTheScreenDoesNotState() {
        ScreeningCountVerifier.Result result = verifier.verify("INFY meets 5 of 6 criteria. 5 of the 46 have gaps. "
                + "SBIN has an ROE of 15.38 for 7 companies. The figures cover 12 months.", screen());

        assertThat(result.text()).isEqualTo("The figures cover 12 months.");
        assertThat(result.wrongCounts()).containsExactly("5 of 6", "5 of the 46", "7 companies");
    }

    @Test
    void checksNothingWithoutAScreenInTheEvidence() {
        String answer = "Only 12 stocks meet all criteria.";

        assertThat(verifier.verify(answer, "TOOL RESULT: getFinancialSummary").text()).isEqualTo(answer);
    }

    @Test
    void theAnswerChecksApplyTheCountCheckAndSaySo() {
        AnswerChecks checks = new AnswerChecks(new NumericClaimVerifier(), verifier, new UnsupportedClaimFilter());

        AnswerChecks.Checked checked = checks.check("2 of the 3 screened stocks meet every criterion. Only 12 stocks "
                + "meet every criterion.", screen());

        assertThat(checked.removed()).isEqualTo(1);
        assertThat(checked.text()).startsWith("2 of the 3 screened stocks meet every criterion.")
                .contains("_Removed 1 statement(s) with counts the screen does not state (the model miscounted the "
                        + "table): 12 stocks meet every._");
    }
}
