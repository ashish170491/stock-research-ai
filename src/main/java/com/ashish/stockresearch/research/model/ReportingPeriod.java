package com.ashish.stockresearch.research.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * The period a financial figure covers. Built by
 * {@code FiscalCalendar} from dates the provider actually supplied; a
 * period the provider did not state is {@link #unknown()}, never inferred
 * from today's date.
 *
 * @param label         human-readable label computed in Java, e.g. "Q1 FY27" or "FY26" -
 *                      the model must use this rather than derive its own
 * @param fiscalYear    the calendar year in which the fiscal year ends (FY27 -> 2027)
 * @param fiscalQuarter 1-4 within the fiscal year, for quarterly periods only
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportingPeriod(
        PeriodType type,
        LocalDate start,
        LocalDate end,
        String label,
        Integer fiscalYear,
        Integer fiscalQuarter
) {

    private static final ReportingPeriod UNKNOWN =
            new ReportingPeriod(PeriodType.UNKNOWN, null, null, "UNKNOWN (not stated by the source)", null, null);

    public ReportingPeriod {
        if (type == null) {
            throw new IllegalArgumentException("period type is required; use PeriodType.UNKNOWN");
        }
        if (type != PeriodType.UNKNOWN && end == null) {
            throw new IllegalArgumentException(type + " period needs an end date; use ReportingPeriod.unknown()");
        }
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("period start " + start + " is after end " + end);
        }
    }

    public static ReportingPeriod unknown() {
        return UNKNOWN;
    }

    public static ReportingPeriod pointInTime(LocalDate date) {
        return date == null ? UNKNOWN : new ReportingPeriod(PeriodType.POINT_IN_TIME, null, date, "As of " + date, null, null);
    }

    public static ReportingPeriod multiYear(LocalDate start, LocalDate end) {
        return new ReportingPeriod(PeriodType.MULTI_YEAR, start, end, start + " to " + end, null, null);
    }

    public boolean known() {
        return type != PeriodType.UNKNOWN;
    }
}
