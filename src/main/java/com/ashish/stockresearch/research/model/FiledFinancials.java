package com.ashish.stockresearch.research.model;

import java.util.List;

/**
 * A company's annual results as filed with the exchange, one {@link FiledYear} per fiscal year, oldest
 * first, all of one {@link StatementScope}: consolidated and standalone years are never mixed.
 *
 * @param source   the source as a report should name it ("NSE corporate filings (XBRL)")
 * @param years    the fiscal years found, oldest first; a year with no usable filing is left out and
 *                 listed in {@code dataGaps}
 * @param dataGaps what could not be established, and why
 */
public record FiledFinancials(
        String symbol,
        String source,
        StatementScope scope,
        List<FiledYear> years,
        List<DataGap> dataGaps
) {

    public FiledFinancials {
        years = years == null ? List.of() : List.copyOf(years);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        if (years.stream().anyMatch(year -> year.scope() != scope)) {
            throw new IllegalArgumentException("consolidated and standalone years are never mixed");
        }
    }
}
