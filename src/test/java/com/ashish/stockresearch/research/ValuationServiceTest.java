package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.model.ValuationSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.ashish.stockresearch.research.ValuationFixtures.techm;
import static com.ashish.stockresearch.research.ValuationFixtures.valuation;
import static org.assertj.core.api.Assertions.assertThat;

class ValuationServiceTest {

    private final ValuationService service = new ValuationService(BigDecimal.valueOf(5));

    @Test
    void holdsEachMultipleAsADatedDataPointInItsUnit() {
        ValuationSnapshot v = service.assess(techm());

        assertThat(v.asOf()).isEqualTo(ValuationFixtures.AS_OF);
        assertThat(v.metrics()).allSatisfy(point -> {
            assertThat(point.status()).as(point.metric()).isEqualTo(DataStatus.VALID);
            assertThat(point.period().type()).as(point.metric()).isEqualTo(PeriodType.POINT_IN_TIME);
            assertThat(point.period().label()).as(point.metric()).isEqualTo("As of 2026-09-25");
        });
        assertThat(v.trailingPe().unit()).isEqualTo(Unit.MULTIPLE);
        assertThat(v.trailingPe().display()).isEqualTo("26.74x");
        assertThat(v.priceToBook().unit()).isEqualTo(Unit.MULTIPLE);
        assertThat(v.dividendYieldPercent().unit()).isEqualTo(Unit.PERCENT);
        assertThat(v.dividendYieldPercent().display()).isEqualTo("3.29%");
        assertThat(v.earningsYieldPercent().unit()).isEqualTo(Unit.PERCENT);
        assertThat(v.dataQualityIssues()).isEmpty();
        assertThat(v.dataGaps()).isEmpty();
    }

    @Test
    void calculatesEarningsYieldFromThePeWithItsFormulaAndInputsAndTheVerifierReproducesIt() {
        FinancialDataPoint earningsYield = service.assess(techm()).earningsYieldPercent();

        // 100 / 26.74 = 3.74%
        assertThat(earningsYield.calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(earningsYield.display()).isEqualTo("3.74%");
        assertThat(earningsYield.formula()).isEqualTo(Formula.RECIPROCAL_PERCENT);
        assertThat(earningsYield.calculation()).isEqualTo("100 / trailing P/E (As of 2026-09-25)");
        assertThat(earningsYield.inputs()).singleElement().satisfies(input -> {
            assertThat(input.sourceField()).isEqualTo("summaryDetail.trailingPE");
            assertThat(input.value()).isEqualByComparingTo("26.74");
        });
        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        techm().points().forEach(sources::add);
        assertThat(CalculationVerifier.problem(earningsYield, sources)).isEmpty();
        // A P/E recorded differently from the source is caught.
        assertThat(CalculationVerifier.problem(earningsYield, new CalculationVerifier.SourceValues()
                .add("summaryDetail.trailingPE", "As of 2026-09-25", new BigDecimal("30.00")))).isPresent();
    }

    @Test
    void marksAPeThatDisagreesWithPriceOverEpsAsDataConflictAndGivesNoEarningsYield() {
        // 1548.00 / 57.90 = 26.74x, but the provider says 40.00x: 49.6% apart.
        ValuationSnapshot v = service.assess(valuation("1548.00", "57.90", "334.41", "51.00", "40.00", "4.63", "0.0329"));

        assertThat(v.trailingPe().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(v.trailingPe().statusReason()).contains("40.00x").contains("5%")
                // no unverified replacement figure the model could quote as the P/E
                .doesNotContain("26.74");
        assertThat(v.dataQualityIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_CONFLICT);
            // Only the multiple is disputed; the price and EPS it was checked against stay usable.
            assertThat(issue.affectedFields()).containsExactly("summaryDetail.trailingPE");
        });
        assertThat(v.earningsYieldPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(v.earningsYieldPercent().value()).isNull();
        assertThat(v.priceToBook().status()).isEqualTo(DataStatus.VALID);
    }

    @Test
    void keepsAPeWithinTheToleranceAndTheToleranceIsConfigurable() {
        // 27.50x against 26.74x from price / EPS: 2.8% apart.
        var reported = valuation("1548.00", "57.90", "334.41", "51.00", "27.50", "4.63", "0.0329");

        assertThat(service.assess(reported).trailingPe().status()).isEqualTo(DataStatus.VALID);
        assertThat(new ValuationService(BigDecimal.ONE).assess(reported).trailingPe().status())
                .isEqualTo(DataStatus.DATA_CONFLICT);
    }

    @Test
    void checksPriceToBookAndDividendYieldTheSameWay() {
        // P/B 6.00x against 1548.00 / 334.41 = 4.63x; yield 5.00% against 51.00 / 1548.00 = 3.29%.
        ValuationSnapshot v = service.assess(valuation("1548.00", "57.90", "334.41", "51.00", "26.74", "6.00", "0.05"));

        assertThat(v.priceToBook().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(v.dividendYieldPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(v.trailingPe().status()).isEqualTo(DataStatus.VALID);
        assertThat(v.dataQualityIssues()).hasSize(2);
    }

    @Test
    void saysWhenAMultipleCouldNotBeCheckedRatherThanHidingIt() {
        ValuationSnapshot v = service.assess(valuation("1548.00", null, "334.41", "51.00", "26.74", "4.63", "0.0329"));

        assertThat(v.trailingPe().status()).isEqualTo(DataStatus.VALID);
        assertThat(v.dataQualityIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_QUALITY_WARNING);
            assertThat(issue.message()).contains("trailing P/E").contains("could not be checked")
                    .contains("trailingEps is UNAVAILABLE");
        });
    }

    @Test
    void reportsUnavailableValuationMetricsAsDataGaps() {
        ValuationSnapshot v = service.assess(valuation("1548.00", "57.90", "334.41", null, null, "4.63", null));

        assertThat(v.trailingPe().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(v.earningsYieldPercent().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(v.dataGaps()).extracting(gap -> gap.area() + " / " + gap.item()).containsExactly(
                "Valuation / trailingPe", "Valuation / dividendYieldPercent", "Valuation / earningsYieldPercent");
    }
}
