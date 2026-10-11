package com.ashish.stockresearch.verdict;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import org.junit.jupiter.api.Test;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningFixtures.withStatus;
import static com.ashish.stockresearch.screening.ScreeningMetric.*;
import static org.assertj.core.api.Assertions.assertThat;

class VerdictRendererTest {

    private final VerdictEngine engine = new VerdictEngine(ScreeningPresetsTest.presets(), VerdictProperties.defaults());
    private final VerdictRenderer renderer = new VerdictRenderer(com.ashish.stockresearch.glossary.TestGlossary.glossary());

    private String rendered(Rating expected, String earningsYield) {
        Verdict verdict = engine.assess(stock("PHARMA1", IndustryGroup.PHARMA)
                .with(ROE_3Y_AVG_PERCENT, "20").with(ROCE_PERCENT, "20").with(REVENUE_CAGR_5Y_PERCENT, "12")
                .with(NET_PROFIT_CAGR_5Y_PERCENT, "12").with(OCF_TO_PAT_3Y_MULTIPLE, "1.0")
                .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").with(EARNINGS_YIELD_PERCENT, earningsYield).build());
        assertThat(verdict.rating()).isEqualTo(expected);
        return renderer.render(verdict);
    }

    @Test
    void leadsWithTheRatingAndAlwaysCarriesTheNotAdviceNoticeAndWhatItDoesNotTellYou() {
        String md = rendered(Rating.BUY, "5");

        assertThat(md).startsWith("# PHARMA1 Limited (PHARMA1), in plain words\n\n**Rating: Buy**");
        assertThat(md).contains(VerdictRenderer.NOT_ADVICE).contains("SEBI-registered investment adviser")
                .contains("## What this rating does not tell you").contains("## Why this rating")
                .contains("## The five questions").contains("## Every check, with its figure");
    }

    @Test
    void showsAPlainBarForEachPillarAndTheRuleThatDecidedIt() {
        String md = rendered(Rating.HOLD, "2");

        assertThat(md).contains("**Quality of the business**  ▰▰▰▰▰▰  6 of 6 checks met")
                .contains("**Price you pay**  ▱  0 of 1 checks met")
                .contains("**Buy** when at least 80% of the quality checks and 60% of the price checks are met");
        assertThat(md).contains("| Earnings yield (100 / trailing P/E) at the snapshot's share price ≥ 4.00% | Not met |");
    }

    @Test
    void aRuleThatCouldNotBeCheckedSaysSoAndNeverReadsAsMet() {
        Verdict verdict = engine.assess(stock("P2", IndustryGroup.PHARMA)
                .with(withStatus(ROE_3Y_AVG_PERCENT, DataStatus.INVALID, null)).with(ROCE_PERCENT, "20").build());

        String md = renderer.render(verdict);

        assertThat(verdict.rating()).isEqualTo(Rating.NO_RATING);
        assertThat(md).contains("**Rating: No rating** - not enough reliable figures yet: 1 of 6 quality checks")
                .contains("The application will not guess").contains("| Return on equity, average of the last 3 fiscal years ≥ 15.00% | Could not check | the figures it is built from contradict each other");
    }

    // A chosen question shows only its own figures and the rating checks that belong to it.
    @Test
    void aSingleQuestionShowsItsFiguresAndOnlyItsOwnChecks() {
        Verdict verdict = engine.assess(stock("PHARMA1", IndustryGroup.PHARMA)
                .with(ROE_3Y_AVG_PERCENT, "20").with(ROCE_PERCENT, "20").with(REVENUE_CAGR_5Y_PERCENT, "12")
                .with(NET_PROFIT_CAGR_5Y_PERCENT, "8").with(OCF_TO_PAT_3Y_MULTIPLE, "1.0")
                .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").with(EARNINGS_YIELD_PERCENT, "5").build());

        String growing = renderer.question(verdict, com.ashish.stockresearch.glossary.MetricQuestion.GROWING);

        assertThat(growing).startsWith("### Is it growing?\n\n")
                .contains("- Met: revenue CAGR over the last 5 fiscal years ≥ 10.00% - 12.00%")
                .contains("- Not met: net profit CAGR over the last 5 fiscal years ≥ 10.00% - 8.00%")
                .doesNotContain("Return on equity").doesNotContain("Rating:");
        assertThat(renderer.rating(verdict)).startsWith("### Overall rating\n\n**Rating: Buy**")
                .contains(VerdictRenderer.NOT_ADVICE).doesNotContain("Is it growing?");
    }

    // A No rating says what is known, what could not be checked and why, in plain words.
    @Test
    void aNoRatingListsWhatIsKnownAndWhatCouldNotBeCheckedInPlainWords() {
        com.ashish.stockresearch.screening.SnapshotMetric shortHistory = new com.ashish.stockresearch.screening.SnapshotMetric(
                NET_PROFIT_CAGR_5Y_PERCENT, null, NET_PROFIT_CAGR_5Y_PERCENT.unit(), "UNAVAILABLE", DataStatus.UNAVAILABLE,
                "a 5-year net profit CAGR needs 6 fiscal years; 4 available", null, null, null, null);
        Verdict verdict = new VerdictEngine(ScreeningPresetsTest.presets(),
                new VerdictProperties(null, null, 0, 0, 0, 0, 0)).assess(stock("SKY", IndustryGroup.CONSUMER)
                .with(withStatus(ROE_3Y_AVG_PERCENT, DataStatus.UNAVAILABLE, null)).with(ROCE_PERCENT, "45.93")
                .with(withStatus(REVENUE_CAGR_5Y_PERCENT, DataStatus.UNAVAILABLE, null)).with(shortHistory)
                .with(withStatus(OCF_TO_PAT_3Y_MULTIPLE, DataStatus.UNAVAILABLE, null))
                .with(DEBT_TO_EQUITY_MULTIPLE, "0.73").with(EARNINGS_YIELD_PERCENT, "2").build());

        String md = renderer.rating(verdict);

        assertThat(verdict.rating()).isEqualTo(Rating.NO_RATING);
        assertThat(md).contains("**Rating: No rating** - not enough reliable figures yet: 2 of 6 quality checks")
                .contains("What could be checked:\n- Met: return on capital employed")
                .contains("- Not met: debt-to-equity")
                .contains("Could not be checked:\n")
                .contains("Net profit CAGR over the last 5 fiscal years ≥ 10.00%: only 4 years of full-year results "
                        + "are available for this company so far");
    }
}
