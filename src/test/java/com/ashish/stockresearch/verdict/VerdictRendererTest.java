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
    private final VerdictRenderer renderer = new VerdictRenderer();

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
        assertThat(md).contains("**Rating: No rating** - Too few of the checks could be run on reliable data")
                .contains("the application will not guess").contains("| Return on equity, average of the last 3 fiscal years ≥ 15.00% | Could not check | the figures it is built from contradict each other");
    }
}
