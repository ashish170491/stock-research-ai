package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FiscalCalendarTest {

    private final FiscalCalendar indian = FiscalCalendar.indian();

    @Test
    void labelsAprilToJuneAsTheFirstQuarterOfTheFiscalYearEndingTheFollowingMarch() {
        ReportingPeriod q = indian.quarter(LocalDate.of(2026, 6, 30));

        assertThat(q.type()).isEqualTo(PeriodType.QUARTER);
        assertThat(q.label()).isEqualTo("Q1 FY27");
        assertThat(q.fiscalYear()).isEqualTo(2027);
        assertThat(q.fiscalQuarter()).isEqualTo(1);
        assertThat(q.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(q.end()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void labelsEveryQuarterOfAnIndianFiscalYearCorrectly() {
        assertThat(indian.quarter(LocalDate.of(2025, 9, 30)).label()).isEqualTo("Q2 FY26");
        assertThat(indian.quarter(LocalDate.of(2025, 12, 31)).label()).isEqualTo("Q3 FY26");
        assertThat(indian.quarter(LocalDate.of(2026, 3, 31)).label()).isEqualTo("Q4 FY26");
        assertThat(indian.quarter(LocalDate.of(2026, 3, 31)).start()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void namesAFiscalYearAfterTheYearItEndsIn() {
        ReportingPeriod fy = indian.fiscalYear(LocalDate.of(2026, 3, 31));

        assertThat(fy.label()).isEqualTo("FY26");
        assertThat(fy.fiscalYear()).isEqualTo(2026);
        assertThat(fy.start()).isEqualTo(LocalDate.of(2025, 4, 1));
    }

    @Test
    void givesNoFiscalLabelToADateThatIsNotAQuarterEndRatherThanAWrongOne() {
        ReportingPeriod odd = indian.quarter(LocalDate.of(2026, 6, 15));

        assertThat(odd.fiscalQuarter()).isNull();
        assertThat(odd.label()).contains("not a standard fiscal quarter end");
    }

    @Test
    void followsACompanysOwnFiscalYearWhenItIsNotAprilToMarch() {
        FiscalCalendar december = FiscalCalendar.forFiscalYearEnd(LocalDate.of(2025, 12, 31));

        assertThat(december.quarter(LocalDate.of(2026, 6, 30)).label()).isEqualTo("Q2 FY26");
        assertThat(december.fiscalYear(LocalDate.of(2025, 12, 31)).label()).isEqualTo("FY25");
    }

    @Test
    void labelsTrailingTwelveMonthsWithTheQuarterTheyRunTo() {
        ReportingPeriod ttm = indian.trailingTwelveMonths(LocalDate.of(2026, 6, 30));

        assertThat(ttm.type()).isEqualTo(PeriodType.TRAILING_TWELVE_MONTHS);
        assertThat(ttm.start()).isEqualTo(LocalDate.of(2025, 7, 1));
        assertThat(ttm.label()).isEqualTo("TTM to Q1 FY27 (ending 2026-06-30)");
    }

    @Test
    void returnsUnknownWhenTheProviderGaveNoDate() {
        assertThat(indian.quarter(null).type()).isEqualTo(PeriodType.UNKNOWN);
        assertThat(indian.fiscalYear(null).known()).isFalse();
        assertThat(indian.trailingTwelveMonths(null).label()).startsWith("UNKNOWN");
    }
}
