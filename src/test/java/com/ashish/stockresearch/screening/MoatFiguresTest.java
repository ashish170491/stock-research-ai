package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.ResearchFixtures;
import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The lowest return and the margin range over exact spans: calculated in Java, verified, never over fewer years. */
class MoatFiguresTest {

    private static final CalculationVerifier.SourceValues NO_SOURCES = new CalculationVerifier.SourceValues();

    /** One fiscal year's ROE and ROCE (on average balances unless {@code closingOnly}) and operating margin. */
    private static AnnualRatios year(int endYear, String roe, String roce, String margin, boolean closingOnly) {
        ReportingPeriod period = ResearchFixtures.year(endYear, "1", "1", "1", "1").period();
        String label = period.label();
        List<CalculationInput> roeInputs = new ArrayList<>(List.of(input(label + " net profit"),
                input(label + " closing equity")));
        List<CalculationInput> roceInputs = new ArrayList<>(List.of(input(label + " EBIT"),
                input(label + " closing total assets"), input(label + " closing current liabilities")));
        if (!closingOnly) {
            roeInputs.add(input("prior closing equity"));
            roceInputs.add(input("prior closing total assets"));
            roceInputs.add(input("prior closing current liabilities"));
        }
        FinancialDataPoint roePoint = FinancialDataPoint.calculated("returnOnEquityPercent", new BigDecimal(roe),
                Unit.PERCENT, period, ResearchFixtures.SOURCE, Formula.AVERAGE_BALANCE_RETURN_PERCENT, "roe", roeInputs);
        FinancialDataPoint rocePoint = FinancialDataPoint.calculated("returnOnCapitalEmployedPercent",
                new BigDecimal(roce), Unit.PERCENT, period, ResearchFixtures.SOURCE,
                Formula.RETURN_ON_CAPITAL_EMPLOYED_PERCENT, "roce", roceInputs);
        FinancialDataPoint marginPoint = FinancialDataPoint.calculated("operatingMarginPercent", new BigDecimal(margin),
                Unit.PERCENT, period, ResearchFixtures.SOURCE, Formula.RATIO_PERCENT, "margin",
                List.of(input("operating profit"), input("revenue")));
        return new AnnualRatios(period, null, null, null, marginPoint, roePoint, null, null, rocePoint);
    }

    private static CalculationInput input(String name) {
        return new CalculationInput(name, null, BigDecimal.ONE, Unit.INR_CRORE, name);
    }

    private static List<AnnualRatios> sixYears() {
        return List.of(year(2021, "9.00", "8.00", "10.00", true), year(2022, "18.00", "24.10", "21.00", false),
                year(2023, "19.50", "22.00", "22.40", false), year(2024, "17.25", "26.30", "20.10", false),
                year(2025, "21.00", "25.00", "23.00", false), year(2026, "20.40", "27.75", "22.60", false));
    }

    @Test
    void theLowestRoceIsTakenOverExactlyTheLastFiveYearsAndVerified() {
        FinancialDataPoint lowest = SnapshotCalculator.lowest(sixYears(), 5,
                AnnualRatios::returnOnCapitalEmployedPercent, "return on capital employed", "roce5yLowest", NO_SOURCES);

        // FY21's 8.00% is outside the span
        assertThat(lowest.status()).isEqualTo(DataStatus.VALID);
        assertThat(lowest.calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(lowest.formula()).isEqualTo(Formula.MINIMUM);
        assertThat(lowest.value()).isEqualByComparingTo("22.00");
        assertThat(lowest.inputs()).hasSize(5);
        assertThat(lowest.period().label()).isEqualTo("FY22 to FY26 (5 years)");
        assertThat(CalculationVerifier.problem(lowest, NO_SOURCES)).isEmpty();
    }

    @Test
    void theMarginRangeIsTheHighestMinusTheLowestInPercentagePoints() {
        FinancialDataPoint range = SnapshotCalculator.range(sixYears(), 5, AnnualRatios::operatingMarginPercent,
                "operating margin", "margin5yRange", NO_SOURCES);

        // FY22 to FY26: highest 23.00, lowest 20.10
        assertThat(range.status()).isEqualTo(DataStatus.VALID);
        assertThat(range.formula()).isEqualTo(Formula.RANGE);
        assertThat(range.unit()).isEqualTo(Unit.PERCENTAGE_POINTS);
        assertThat(range.value()).isEqualByComparingTo("2.90");
        assertThat(range.display()).isEqualTo("2.90 percentage points");
        assertThat(CalculationVerifier.problem(range, NO_SOURCES)).isEmpty();
    }

    @Test
    void fewerYearsThanTheSpanGiveNothingAndSayHowManyThereAre() {
        FinancialDataPoint lowest = SnapshotCalculator.lowest(sixYears().subList(2, 6), 5,
                AnnualRatios::returnOnEquityPercent, "return on equity", "roe5yLowest", NO_SOURCES);

        assertThat(lowest.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(lowest.value()).isNull();
        assertThat(lowest.statusReason()).isEqualTo("needs 5 fiscal years of return on equity; 4 available");
    }

    @Test
    void aYearOnClosingBalancesInTheSpanGivesNoLowest() {
        // FY21 is on closing balances only: a 6-year span would mix bases
        FinancialDataPoint lowest = SnapshotCalculator.lowest(sixYears(), 6, AnnualRatios::returnOnEquityPercent,
                "return on equity", "roe6yLowest", NO_SOURCES);

        assertThat(lowest.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(lowest.statusReason()).contains("FY21").contains("closing balances");
    }

    @Test
    void aYearThatIsNotValidBlocksTheFigureWithItsStatus() {
        List<AnnualRatios> ratios = new ArrayList<>(sixYears());
        ratios.set(3, ratios.get(3).map(point -> point != null && point.metric().equals("operatingMarginPercent")
                ? point.inConflict("feeds disagree") : point));

        FinancialDataPoint range = SnapshotCalculator.range(ratios, 5, AnnualRatios::operatingMarginPercent,
                "operating margin", "margin5yRange", NO_SOURCES);

        assertThat(range.status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(range.value()).isNull();
    }

    @Test
    void aGapInTheYearsGivesNoFigure() {
        List<AnnualRatios> ratios = new ArrayList<>(sixYears());
        ratios.remove(3); // FY24

        FinancialDataPoint lowest = SnapshotCalculator.lowest(ratios, 5, AnnualRatios::returnOnEquityPercent,
                "return on equity", "roe5yLowest", NO_SOURCES);

        assertThat(lowest.status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(lowest.statusReason()).contains("consecutive");
    }

    @Test
    void theCalculatorAndTheVerifierAgreeOnARange() {
        assertThat(FinancialCalculator.range(List.of(new BigDecimal("4.5"), new BigDecimal("-1.25"),
                new BigDecimal("3")))).contains(new BigDecimal("5.75"));
        assertThat(FinancialCalculator.range(List.of())).isEmpty();
    }
}
