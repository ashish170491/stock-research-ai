package com.ashish.stockresearch.research.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Object-level metadata attached to every research result so the model
 * always knows where the data came from and how old it is. Individual
 * numbers carry their own {@link SourceInfo} and {@link ReportingPeriod}
 * inside each {@link FinancialDataPoint}.
 *
 * @param source      human-readable provider name
 * @param sourceType  the kind of source
 * @param freshness   whether this is live, delayed, a filing, or history
 * @param retrievedAt when this application fetched it
 * @param publishedAt when the source published the underlying data, or null if it does not say
 * @param asOf        the date the data describes (trading date, series end). Null means the
 *                    provider gave no as-of date - which is itself important, not a licence
 *                    to assume "today".
 * @param periodEnd   for filing data, the end of the latest reporting period covered; null otherwise
 * @param note        caveats worth surfacing to the user
 */
public record DataProvenance(
        String source,
        SourceType sourceType,
        DataFreshness freshness,
        Instant retrievedAt,
        LocalDate publishedAt,
        LocalDate asOf,
        LocalDate periodEnd,
        String note
) {
}
