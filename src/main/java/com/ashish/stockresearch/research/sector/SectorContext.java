package com.ashish.stockresearch.research.sector;

import java.util.List;

/**
 * How to read a company's metrics given its industry group.
 *
 * @param basis                      the provider classification the group was derived from
 * @param primaryMetrics             metrics that describe this group's financial performance
 * @param lessMeaningfulMetrics      generic metrics that are not primary indicators for this group; no
 *                                   observation or conclusion is drawn from them
 * @param sectorMetricsNotAvailable  metrics this group needs that the current data source does not supply
 */
public record SectorContext(
        IndustryGroup group,
        String basis,
        List<String> primaryMetrics,
        List<String> lessMeaningfulMetrics,
        List<String> sectorMetricsNotAvailable,
        String note
) {

    public static SectorContext of(IndustryGroup group, String basis) {
        return new SectorContext(group, basis, group.primaryMetrics(), group.lessMeaningfulMetrics(),
                group.sectorMetrics(), group.note());
    }

    /** True if no observation or conclusion may be drawn from this application metric for this group. */
    public boolean isNonPrimary(String metricKey) {
        return group.isNonPrimary(metricKey);
    }
}
