package com.ashish.stockresearch.verdict;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.ScreeningFixtures;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import com.ashish.stockresearch.screening.ScreeningMetric;
import org.junit.jupiter.api.Test;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningFixtures.withStatus;
import static com.ashish.stockresearch.screening.ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.EARNINGS_YIELD_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.REVENUE_CAGR_5Y_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROCE_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_3Y_AVG_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROA_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.PRICE_TO_BOOK_MULTIPLE;
import static org.assertj.core.api.Assertions.assertThat;

/** The rating rule on hand-built snapshots, using the real configured presets (quality-compounder, reasonable-value). */
class VerdictEngineTest {

    private final VerdictEngine engine = new VerdictEngine(ScreeningPresetsTest.presets(), VerdictProperties.defaults());

    /** A non-financial company meeting all six quality rules; the price rule is earnings yield >= 4. */
    private static ScreeningFixtures.Builder allQualityMet(String earningsYield) {
        return stock("PHARMA1", IndustryGroup.PHARMA)
                .with(ROE_3Y_AVG_PERCENT, "20").with(ROCE_PERCENT, "20").with(REVENUE_CAGR_5Y_PERCENT, "12")
                .with(NET_PROFIT_CAGR_5Y_PERCENT, "12").with(OCF_TO_PAT_3Y_MULTIPLE, "1.0")
                .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").with(EARNINGS_YIELD_PERCENT, earningsYield);
    }

    @Test
    void qualityAndPriceBothMetIsBuy() {
        Verdict verdict = engine.assess(allQualityMet("5").build());

        assertThat(verdict.rating()).isEqualTo(Rating.BUY);
        assertThat(verdict.quality().met()).isEqualTo(6);
        assertThat(verdict.price().total()).isEqualTo(1);
    }

    @Test
    void goodQualityAtAPriceThatMissesTheRuleIsHold() {
        assertThat(engine.assess(allQualityMet("2").build()).rating()).isEqualTo(Rating.HOLD);
    }

    @Test
    void qualityAtExactlyTheBuyBoundaryNeedsFivePointFourOfSix() {
        // 5 of 6 = 83% >= 80%: still BUY; 4 of 6 = 66% is not
        FundamentalsSnapshot five = allQualityMet("5").with(ROCE_PERCENT, "10").build();
        FundamentalsSnapshot four = allQualityMet("5").with(ROCE_PERCENT, "10").with(DEBT_TO_EQUITY_MULTIPLE, "2").build();

        assertThat(engine.assess(five).rating()).isEqualTo(Rating.BUY);
        assertThat(engine.assess(four).rating()).isEqualTo(Rating.HOLD);
    }

    @Test
    void weakQualityIsSellWhateverThePrice() {
        FundamentalsSnapshot weak = allQualityMet("9").with(ROE_3Y_AVG_PERCENT, "2").with(ROCE_PERCENT, "2")
                .with(REVENUE_CAGR_5Y_PERCENT, "1").with(DEBT_TO_EQUITY_MULTIPLE, "3").build();

        Verdict verdict = engine.assess(weak);

        assertThat(verdict.rating()).isEqualTo(Rating.SELL);
        assertThat(verdict.quality().met()).isEqualTo(2);
    }

    // A check that cannot be run is never counted as met, and too many of them means no rating at all.
    @Test
    void tooFewCheckableRulesGivesNoRatingEvenWhenTheOnesThatCouldBeRunAllPass() {
        FundamentalsSnapshot partial = allQualityMet("5")
                .with(withStatus(ROE_3Y_AVG_PERCENT, DataStatus.INVALID, null))
                .with(withStatus(NET_PROFIT_CAGR_5Y_PERCENT, DataStatus.UNAVAILABLE, null))
                .with(withStatus(OCF_TO_PAT_3Y_MULTIPLE, DataStatus.INVALID, null)).build();

        Verdict verdict = engine.assess(partial);

        assertThat(verdict.rating()).isEqualTo(Rating.NO_RATING);
        assertThat(verdict.reason()).contains("3 of 6 quality");
        assertThat(verdict.quality().checkable()).isEqualTo(3);
        assertThat(verdict.quality().met()).isEqualTo(3);
    }

    @Test
    void anUnavailablePriceFigureGivesNoRatingRatherThanAssumingAPrice() {
        FundamentalsSnapshot noPrice = allQualityMet("5").with(withStatus(EARNINGS_YIELD_PERCENT,
                DataStatus.UNAVAILABLE, null)).build();

        assertThat(engine.assess(noPrice).rating()).isEqualTo(Rating.NO_RATING);
    }

    @Test
    void aBankIsRatedOnItsOwnRulesWithPriceToBookAsThePriceRule() {
        FundamentalsSnapshot bank = stock("BANK1", IndustryGroup.BANK).with(ROA_PERCENT, "1.5")
                .with(ROE_PERCENT, "16").with(PRICE_TO_BOOK_MULTIPLE, "2.0").build();

        Verdict verdict = engine.assess(bank);

        assertThat(verdict.rating()).isEqualTo(Rating.BUY);
        assertThat(verdict.price().results()).singleElement()
                .satisfies(result -> assertThat(result.rule().metric()).isEqualTo(ScreeningMetric.PRICE_TO_BOOK_MULTIPLE));
    }

    @Test
    void anUnknownIndustryIsNeverRated() {
        Verdict verdict = engine.assess(stock("X", IndustryGroup.UNKNOWN).with(ROE_PERCENT, "20").build());

        assertThat(verdict.rating()).isEqualTo(Rating.NO_RATING);
        assertThat(verdict.reason()).contains("industry could not be identified");
    }
}
