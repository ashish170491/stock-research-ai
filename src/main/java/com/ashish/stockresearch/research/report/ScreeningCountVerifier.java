package com.ashish.stockresearch.research.report;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the counts in an answer about a screen against the counts the application made. A count is a
 * small whole number, which {@link NumericClaimVerifier} deliberately does not check ("3 years", list
 * numbering), so "only 12 stocks meet all criteria" could pass it whenever a 12 appears anywhere in the
 * evidence ("12 months"). Here it is checked against the screen's own "Summary (counted by the
 * application)" and table headers:
 *
 * <ul>
 *   <li>a count of stocks meeting every criterion, having a criterion that could not be assessed, or not
 *       screened must be the Summary's count of that;</li>
 *   <li>"N of M" must be a pair the screen states;</li>
 *   <li>any other count of stocks or companies must be a count the screen states.</li>
 * </ul>
 * A sentence with a count the screen does not state is removed. Nothing is checked when the evidence
 * holds no screen.
 */
@Component
public class ScreeningCountVerifier {

    static final String SUMMARY_HEADING = "#### Summary (counted by the application)";

    private static final Map<String, Integer> WORDS = Map.ofEntries(Map.entry("no", 0), Map.entry("zero", 0),
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
            Map.entry("thirteen", 13), Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16),
            Map.entry("seventeen", 17), Map.entry("eighteen", 18), Map.entry("nineteen", 19), Map.entry("twenty", 20));
    /** Not inside a larger number or word: the 5 of "18.5" is no count. */
    private static final String START = "(?<![\\p{L}\\d.,])";
    private static final String COUNT = "(\\d{1,3}|" + String.join("|", WORDS.keySet()) + ")";
    private static final String STOCKS = "(?:stocks?|companies|company|names|shares|banks?|lenders?|nbfcs?|insurers?)";
    private static final String MEETS_ALL = "(?:meet|meets|met|meeting|pass|passes|passed|passing|satisf\\w+|clear\\w*)"
            + "\\s+(?:all|every|each)\\b";

    /** "3 of the 46", "5 out of 6" */
    private static final Pattern PAIR = Pattern.compile(START + COUNT + "\\s+(?:of|out of)\\s+(?:the\\s+|all\\s+)?"
            + COUNT + "\\b", Pattern.CASE_INSENSITIVE);
    /** "3 stocks", "12 IT companies", "three of them" */
    private static final Pattern STOCK_COUNT = Pattern.compile(START + COUNT + "\\s+(?:[\\p{L}-]+\\s+){0,2}?" + STOCKS
            + "\\b", Pattern.CASE_INSENSITIVE);
    private static final String HAS_GAPS = "(?:(?:have|has|with)\\s+(?:at\\s+least\\s+)?(?:one|a|an)\\s+"
            + "(?:[\\p{L}-]+\\s+){0,2}?criteri(?:on|a)\\s+(?:that\\s+)?(?:could\\s+not|cannot|can't|couldn't)\\s+be\\s+"
            + "assessed|(?:have|has|with)\\s+(?:[\\p{L}-]+\\s+){0,3}?INSUFFICIENT_DATA)";
    private static final String NOT_SCREENED = "(?:were|was|are|is)\\s+not\\s+screened\\b";

    /** A count and what the Summary counts it as: "only 12 stocks meeting all criteria", "4 were not screened". */
    private enum Claim {
        MEETS_ALL(ScreeningCountVerifier.MEETS_ALL, "- (\\d+) of the (\\d+) screened stocks meet every criterion"),
        HAS_GAPS(ScreeningCountVerifier.HAS_GAPS, "- (\\d+) of the (\\d+) have at least one criterion"),
        NOT_SCREENED(ScreeningCountVerifier.NOT_SCREENED, "- (\\d+) stocks were not screened()");

        private final Pattern inAnswer;
        private final Pattern inEvidence;

        Claim(String claim, String evidence) {
            this.inAnswer = Pattern.compile(START + COUNT + "(?:\\s+(?:of|out of)\\s+(?:the\\s+)?" + COUNT
                    + ")?\\s+(?:[\\p{L}-]+\\s+){0,4}?" + claim, Pattern.CASE_INSENSITIVE);
            this.inEvidence = Pattern.compile(evidence);
        }
    }

    private static final Pattern EVIDENCE_PAIR = Pattern.compile("\\b(\\d+) of (?:the )?(\\d+)\\b");
    private static final Pattern EVIDENCE_COUNT = Pattern.compile(
            "\\b(\\d+) (?:other )?(?:screened )?stocks\\b|\\((\\d+) stocks\\)|- (\\d+) of the \\d+ have");
    /** Also before a sentence that starts with its count ("... assessed. 5 have ..."); a decimal has no space. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    public record Result(String text, List<String> removedStatements, List<String> wrongCounts) {
    }

    public Result verify(String answer, String evidence) {
        if (answer == null || answer.isBlank() || evidence == null || !evidence.contains(SUMMARY_HEADING)) {
            return new Result(answer, List.of(), List.of());
        }
        Counts counts = counts(evidence);
        List<String> removed = new ArrayList<>();
        Set<String> wrong = new LinkedHashSet<>();
        StringBuilder kept = new StringBuilder();
        for (String line : answer.split("\n", -1)) {
            StringBuilder keptLine = new StringBuilder();
            for (String sentence : SENTENCE_END.split(line)) {
                List<String> problems = problems(sentence, counts);
                if (!problems.isEmpty()) {
                    removed.add(sentence.strip());
                    wrong.addAll(problems);
                    continue;
                }
                if (!keptLine.isEmpty()) {
                    keptLine.append(' ');
                }
                keptLine.append(sentence);
            }
            String result = keptLine.toString();
            if (!line.isBlank() && result.replaceAll("^[\\s*#>|\\-\\d.]+", "").isBlank()) {
                continue;
            }
            kept.append(result).append('\n');
        }
        return new Result(kept.toString().stripTrailing(), List.copyOf(removed), List.copyOf(wrong));
    }

    /**
     * What the screen (or screens) in the evidence counted.
     *
     * @param claims for each kind of claim, the counts the Summary states, alone and as "N of M"
     */
    private record Counts(Map<Claim, Set<List<Integer>>> claims, Set<List<Integer>> pairs, Set<Integer> counts) {
    }

    private static Counts counts(String evidence) {
        Map<Claim, Set<List<Integer>>> claims = new java.util.EnumMap<>(Claim.class);
        Set<Integer> counts = new HashSet<>();
        for (Claim claim : Claim.values()) {
            Set<List<Integer>> stated = new HashSet<>();
            Matcher summary = claim.inEvidence.matcher(evidence);
            while (summary.find()) {
                int n = Integer.parseInt(summary.group(1));
                stated.add(summary.group(2).isEmpty() ? List.of(n) : List.of(n, Integer.parseInt(summary.group(2))));
                counts.add(n);
            }
            claims.put(claim, stated);
        }
        Set<List<Integer>> pairs = new HashSet<>();
        Matcher pair = EVIDENCE_PAIR.matcher(evidence);
        while (pair.find()) {
            int a = Integer.parseInt(pair.group(1));
            int b = Integer.parseInt(pair.group(2));
            pairs.add(List.of(a, b));
            counts.add(a);
            counts.add(b);
        }
        Matcher count = EVIDENCE_COUNT.matcher(evidence);
        while (count.find()) {
            for (int group = 1; group <= count.groupCount(); group++) {
                if (count.group(group) != null) {
                    counts.add(Integer.parseInt(count.group(group)));
                }
            }
        }
        return new Counts(claims, pairs, counts);
    }

    private static List<String> problems(String sentence, Counts counts) {
        List<String> problems = new ArrayList<>();
        Set<Integer> checked = new HashSet<>();
        for (Claim claim : Claim.values()) {
            Matcher matcher = claim.inAnswer.matcher(sentence);
            while (matcher.find()) {
                if (checked.contains(matcher.start(1))) {
                    continue;
                }
                int n = value(matcher.group(1));
                Set<List<Integer>> stated = counts.claims().get(claim);
                boolean ok = matcher.group(2) == null
                        ? stated.stream().anyMatch(s -> s.get(0) == n)
                        : stated.contains(List.of(n, value(matcher.group(2))));
                if (!ok) {
                    problems.add(matcher.group().strip());
                }
                checked.add(matcher.start(1));
            }
        }
        Matcher pair = PAIR.matcher(sentence);
        while (pair.find()) {
            if (checked.contains(pair.start(1))) {
                continue;
            }
            checked.add(pair.start(1));
            if (!counts.pairs().contains(List.of(value(pair.group(1)), value(pair.group(2))))) {
                problems.add(pair.group().strip());
            }
        }
        Matcher stocks = STOCK_COUNT.matcher(sentence);
        while (stocks.find()) {
            if (checked.contains(stocks.start(1)) || isYear(stocks.group(1))) {
                continue;
            }
            int n = value(stocks.group(1));
            if (n != 0 && !counts.counts().contains(n)) {
                problems.add(stocks.group().strip());
            }
        }
        return problems;
    }

    private static boolean isYear(String count) {
        return count.length() == 4;
    }

    private static int value(String count) {
        Integer word = WORDS.get(count.toLowerCase(Locale.ROOT));
        return word != null ? word : Integer.parseInt(count);
    }
}
