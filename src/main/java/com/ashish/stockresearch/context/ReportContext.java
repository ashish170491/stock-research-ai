package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.SnapshotRun;

import java.util.List;
import java.util.Optional;

/**
 * The context a research report gives beside its ratios: for each, the company's own fiscal years and its
 * industry peers in the latest stored snapshot.
 *
 * @param symbol     the company
 * @param group      its industry group
 * @param run        the snapshot the peers come from; null when there is none to use
 * @param ratios     one entry per ratio that has any context, in the order the report shows them
 * @param notPrimary metric keys given no context because they are not primary measures for the group
 * @param peerNote   why no peer figures are given at all (no snapshot, or a group that is not one industry);
 *                   null when peers were looked for
 */
public record ReportContext(String symbol, IndustryGroup group, SnapshotRun run, List<Ratio> ratios,
                            List<String> notPrimary, String peerNote) {

    /**
     * @param metric  the report's metric key
     * @param history the company's own fiscal years; empty for a figure that is not yearly (P/E)
     * @param peers   the industry peers; empty when no peer figures are given
     */
    public record Ratio(String metric, Optional<OwnHistory> history, Optional<PeerContext> peers) {
    }

    public ReportContext {
        ratios = List.copyOf(ratios);
        notPrimary = List.copyOf(notPrimary);
    }

    public static ReportContext none(String symbol, IndustryGroup group, String why) {
        return new ReportContext(symbol, group, null, List.of(), List.of(), why);
    }
}
