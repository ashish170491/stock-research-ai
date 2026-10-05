package com.ashish.stockresearch.context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Keeps the context beside each ratio in the application's words only. Where a value sits - among the company's
 * own years or among its peers - is calculated, verified and rendered by Java ({@link ContextRenderer}), and the
 * reader sees it beside the ratio. A model sentence that restates it is removed rather than checked: a place or a
 * comparison can be worded in endless ways ("11th among its peers", "in the top three", "below the median"), and a
 * check that recognises some of them lets the rest through.
 *
 * <p>Removed: any sentence that compares the company with other companies (peers, the snapshot, a median, other
 * banks), and any sentence that places a value among years or companies (the highest or lowest of the years, the
 * 7th highest, a rank) or against an unstated norm ("relatively low", "a moderate return on equity"). Also removed,
 * whatever words surround it: a unit-bearing figure ("13%", "1 times book") in the same sentence as a plain mention
 * of other companies ("banks", "lenders", "this group") - a figure with no trigger word of its own can still be a
 * peer statistic rounded to a coarser precision ("Nifty 500 lenders earn about 13% on equity", true median 13.10%),
 * and {@code NumericClaimVerifier} grounds a whole number by exact match anywhere in the facts, with no link to
 * what that number is about. The caller says how many were removed and why.
 */
public final class PositionClaimFilter {

    /** Comparisons with other companies. */
    private static final Pattern PEERS = Pattern.compile(
            "\\b(?:peers?|snapshot|medians?|competitors?|rivals?|sector average|industry average"
                    + "|other (?:banks|companies|lenders|insurers|stocks|firms|players)"
                    + "|(?:among|than|versus|vs\\.?|above|below|exceeds?|exceeding) (?:\\w+\\s+){0,4}"
                    + "(?:banks|companies|lenders|insurers|stocks|firms)"
                    + "|most (?:\\w+\\s+){0,3}(?:banks|companies|lenders|insurers|stocks|firms|peers)|handful of|typical"
                    + "|in the middle of|the middle of (?:\\w+\\s+){0,4}(?:banks|companies|lenders|insurers|stocks|firms)"
                    + "|the group'?s?|(?:banking|industry|sector|peer) group|in the index|the index's|the sector'?s?"
                    + "|across (?:the )?(?:\\w+ ){0,3}(?:banks|companies|lenders|insurers|stocks|firms|group))\\b",
            Pattern.CASE_INSENSITIVE);
    /** A place among years or companies. */
    private static final Pattern PLACE = Pattern.compile(
            "\\b\\d+(?:st|nd|rd|th)\\b(?!\\s+(?:quarter|century))"
                    + "|\\b(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|"
                    + "thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|last)"
                    + "[\\s-]+(?:highest|lowest|largest|smallest|only)\\b"
                    + "|\\b(?:ranks?|ranked|ranking)\\b"
                    + "|\\b(?:top|bottom)\\s+(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten|half|quartile)\\b"
                    + "|\\b(?:highest|lowest)\\b[^.;]*\\byears?\\b|\\byears?\\b[^.;]*\\b(?:highest|lowest)\\b"
                    // "relatively low", "a moderate return on equity": placed against a norm the data does not supply
                    + "|\\b(?:relatively|comparatively)\\b"
                    + "|\\b(?:moderate|modest|low|high|lower|higher|average|below[- ]average|above[- ]average)\\s+"
                    + "(?:(?:level|rate) of\\s+)?(?:returns? on|ROE|ROA|ROCE|(?:operating |net (?:profit )?)?margins?"
                    + "|P/?E|P/?B|price[- ]to[- ](?:book|earnings)|valuations?|(?:dividend|earnings) yields?)",
            Pattern.CASE_INSENSITIVE);
    /** A number with a unit a ratio is quoted in - a plain count ("6 peers") is not one. */
    private static final Pattern FIGURE_WITH_UNIT = Pattern.compile(
            "\\d(?:[\\d,]*\\.\\d+|[\\d,]*\\s?(?:%|x\\b))|\\b\\d+(?:\\.\\d+)?\\s+times\\b", Pattern.CASE_INSENSITIVE);
    /** A plain mention of other companies, with no comparison word of its own required. */
    private static final Pattern GROUP_MENTION = Pattern.compile(
            "\\b(?:banks|companies|lenders|insurers|stocks|firms|peers|this group|the group|the sector|the industry"
                    + "|in the index)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[^\\d])");

    public record Result(String text, List<String> removedStatements) {
    }

    private PositionClaimFilter() {
    }

    public static Result filter(String answer) {
        if (answer == null || answer.isBlank()) {
            return new Result(answer, List.of());
        }
        List<String> removed = new ArrayList<>();
        StringBuilder kept = new StringBuilder();
        for (String line : answer.split("\n", -1)) {
            List<String> keptSentences = new ArrayList<>();
            for (String sentence : SENTENCE_END.split(line)) {
                if (PEERS.matcher(sentence).find() || PLACE.matcher(sentence).find()
                        || (FIGURE_WITH_UNIT.matcher(sentence).find() && GROUP_MENTION.matcher(sentence).find())) {
                    removed.add(sentence.strip());
                } else {
                    keptSentences.add(sentence);
                }
            }
            String result = String.join(" ", keptSentences);
            if (!line.isBlank() && result.isBlank()) {
                continue;
            }
            kept.append(result).append('\n');
        }
        return new Result(kept.toString().stripTrailing(), List.copyOf(removed));
    }
}
