package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningResult.RuleResult;
import com.ashish.stockresearch.screening.ScreeningResult.StockScreening;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningFixtures.unchecked;
import static com.ashish.stockresearch.screening.ScreeningFixtures.withStatus;
import static com.ashish.stockresearch.screening.ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.EARNINGS_YIELD_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.OPERATING_MARGIN_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROA_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROCE_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_PERCENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The screener in isolation, on hand-built snapshots. */
class StockScreenerTest {

    private final StockScreener screener = new StockScreener();

    private static Rule rule(ScreeningMetric metric, Operator operator, String threshold) {
        return new Rule(metric, operator, new BigDecimal(threshold));
    }

    private static ScreeningCriteria criteria(List<Rule> nonFinancial, Map<IndustryGroup, List<Rule>> byGroup) {
        return new ScreeningCriteria("test", "test criteria", nonFinancial, byGroup,
                new ScreeningCriteria.TieBreak(ROE_PERCENT, true), Set.of());
    }

    private static final List<Rule> QUALITY = List.of(rule(ROE_PERCENT, Operator.AT_LEAST, "15"),
            rule(ROCE_PERCENT, Operator.AT_LEAST, "15"), rule(DEBT_TO_EQUITY_MULTIPLE, Operator.AT_MOST, "0.5"));
    private static final List<Rule> BANK = List.of(rule(ROA_PERCENT, Operator.AT_LEAST, "1"),
            rule(ROE_PERCENT, Operator.AT_LEAST, "14"));

    @Test
    void passesAndFailsOnValidValuesAtAndAroundTheThreshold() {
        FundamentalsSnapshot tcs = stock("TCS", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "15.00")
                .with(ROCE_PERCENT, "14.99").with(DEBT_TO_EQUITY_MULTIPLE, "0.50").build();

        StockScreening result = screener.screen(criteria(QUALITY, Map.of()), List.of(tcs)).ranked().get(0);

        assertThat(result.results()).extracting(RuleResult::outcome)
                .containsExactly(RuleOutcome.PASS, RuleOutcome.FAIL, RuleOutcome.PASS);
    }

    // S3-5: a value that is not VALID never passes - not even a DATA_CONFLICT whose kept value is far above
    // the threshold.
    @ParameterizedTest
    @EnumSource(value = DataStatus.class, names = {"UNAVAILABLE", "DATA_CONFLICT", "INVALID"})
    void aValueThatIsNotValidIsInsufficientDataNeverAPass(DataStatus status) {
        String kept = status == DataStatus.DATA_CONFLICT ? "40.00" : null;
        FundamentalsSnapshot stock = stock("X", IndustryGroup.IT_SERVICES)
                .with(withStatus(ROE_PERCENT, status, kept)).with(ROCE_PERCENT, "30")
                .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build();

        RuleResult roe = screener.screen(criteria(QUALITY, Map.of()), List.of(stock)).ranked().get(0).results().get(0);

        assertThat(roe.outcome()).isEqualTo(RuleOutcome.INSUFFICIENT_DATA);
        assertThat(roe.reason()).contains(status.name());
    }

    @Test
    void aMissingMetricIsInsufficientData() {
        FundamentalsSnapshot stock = stock("X", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "20").build();

        StockScreening result = screener.screen(criteria(QUALITY, Map.of()), List.of(stock)).ranked().get(0);

        assertThat(result.results()).extracting(RuleResult::outcome)
                .containsExactly(RuleOutcome.PASS, RuleOutcome.INSUFFICIENT_DATA, RuleOutcome.INSUFFICIENT_DATA);
        assertThat(result.meetsAll()).isFalse();
    }

    @Test
    void aMultipleThatWasNotCrossCheckedNeverPasses() {
        ScreeningCriteria value = criteria(List.of(rule(EARNINGS_YIELD_PERCENT, Operator.AT_LEAST, "4")), Map.of());
        FundamentalsSnapshot stock = stock("X", IndustryGroup.IT_SERVICES)
                .with(unchecked(EARNINGS_YIELD_PERCENT, "9.00")).build();

        RuleResult result = screener.screen(value, List.of(stock)).ranked().get(0).results().get(0);

        assertThat(result.outcome()).isEqualTo(RuleOutcome.INSUFFICIENT_DATA);
        assertThat(result.reason()).contains("not cross-checked");
    }

    @Test
    void neverComparesAValueInAnotherUnitWithTheThreshold() {
        SnapshotMetric asAMultiple = new SnapshotMetric(ROE_PERCENT, new BigDecimal("0.20"), Unit.MULTIPLE, "0.20x",
                DataStatus.VALID, null, "FY26", "test", null, null);

        RuleResult result = StockScreener.evaluate(rule(ROE_PERCENT, Operator.AT_LEAST, "15"), asAMultiple);

        assertThat(result.outcome()).isEqualTo(RuleOutcome.INSUFFICIENT_DATA);
        assertThat(result.reason()).contains("never mixed");
    }

    // S3-7: a bank is screened by its own rules only - debt-to-equity and operating margin are never evaluated,
    // even when the snapshot has them.
    @Test
    void screensABankOnlyByBankRules() {
        FundamentalsSnapshot hdfc = stock("HDFCBANK", IndustryGroup.BANK).with(ROA_PERCENT, "1.90")
                .with(ROE_PERCENT, "16.00").with(DEBT_TO_EQUITY_MULTIPLE, "0.80")
                .with(OPERATING_MARGIN_PERCENT, "30.00").build();

        StockScreening result = screener.screen(criteria(QUALITY, Map.of(IndustryGroup.BANK, BANK)), List.of(hdfc))
                .ranked().get(0);

        assertThat(result.results()).extracting(r -> r.rule().metric()).containsExactly(ROA_PERCENT, ROE_PERCENT);
        assertThat(result.meetsAll()).isTrue();
    }

    @Test
    void neverAppliesTheNonFinancialRulesToAFinancialGroupWithoutRulesOfItsOwn() {
        FundamentalsSnapshot insurer = stock("SBILIFE", IndustryGroup.INSURANCE).with(ROE_PERCENT, "20").build();
        FundamentalsSnapshot bank = stock("SBIN", IndustryGroup.BANK).with(ROE_PERCENT, "20").build();
        FundamentalsSnapshot holding = stock("BAJAJFINSV", IndustryGroup.FINANCIAL_OTHER).with(ROE_PERCENT, "20")
                .with(DEBT_TO_EQUITY_MULTIPLE, "4.64").build();

        ScreeningResult result = screener.screen(criteria(QUALITY, Map.of()), List.of(insurer, bank, holding));

        assertThat(result.ranked()).isEmpty();
        assertThat(result.notScreened()).extracting(ScreeningResult.NotScreened::symbol)
                .containsExactly("SBILIFE", "SBIN", "BAJAJFINSV");
        assertThat(result.notScreened().get(0).reason()).contains("define no rules for INSURANCE");
    }

    @Test
    void neverScreensAStockWhoseIndustryGroupIsUnknown() {
        FundamentalsSnapshot unknown = stock("X", IndustryGroup.UNKNOWN).with(ROE_PERCENT, "20").build();

        ScreeningResult result = screener.screen(criteria(QUALITY, Map.of()), List.of(unknown));

        assertThat(result.ranked()).isEmpty();
        assertThat(result.notScreened()).singleElement().satisfies(stock ->
                assertThat(stock.reason()).contains("UNKNOWN").contains("test classification"));
    }

    @Test
    void theCriteriaRejectARuleOnAMetricThatIsNotPrimaryForTheGroup() {
        assertThatThrownBy(() -> criteria(QUALITY, Map.of(IndustryGroup.BANK,
                List.of(rule(DEBT_TO_EQUITY_MULTIPLE, Operator.AT_MOST, "1")))))
                .hasMessageContaining("DEBT_TO_EQUITY_MULTIPLE is not a primary metric for BANK");
        assertThatThrownBy(() -> criteria(QUALITY, Map.of(IndustryGroup.NBFC,
                List.of(rule(OPERATING_MARGIN_PERCENT, Operator.AT_LEAST, "10")))))
                .hasMessageContaining("not a primary metric for NBFC");
        assertThatThrownBy(() -> criteria(QUALITY, Map.of(IndustryGroup.UNKNOWN, BANK)))
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void ranksStocksMeetingEveryCriterionFirstThenByShareMetThenFewerGapsThenTieBreak() {
        List<FundamentalsSnapshot> stocks = List.of(
                // 2 of 3 met, one gap
                stock("GAP", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "40").with(ROCE_PERCENT, "40").build(),
                // 2 of 3 met, no gap
                stock("TWO", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "16").with(ROCE_PERCENT, "16")
                        .with(DEBT_TO_EQUITY_MULTIPLE, "0.9").build(),
                // all met, lower ROE
                stock("ALL_LOW", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "16").with(ROCE_PERCENT, "16")
                        .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build(),
                // a bank meeting both of its two rules ranks with the non-financials meeting all of theirs
                stock("BANK", IndustryGroup.BANK).with(ROA_PERCENT, "2").with(ROE_PERCENT, "18").build(),
                // all met, higher ROE
                stock("ALL_HIGH", IndustryGroup.IT_SERVICES).with(ROE_PERCENT, "30").with(ROCE_PERCENT, "30")
                        .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build());

        ScreeningResult result = screener.screen(criteria(QUALITY, Map.of(IndustryGroup.BANK, BANK)), stocks);

        assertThat(result.ranked()).extracting(StockScreening::symbol)
                .containsExactly("ALL_HIGH", "BANK", "ALL_LOW", "TWO", "GAP");
    }

    @Test
    void filtersByIndustryGroup() {
        List<FundamentalsSnapshot> stocks = List.of(stock("TCS", IndustryGroup.IT_SERVICES).build(),
                stock("HDFCBANK", IndustryGroup.BANK).build());

        ScreeningResult result = screener.screen(criteria(QUALITY, Map.of(IndustryGroup.BANK, BANK))
                .withIndustryGroups(Set.of(IndustryGroup.BANK)), stocks);

        assertThat(result.ranked()).extracting(StockScreening::symbol).containsExactly("HDFCBANK");
        assertThat(result.filteredOut()).isEqualTo(1);
    }

    @Test
    void givesEveryStockAResultForEveryRuleThatAppliesToIt() {
        List<FundamentalsSnapshot> stocks = List.of(stock("A", IndustryGroup.IT_SERVICES).build(),
                stock("B", IndustryGroup.BANK).build(), stock("C", IndustryGroup.MANUFACTURING).with(ROE_PERCENT, "1").build());

        ScreeningResult result = screener.screen(criteria(QUALITY, Map.of(IndustryGroup.BANK, BANK)), stocks);

        assertThat(result.ranked()).allSatisfy(stock -> assertThat(stock.results())
                .hasSize(stock.industryGroup() == IndustryGroup.BANK ? BANK.size() : QUALITY.size()));
    }

    // S3-10
    @Test
    void screensFiftyStocksInWellUnderASecond() {
        List<FundamentalsSnapshot> stocks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            IndustryGroup group = i % 5 == 0 ? IndustryGroup.BANK : IndustryGroup.IT_SERVICES;
            stocks.add(stock("S" + i, group).with(ROE_PERCENT, String.valueOf(10 + i % 12))
                    .with(ROCE_PERCENT, String.valueOf(8 + i % 15)).with(ROA_PERCENT, "1.5")
                    .with(DEBT_TO_EQUITY_MULTIPLE, "0." + (i % 9)).build());
        }
        ScreeningCriteria criteria = criteria(QUALITY, Map.of(IndustryGroup.BANK, BANK));

        long started = System.nanoTime();
        ScreeningResult result = screener.screen(criteria, stocks);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.ranked()).hasSize(50);
        assertThat(millis).isLessThan(1000);
    }

    // S3-6: the screener reads only what it is given - no provider, no repository, no state.
    @Test
    void hasNoDependenciesOfItsOwn() {
        assertThat(StockScreener.class.getDeclaredFields()).isEmpty();
        assertThat(StockScreener.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(constructor.getParameterCount()).isZero());
    }
}
