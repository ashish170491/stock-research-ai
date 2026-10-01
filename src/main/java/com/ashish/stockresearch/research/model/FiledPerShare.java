package com.ashish.stockresearch.research.model;

/**
 * The per-share figures of a company's latest filed fiscal year, for measuring today's share price against:
 * its basic earnings per share, and the paid-up equity capital and face value that give its number of
 * shares, to tell whether that EPS is still on today's share count.
 *
 * @param fiscalYear        the fiscal year the figures are for; unknown when there are none
 * @param unavailableReason why there are no figures, or null when there are
 */
public record FiledPerShare(
        ReportingPeriod fiscalYear,
        FinancialDataPoint basicEps,
        FinancialDataPoint paidUpEquityCapital,
        FinancialDataPoint faceValue,
        String unavailableReason
) {

    public FiledPerShare {
        if (unavailableReason == null && (fiscalYear == null || basicEps == null || paidUpEquityCapital == null
                || faceValue == null)) {
            throw new IllegalArgumentException("filed per-share figures need their year and all three figures");
        }
    }

    public static FiledPerShare of(FiledYear year) {
        return new FiledPerShare(year.period(), year.item(FiledItem.BASIC_EPS),
                year.item(FiledItem.PAID_UP_EQUITY_CAPITAL), year.item(FiledItem.FACE_VALUE_PER_SHARE), null);
    }

    public static FiledPerShare unavailable(String reason) {
        return new FiledPerShare(ReportingPeriod.unknown(), null, null, null, reason);
    }

    public boolean available() {
        return unavailableReason == null;
    }
}
