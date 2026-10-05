package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.ashish.stockresearch.research.ResearchFixtures.CALENDAR;
import static org.assertj.core.api.Assertions.assertThat;

// S13-4
class OwnHistoryTest {

    static final SourceInfo FILINGS = new SourceInfo("NSE corporate filings (XBRL)", SourceType.OFFICIAL_FILING, null,
            null, null);

    static FinancialDataPoint roe(int year, String value) {
        ReportingPeriod period = CALENDAR.fiscalYear(LocalDate.of(year, 3, 31));
        BigDecimal v = new BigDecimal(value);
        return FinancialDataPoint.calculated("returnOnEquityPercent", v, Unit.PERCENT, period, FILINGS,
                Formula.SELECTED_VALUE, "test", List.of(new CalculationInput("roe", null, v, Unit.PERCENT,
                        period.label())));
    }

    /** A year in the status asked for, as the report holds it: UNAVAILABLE, or a calculation not performed. */
    static FinancialDataPoint roe(int year, DataStatus status) {
        ReportingPeriod period = CALENDAR.fiscalYear(LocalDate.of(year, 3, 31));
        return status == DataStatus.UNAVAILABLE
                ? FinancialDataPoint.unavailable("returnOnEquityPercent", Unit.PERCENT, period, null, "test: " + status)
                : FinancialDataPoint.notCalculated("returnOnEquityPercent", Unit.PERCENT, period, FILINGS, status,
                        Formula.AVERAGE_BALANCE_RETURN_PERCENT, "test", List.of(), "test: " + status);
    }

    @Test
    void listsEveryYearAndRangesTheValidOnesWithTheLatestYearsPlace() {
        FinancialDataPoint disputed = roe(2025, "40.00");
        List<FinancialDataPoint> years = List.of(roe(2021, "15.10"), roe(2022, DataStatus.UNAVAILABLE),
                roe(2023, "17.20"), roe(2024, "13.90"), disputed, roe(2026, "14.20"));

        OwnHistory history = OwnHistory.of("returnOnEquityPercent", years, true, point -> point == disputed)
                .orElseThrow();

        assertThat(history.years()).extracting(OwnHistory.Year::label)
                .containsExactly("FY21", "FY22", "FY23", "FY24", "FY25", "FY26");
        // a year that is not VALID, or depends on a disputed value, is shown with its status and left out
        assertThat(history.years()).extracting(OwnHistory.Year::shown)
                .containsExactly("15.10%", "UNAVAILABLE", "17.20%", "13.90%", "DATA_CONFLICT", "14.20%");
        assertThat(history.usableYears()).hasSize(4);
        assertThat(history.lowest().value()).isEqualByComparingTo("13.90");
        assertThat(history.highest().value()).isEqualByComparingTo("17.20");
        // 17.20, 15.10, 14.20, 13.90: FY26 is the 3rd highest of the 4, a verified calculation
        assertThat(history.latestRank()).isEqualTo(3);
        assertThat(history.latestRankPoint().formula()).isEqualTo(Formula.RANK_FROM_HIGHEST);
        assertThat(CalculationVerifier.problem(history.latestRankPoint(), new CalculationVerifier.SourceValues()))
                .isEmpty();
        assertThat(history.lowest().inputs()).extracting(CalculationInput::name)
                .containsExactly("FY21 returnOnEquityPercent", "FY23 returnOnEquityPercent",
                        "FY24 returnOnEquityPercent", "FY26 returnOnEquityPercent");
        assertThat(CalculationVerifier.problem(history.highest(), new CalculationVerifier.SourceValues())).isEmpty();
        assertThat(ContextRenderer.history(history)).isEqualTo("Own fiscal years (as filed with NSE): FY21 15.10%, "
                + "FY22 UNAVAILABLE, FY23 17.20%, FY24 13.90%, FY25 DATA_CONFLICT, FY26 14.20%. Range of the 4 VALID "
                + "years: 13.90% to 17.20%; FY26 is the 3rd highest of the 4 years.");
    }

    @Test
    void givesTheLatestYearNoPlaceWhenItIsNotValid() {
        OwnHistory history = OwnHistory.of("returnOnEquityPercent", List.of(roe(2024, "13.90"), roe(2025, "14.03"),
                roe(2026, DataStatus.DATA_CONFLICT)), true, point -> false).orElseThrow();

        assertThat(history.hasRange()).isTrue();
        assertThat(history.latestRank()).isNull();
        assertThat(ContextRenderer.history(history)).endsWith("Range of the 2 VALID years: 13.90% to 14.03%; FY26 is "
                + "DATA_CONFLICT, so it has no place in it.");
    }

    // HDFC Bank's ROE as filed: one VALID year is no range
    @Test
    void givesNoRangeOfFewerThanTwoValidYears() {
        OwnHistory history = OwnHistory.of("returnOnEquityPercent", List.of(roe(2022, DataStatus.UNAVAILABLE),
                roe(2023, DataStatus.UNAVAILABLE), roe(2024, DataStatus.DATA_CONFLICT), roe(2025, "14.03"),
                roe(2026, DataStatus.DATA_CONFLICT)), true, point -> false).orElseThrow();

        assertThat(history.hasRange()).isFalse();
        assertThat(history.lowest().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(history.latestRank()).isNull();
        assertThat(ContextRenderer.history(history)).endsWith("FY25 14.03%, FY26 DATA_CONFLICT. No range is given: a "
                + "range needs at least 2 VALID fiscal years; 1 of the 5 is VALID.");
    }

    @Test
    void namesTheHighestAndLowestYears() {
        assertThat(ContextRenderer.place(1, 6)).isEqualTo("the highest");
        assertThat(ContextRenderer.place(6, 6)).isEqualTo("the lowest");
        assertThat(ContextRenderer.place(2, 6)).isEqualTo("the 2nd highest");
        assertThat(ContextRenderer.place(11, 25)).isEqualTo("the 11th highest");
        assertThat(ContextRenderer.place(23, 25)).isEqualTo("the 23rd highest");
    }
}
