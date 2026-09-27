package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.model.PeriodType;
import com.ashish.stockresearch.research.model.ReportingPeriod;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;

/**
 * Turns period-end dates supplied by a provider into labelled
 * {@link ReportingPeriod}s ("Q1 FY27", "FY26"). Labels are computed here so
 * the model never has to work out which fiscal quarter a date falls in.
 *
 * Indian companies report on an April-March fiscal year (Companies Act
 * 2013, s.2(41)), and a fiscal year is named after the calendar year it ends
 * in: April 2026 - March 2027 is FY27, and April-June 2026 is Q1 FY27. A
 * company with a different year end is handled by constructing the
 * calendar from its actual fiscal-year-end month.
 */
public final class FiscalCalendar {

    private final Month fiscalYearEndMonth;

    private FiscalCalendar(Month fiscalYearEndMonth) {
        this.fiscalYearEndMonth = fiscalYearEndMonth;
    }

    /** April-March fiscal year. */
    public static FiscalCalendar indian() {
        return new FiscalCalendar(Month.MARCH);
    }

    /**
     * Calendar matching a company's reported fiscal-year-end date. Falls
     * back to the statutory April-March year when the provider gives none.
     */
    public static FiscalCalendar forFiscalYearEnd(LocalDate lastFiscalYearEnd) {
        return lastFiscalYearEnd == null ? indian() : new FiscalCalendar(lastFiscalYearEnd.getMonth());
    }

    /** The calendar year in which the fiscal year containing {@code date} ends. */
    public int fiscalYearOf(LocalDate date) {
        return date.getMonthValue() <= fiscalYearEndMonth.getValue() ? date.getYear() : date.getYear() + 1;
    }

    /** 1-4: which quarter of its fiscal year {@code date} falls in. */
    public int fiscalQuarterOf(LocalDate date) {
        return monthsIntoFiscalYear(date) / 3 + 1;
    }

    /** True only for the last day of a fiscal quarter's final month. */
    public boolean isQuarterEnd(LocalDate date) {
        return date.equals(YearMonth.from(date).atEndOfMonth()) && monthsIntoFiscalYear(date) % 3 == 2;
    }

    public boolean isFiscalYearEnd(LocalDate date) {
        return date.getMonth() == fiscalYearEndMonth && date.equals(YearMonth.from(date).atEndOfMonth());
    }

    /**
     * A single fiscal quarter ending on {@code end}. A null end yields an
     * UNKNOWN period; an end that is not a quarter boundary keeps its real
     * dates but gets no fiscal label rather than a wrong one.
     */
    public ReportingPeriod quarter(LocalDate end) {
        if (end == null) {
            return ReportingPeriod.unknown();
        }
        if (!isQuarterEnd(end)) {
            return new ReportingPeriod(PeriodType.QUARTER, end.minusMonths(3).plusDays(1), end,
                    "Quarter ending " + end + " (not a standard fiscal quarter end)", null, null);
        }
        int fiscalYear = fiscalYearOf(end);
        int quarter = fiscalQuarterOf(end);
        return new ReportingPeriod(PeriodType.QUARTER, end.withDayOfMonth(1).minusMonths(2), end,
                "Q%d %s".formatted(quarter, fiscalYearLabel(fiscalYear)), fiscalYear, quarter);
    }

    /** A full fiscal year ending on {@code end}. */
    public ReportingPeriod fiscalYear(LocalDate end) {
        if (end == null) {
            return ReportingPeriod.unknown();
        }
        LocalDate start = end.plusDays(1).minusYears(1);
        if (!isFiscalYearEnd(end)) {
            return new ReportingPeriod(PeriodType.FISCAL_YEAR, start, end,
                    "12 months ending " + end, null, null);
        }
        int fiscalYear = fiscalYearOf(end);
        return new ReportingPeriod(PeriodType.FISCAL_YEAR, start, end, fiscalYearLabel(fiscalYear), fiscalYear, null);
    }

    /** The twelve months ending on {@code end}, labelled with the quarter they run up to. */
    public ReportingPeriod trailingTwelveMonths(LocalDate end) {
        if (end == null) {
            return ReportingPeriod.unknown();
        }
        String label = isQuarterEnd(end)
                ? "TTM to %s (ending %s)".formatted(quarter(end).label(), end)
                : "TTM ending " + end;
        return new ReportingPeriod(PeriodType.TRAILING_TWELVE_MONTHS, end.plusDays(1).minusYears(1), end,
                label, null, null);
    }

    public static String fiscalYearLabel(int fiscalYear) {
        return "FY%02d".formatted(fiscalYear % 100);
    }

    private int monthsIntoFiscalYear(LocalDate date) {
        int firstMonth = fiscalYearEndMonth.getValue() % 12 + 1;
        return (date.getMonthValue() - firstMonth + 12) % 12;
    }
}
