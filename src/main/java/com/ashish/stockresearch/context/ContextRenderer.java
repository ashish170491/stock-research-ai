package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.screening.SnapshotMetric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Renders a report's {@link ReportContext} in neutral words only - median, range, rank, the highest or lowest of
 * the years shown - and never says a value is good, bad, high or low for its kind. Every peer figure is stated
 * with the snapshot date and the number of peers it was calculated from. The same text is the evidence the model
 * interprets and the section a person reads.
 */
public final class ContextRenderer {

    static final String HEADING = "## Context beside each ratio";
    static final String INTRO = "_Each ratio beside the company's own fiscal years and beside other companies of its "
            + "industry group in the stored fundamentals snapshot, calculated and verified by the application. The "
            + "median, range and rank say where a value sits among the years and companies shown; they are not "
            + "benchmarks or targets. A year or company whose value is not VALID is shown with its status and left "
            + "out._";
    /**
     * Shown to the model in place of a peer line, so its evidence carries no peer figure to restate, round or
     * mis-copy. The reader still sees the real median, range and rank, rendered separately by {@link #render}.
     */
    static final String PEERS_OMITTED_NOTE = "Peer figures (median, range, rank) for this ratio are shown in the "
            + "report beside it, not in this evidence pack. Do not state, estimate or compare with them.";

    private ContextRenderer() {
    }

    /**
     * @param label names a metric key for a reader ("Return on equity"); the key itself when null
     */
    public static String render(ReportContext context, Function<String, String> label) {
        return render(context, label, true);
    }

    /**
     * What the model is given: the company's own fiscal years, exactly as the reader sees them, but with every peer
     * figure replaced by {@link #PEERS_OMITTED_NOTE}. A peer median, range or rank can then reach the reader only
     * through this class's own rendering of the real {@link ReportContext}, never through the model's words: a
     * number the model does not have, it cannot restate, round to a coarser precision, or misquote.
     */
    public static String renderForModel(ReportContext context, Function<String, String> label) {
        return render(context, label, false);
    }

    private static String render(ReportContext context, Function<String, String> label, boolean withPeerFigures) {
        Function<String, String> name = label == null ? key -> key : label;
        StringBuilder md = new StringBuilder(HEADING).append("\n\n").append(INTRO).append("\n\n");
        if (context.ratios().isEmpty()) {
            md.append("- No ratio could be given context.\n");
        }
        for (ReportContext.Ratio ratio : context.ratios()) {
            md.append("**").append(name.apply(ratio.metric())).append("** (").append(ratio.metric()).append(")\n");
            ratio.history().ifPresent(history -> md.append("- ").append(history(history)).append('\n'));
            ratio.peers().ifPresent(peers -> md.append("- ")
                    .append(withPeerFigures ? peers(peers) : PEERS_OMITTED_NOTE).append('\n'));
            md.append('\n');
        }
        if (!context.notPrimary().isEmpty()) {
            md.append("No context is given for ").append(String.join(", ", context.notPrimary().stream()
                            .map(name).toList()))
                    .append(": not primary measures for ").append(context.group()).append(" companies.\n");
        }
        if (context.peerNote() != null) {
            md.append(context.peerNote()).append('\n');
        }
        return md.toString();
    }

    static String history(OwnHistory history) {
        StringBuilder text = new StringBuilder("Own fiscal years (")
                .append(history.filed() ? "as filed with NSE" : "as the provider reports them").append("): ")
                .append(history.years().stream().map(year -> year.label() + " " + year.shown())
                        .collect(Collectors.joining(", ")))
                .append(". ");
        int usable = history.usableYears().size();
        if (!history.hasRange()) {
            return text.append("No range is given: ").append(history.lowest().statusReason()).append('.').toString();
        }
        text.append("Range of the ").append(usable).append(" VALID years: ").append(history.lowest().display())
                .append(" to ").append(history.highest().display()).append("; ");
        OwnHistory.Year latest = history.latest();
        if (history.latestRank() == null) {
            text.append(latest.label()).append(" is ").append(latest.shown()).append(", so it has no place in it.");
        } else {
            text.append(latest.label()).append(" is ").append(place(history.latestRank(), usable)).append(" of the ")
                    .append(usable).append(" years.");
        }
        return text.toString();
    }

    static String peers(PeerContext peers) {
        StringBuilder text = new StringBuilder().append(peers.group()).append(" peers in the ")
                .append(peers.run().universe().displayName()).append(" snapshot of ").append(peers.snapshotDate());
        if (!peers.calculated()) {
            return text.append(": no peer statistic - ").append(peers.median().statusReason()).append('.').toString();
        }
        text.append(" (").append(peers.count()).append(" peers with a usable value): median ")
                .append(peers.median().display()).append(", range ").append(peers.lowest().display()).append(" to ")
                .append(peers.highest().display()).append(". ");
        SnapshotMetric own = peers.companyValue();
        if (own == null) {
            text.append(peers.symbol()).append(" is not in that snapshot, so it is not ranked. ");
        } else if (peers.rank() == null) {
            text.append(peers.symbol()).append(" in that snapshot: ")
                    .append(own.status() == DataStatus.VALID ? "not cross-checked" : own.status().name())
                    .append(", so it is not ranked. ");
        } else {
            text.append(peers.symbol()).append(" in that snapshot: ").append(own.display())
                    .append(own.periodLabel() == null ? "" : " (" + own.periodLabel() + ")").append(", ")
                    .append(place(peers.rank(), peers.rankedOf())).append(" of ").append(peers.rankedOf())
                    .append(" companies (").append(peers.symbol()).append(" and its ").append(peers.count())
                    .append(" peers). ");
        }
        text.append("Periods: ").append(periods(peers.peers())).append(". Peers: ")
                .append(peers.peers().stream().map(PeerContext.Peer::symbol).collect(Collectors.joining(", ")))
                .append('.');
        return text.toString();
    }

    /** "FY26 for 20, FY25 for 4": each peer's value is its own latest period. */
    private static String periods(List<PeerContext.Peer> peers) {
        Map<String, Long> counts = peers.stream().collect(Collectors.groupingBy(
                peer -> peer.value().periodLabel() == null ? "period not stated" : peer.value().periodLabel(),
                LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> entry.getKey() + " for " + entry.getValue()).collect(Collectors.joining(", "));
    }

    /** "the highest", "the 3rd highest", "the lowest", counted from the highest of {@code of}. */
    static String place(int rank, int of) {
        if (rank == 1) {
            return "the highest";
        }
        if (rank == of) {
            return "the lowest";
        }
        return "the " + ordinal(rank) + " highest";
    }

    private static String ordinal(int n) {
        int lastTwo = n % 100;
        String suffix = lastTwo >= 11 && lastTwo <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}
