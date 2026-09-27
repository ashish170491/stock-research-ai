package com.ashish.stockresearch.research.report;

import com.ashish.stockresearch.research.model.SourceType;

import java.time.LocalDate;

/**
 * One source used in a report and what it covered.
 *
 * @param covers      which report section drew on it
 * @param publishedAt when the source published the data, or null if it does not say
 * @param asOf        the date the data describes
 * @param periodEnd   the end of the latest reporting period covered, for filing data
 */
public record SourceReference(
        String source,
        SourceType sourceType,
        String covers,
        LocalDate publishedAt,
        LocalDate asOf,
        LocalDate periodEnd
) {
}
