package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.calc.FinancialUnits.ProviderScale;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the rupee conversions that previously went wrong by a factor of ten
 * when the model did them. Inputs are real Yahoo Finance values for HDFC Bank.
 */
class FinancialUnitsTest {

    /** Yahoo price.marketCap for HDFCBANK.NS, whole rupees. */
    private static final BigDecimal HDFC_MARKET_CAP_RUPEES = new BigDecimal("11341831077888");
    /** Yahoo financialData.totalRevenue for HDFCBANK.NS, whole rupees (~2.95 trillion). */
    private static final BigDecimal HDFC_REVENUE_RUPEES = new BigDecimal("2949644288000");

    @Test
    void convertsMarketCapInRupeesToCroreWithoutATenfoldError() {
        BigDecimal crore = FinancialUnits.toCrore(HDFC_MARKET_CAP_RUPEES, ProviderScale.ONES);

        assertThat(crore).isEqualByComparingTo("1134183.11");
        // The two neighbouring 10x mistakes must both be ruled out.
        assertThat(crore).isNotEqualByComparingTo("11341831.08");
        assertThat(crore).isNotEqualByComparingTo("113418.31");
    }

    @Test
    void convertsCroreToLakhCroreByDividingByOneLakh() {
        BigDecimal lakhCrore = FinancialUnits.croreToLakhCrore(new BigDecimal("1134183.11"));

        assertThat(lakhCrore).isEqualByComparingTo("11.3418");
        // The bug that reached the report: ~113.42 lakh crore.
        assertThat(lakhCrore).isLessThan(new BigDecimal("12"));
        assertThat(lakhCrore).isGreaterThan(new BigDecimal("11"));
    }

    @Test
    void displaysMarketCapInBothLakhCroreAndCroreSoTheMagnitudeCannotBeMisread() {
        BigDecimal crore = FinancialUnits.toCrore(HDFC_MARKET_CAP_RUPEES, ProviderScale.ONES);

        assertThat(FinancialUnits.display(crore, Unit.INR_CRORE))
                .isEqualTo("₹11.34 lakh crore (₹11,34,183 crore)");
    }

    @Test
    void convertsRevenueOfAboutTwoPointNineFiveTrillionRupeesToTwoPointNineFiveLakhCrore() {
        BigDecimal crore = FinancialUnits.toCrore(HDFC_REVENUE_RUPEES, ProviderScale.ONES);

        assertThat(crore).isEqualByComparingTo("294964.43");
        assertThat(FinancialUnits.croreToLakhCrore(crore)).isEqualByComparingTo("2.9496");
        assertThat(FinancialUnits.display(crore, Unit.INR_CRORE)).isEqualTo("₹2.95 lakh crore (₹2,94,964 crore)");
    }

    @Test
    void theExampleFromTheSpecificationNormalisesToTwoLakhNinetyFiveThousandCrore() {
        assertThat(FinancialUnits.toCrore(new BigDecimal("2950000000000"), ProviderScale.ONES))
                .isEqualByComparingTo("295000");
    }

    @Test
    void everyProviderScaleOfTheSameAmountNormalisesToTheSameCrore() {
        assertThat(FinancialUnits.toCrore(new BigDecimal("2950000000000"), ProviderScale.ONES)).isEqualByComparingTo("295000");
        assertThat(FinancialUnits.toCrore(new BigDecimal("2950000000"), ProviderScale.THOUSANDS)).isEqualByComparingTo("295000");
        assertThat(FinancialUnits.toCrore(new BigDecimal("29500000"), ProviderScale.LAKHS)).isEqualByComparingTo("295000");
        assertThat(FinancialUnits.toCrore(new BigDecimal("2950000"), ProviderScale.MILLIONS)).isEqualByComparingTo("295000");
        assertThat(FinancialUnits.toCrore(new BigDecimal("295000"), ProviderScale.CRORES)).isEqualByComparingTo("295000");
        assertThat(FinancialUnits.toCrore(new BigDecimal("2950"), ProviderScale.BILLIONS)).isEqualByComparingTo("295000");
    }

    @Test
    void displaysAmountsBelowOneLakhCroreInCroreWithIndianGrouping() {
        assertThat(FinancialUnits.display(new BigDecimal("46355.55"), Unit.INR_CRORE)).isEqualTo("₹46,355.55 crore");
        assertThat(FinancialUnits.display(new BigDecimal("-1234.5"), Unit.INR_CRORE)).isEqualTo("-₹1,234.50 crore");
        assertThat(FinancialUnits.indianGrouping(new BigDecimal("123456789"), 0)).isEqualTo("12,34,56,789");
        assertThat(FinancialUnits.indianGrouping(new BigDecimal("999"), 0)).isEqualTo("999");
    }

    @Test
    void normalisesInrToCroreAndUsdToMillionsButRefusesAnUnknownCurrency() {
        assertThat(FinancialUnits.normalizeAmount(HDFC_REVENUE_RUPEES, ProviderScale.ONES, "INR"))
                .hasValueSatisfying(a -> {
                    assertThat(a.unit()).isEqualTo(Unit.INR_CRORE);
                    assertThat(a.value()).isEqualByComparingTo("294964.43");
                });
        assertThat(FinancialUnits.normalizeAmount(new BigDecimal("20298999808"), ProviderScale.ONES, "usd"))
                .hasValueSatisfying(a -> {
                    assertThat(a.unit()).isEqualTo(Unit.USD_MILLION);
                    assertThat(a.value()).isEqualByComparingTo("20299.00");
                });
        assertThat(FinancialUnits.normalizeAmount(BigDecimal.TEN, ProviderScale.ONES, null)).isEmpty();
        assertThat(FinancialUnits.normalizeAmount(BigDecimal.TEN, ProviderScale.ONES, "EUR")).isEmpty();
    }

    @Test
    void convertsProviderFractionsToPercentages() {
        assertThat(FinancialUnits.fractionToPercent(new BigDecimal("0.26787"))).isEqualByComparingTo("26.79");
        assertThat(FinancialUnits.display(new BigDecimal("26.79"), Unit.PERCENT)).isEqualTo("26.79%");
    }

    @Test
    void rendersAMissingValueAsUnavailableNeverAsZero() {
        assertThat(FinancialUnits.display(null, Unit.INR_CRORE)).isEqualTo("UNAVAILABLE");
    }
}
