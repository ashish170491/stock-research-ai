package com.ashish.stockresearch.research.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Metadata attached to every research object so the model always knows where
 * a number came from and how old it is.
 *
 * @param source      human-readable provider name
 * @param freshness   whether this is live, delayed, a filing, or history
 * @param retrievedAt when this application fetched it
 * @param asOf        the date the data actually describes (fiscal period end, series end
 *                    date, filing quarter). {@code null} means the provider gave us no
 *                    as-of date - which is itself important, not a licence to assume "today".
 * @param note        caveats worth surfacing to the user (unofficial endpoint, ADR-based
 *                    figures, derived metric, etc.)
 */
public record DataProvenance(
        String source,
        DataFreshness freshness,
        Instant retrievedAt,
        LocalDate asOf,
        String note
) {
}
