package com.ashish.stockresearch.moat;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.RuleOutcome;
import com.ashish.stockresearch.screening.ScreeningFixtures;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import com.ashish.stockresearch.screening.SnapshotMetric;
import com.ashish.stockresearch.screening.StockScreener;
import org.junit.jupiter.api.Test;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningMetric.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The signs are the moat-signs preset's rules for the company's group, decided as a screen decides them. */
class MoatAssessorTest {

    private final MoatAssessor assessor = new MoatAssessor(ScreeningPresetsTest.presets(), MoatProperties.defaults());

    @Test
    void aNonFinancialCompanyIsCheckedOnReturnsMarginCashGrowthAndDebt() {
        MoatSigns signs = assessor.assess(stock("ASIANPAINT", IndustryGroup.CONSUMER)
                .with(ROCE_5Y_MIN_PERCENT, "24").with(OPERATING_MARGIN_5Y_RANGE, "3.4")
                .with(OCF_TO_PAT_5Y_MULTIPLE, "0.95").with(REVENUE_CAGR_5Y_PERCENT, "8")
                .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build());

        assertThat(signs.signs()).extracting(sign -> sign.asked().metric()).containsExactlyInAnyOrder(
                ROCE_5Y_MIN_PERCENT, OPERATING_MARGIN_5Y_RANGE, OCF_TO_PAT_5Y_MULTIPLE, REVENUE_CAGR_5Y_PERCENT,
                DEBT_TO_EQUITY_MULTIPLE);
        assertThat(signs.found()).isEqualTo(4);
        assertThat(signs.checkable()).isEqualTo(5);
        assertThat(signs.withOutcome(RuleOutcome.FAIL)).singleElement()
                .satisfies(sign -> assertThat(sign.asked().metric()).isEqualTo(REVENUE_CAGR_5Y_PERCENT));
        assertThat(signs.notes()).isEmpty();
    }

    @Test
    void aBankIsCheckedOnItsLowestReturnOnEquityAndProfitGrowthNeverOnMarginsOrDebt() {
        MoatSigns signs = assessor.assess(stock("HDFCBANK", IndustryGroup.BANK)
                .with(ROE_5Y_MIN_PERCENT, "14.5").with(NET_PROFIT_CAGR_5Y_PERCENT, "18").build());

        assertThat(signs.signs()).extracting(sign -> sign.asked().metric())
                .containsExactlyInAnyOrder(ROE_5Y_MIN_PERCENT, NET_PROFIT_CAGR_5Y_PERCENT);
        assertThat(signs.found()).isEqualTo(2);
    }

    @Test
    void anInsurerHasNoSignsAndTheReasonSaysSo() {
        MoatSigns signs = assessor.assess(stock("HDFCLIFE", IndustryGroup.INSURANCE).build());

        assertThat(signs.total()).isZero();
        assertThat(assessor.whyNoSigns(signs)).contains("moat-signs").contains("INSURANCE");
    }

    @Test
    void aFiveYearSignTheCompanyCannotHaveYetIsCheckedOverThreeAndSaysSo() {
        MoatSigns signs = assessor.assess(stock("SKYGOLD", IndustryGroup.CONSUMER)
                .with(SnapshotMetric.unavailable(ROCE_5Y_MIN_PERCENT, "needs 5 fiscal years of return on capital "
                        + "employed; 4 available"))
                .with(ROCE_3Y_MIN_PERCENT, "31").build());

        StockScreener.Decision roce = signs.signs().stream()
                .filter(sign -> sign.asked().metric() == ROCE_5Y_MIN_PERCENT).findFirst().orElseThrow();
        assertThat(roce.onShorterSpan()).isTrue();
        assertThat(roce.result().rule().metric()).isEqualTo(ROCE_3Y_MIN_PERCENT);
        assertThat(roce.result().outcome()).isEqualTo(RuleOutcome.PASS);
        assertThat(signs.notes()).containsExactly("Lowest return on capital employed was checked over 3 years because "
                + "the 5-year figure is not available: only 4 years of full-year results are available for this "
                + "company so far.");
    }

    @Test
    void anInvalidFiveYearFigureIsNeverReplacedByAShorterOne() {
        MoatSigns signs = assessor.assess(stock("X", IndustryGroup.CONSUMER)
                .with(ScreeningFixtures.withStatus(ROCE_5Y_MIN_PERCENT, DataStatus.INVALID, null))
                .with(ROCE_3Y_MIN_PERCENT, "31").build());

        StockScreener.Decision roce = signs.signs().stream()
                .filter(sign -> sign.asked().metric() == ROCE_5Y_MIN_PERCENT).findFirst().orElseThrow();
        assertThat(roce.onShorterSpan()).isFalse();
        assertThat(roce.result().outcome()).isEqualTo(RuleOutcome.INSUFFICIENT_DATA);
        assertThat(signs.notes()).isEmpty();
    }

    @Test
    void noFallbackWhenTheShorterSpanIsTurnedOff() {
        MoatAssessor strict = new MoatAssessor(ScreeningPresetsTest.presets(), new MoatProperties(null, 0, null));
        MoatSigns signs = strict.assess(stock("SKYGOLD", IndustryGroup.CONSUMER)
                .with(ROCE_3Y_MIN_PERCENT, "31").build());

        assertThat(signs.signs()).noneMatch(StockScreener.Decision::onShorterSpan);
        assertThat(signs.withOutcome(RuleOutcome.INSUFFICIENT_DATA)).hasSize(5);
    }

    @Test
    void anUnknownPresetStopsTheApplication() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MoatAssessor(ScreeningPresetsTest.presets(),
                new MoatProperties("no-such-preset", null, null))).hasMessageContaining("no-such-preset");
    }

    @Test
    void everySignsMetricIsOneTheSnapshotRecords() {
        assertThat(ScreeningMetric.of(ScreeningMetric.Series.ROCE_LOWEST, 3)).contains(ROCE_3Y_MIN_PERCENT);
        assertThat(ScreeningMetric.of(ScreeningMetric.Series.OPERATING_MARGIN_RANGE, 5)).contains(OPERATING_MARGIN_5Y_RANGE);
    }
}
