package com.ashish.stockresearch.research.sector;

import java.util.List;

/**
 * How to read a company's metrics given its industry group.
 *
 * @param basis                      the provider classification the group was derived from
 * @param lessMeaningfulMetrics      generic metrics that do not mean the same thing in this group
 * @param sectorMetricsNotAvailable  metrics this group needs that the current data source does not supply
 */
public record SectorContext(
        IndustryGroup group,
        String basis,
        List<String> lessMeaningfulMetrics,
        List<String> sectorMetricsNotAvailable,
        String note
) {

    public static SectorContext of(IndustryGroup group, String basis) {
        return new SectorContext(group, basis, group.lessMeaningfulMetrics(), group.sectorMetrics(), group.note());
    }
}
