package com.ashish.stockresearch.research.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The SEBI-style quarterly shareholding pattern of an Indian listed
 * company: how ownership splits between the promoter group, foreign and
 * domestic institutions, and the public.
 *
 * Percentages are of total shares outstanding and should sum to
 * approximately 100. A {@code null} category means the filing did not break
 * that category out - it does not mean nobody holds that category.
 *
 * No data source is currently wired up for this model; see
 * {@code UnsupportedShareholdingProvider} for why.
 *
 * @param asOfQuarterEnd the quarter the filing covers - shareholding data is always
 *                       a snapshot of a past quarter, never a live figure
 */
public record Shareholding(
        String symbol,
        String exchange,
        String companyName,
        BigDecimal promoterHoldingPercent,
        BigDecimal promoterPledgedPercent,
        BigDecimal foreignInstitutionalHoldingPercent,
        BigDecimal domesticInstitutionalHoldingPercent,
        BigDecimal publicHoldingPercent,
        LocalDate asOfQuarterEnd,
        DataProvenance provenance
) {
}
