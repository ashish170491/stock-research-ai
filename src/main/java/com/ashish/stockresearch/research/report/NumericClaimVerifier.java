package com.ashish.stockresearch.research.report;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks every financial figure in a model's answer against the text the
 * tools actually returned. A statement containing a figure that appears
 * nowhere in that evidence is removed: the model mistyped it, recalculated
 * it, converted it, or invented it - and in every case the number is not the
 * application's.
 *
 * <p>What counts as a financial figure: a number with a decimal point or
 * digit grouping, or one attached to a unit or currency (%, x, crore, lakh,
 * million, billion, ₹, $). Bare small integers (list numbering, "3 years"),
 * years, dates and period labels (FY26, Q1) are not checked.
 *
 * <p>A figure is grounded if the evidence contains the same number, or one
 * that rounds to it at the figure's own precision (16.4 for 16.44). Rounding
 * is accepted only for figures with at least one decimal place, so "16%" is
 * not grounded by 16.44%.
 */
@Component
public class NumericClaimVerifier {

    private static final Pattern DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d.,])(\\d{1,3}(?:,\\d{2,3})+|\\d+)(\\.\\d+)?");
    private static final Pattern UNIT_AFTER = Pattern.compile(
            "^\\s?(?:%|x\\b|crore|lakh|cr\\b|million|mn\\b|m\\b|billion|bn\\b|b\\b|trillion|t\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY_BEFORE = Pattern.compile("(?:₹|\\$|rs\\.?|inr|usd)\\s?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[^\\d])");

    public record Result(String text, List<String> removedStatements, List<String> ungroundedFigures) {
    }

    /**
     * @param answer   the model's answer
     * @param evidence everything the tools returned for this request
     */
    public Result verify(String answer, String evidence) {
        if (answer == null || answer.isBlank()) {
            return new Result(answer, List.of(), List.of());
        }
        Set<BigDecimal> known = numbersIn(evidence == null ? "" : evidence);
        List<String> removed = new ArrayList<>();
        LinkedHashSet<String> ungrounded = new LinkedHashSet<>();
        StringBuilder kept = new StringBuilder();
        for (String line : answer.split("\n", -1)) {
            StringBuilder keptLine = new StringBuilder();
            for (String sentence : SENTENCE_END.split(line)) {
                List<String> missing = ungroundedFigures(sentence, known);
                if (missing.isEmpty()) {
                    if (!keptLine.isEmpty()) {
                        keptLine.append(' ');
                    }
                    keptLine.append(sentence);
                } else {
                    removed.add(sentence.strip());
                    ungrounded.addAll(missing);
                }
            }
            String result = keptLine.toString();
            // Drop a bullet or table row whose only content was removed, rather than leaving a stub.
            if (!line.isBlank() && result.replaceAll("^[\\s*#>|\\-\\d.]+", "").isBlank()) {
                continue;
            }
            kept.append(result).append('\n');
        }
        return new Result(kept.toString().stripTrailing(), List.copyOf(removed), List.copyOf(ungrounded));
    }

    /** The financial figures in a piece of text that the evidence does not contain. */
    List<String> ungroundedFigures(String text, Set<BigDecimal> known) {
        List<String> missing = new ArrayList<>();
        String cleaned = DATE.matcher(text).replaceAll(" ");
        Matcher matcher = NUMBER.matcher(cleaned);
        while (matcher.find()) {
            String integer = matcher.group(1);
            String fraction = matcher.group(2);
            String after = cleaned.substring(matcher.end());
            String before = cleaned.substring(0, matcher.start());
            boolean grouped = integer.contains(",");
            boolean hasUnit = UNIT_AFTER.matcher(after).find() || CURRENCY_BEFORE.matcher(before).find();
            if (fraction == null && !grouped && !hasUnit) {
                continue;
            }
            BigDecimal value = new BigDecimal(integer.replace(",", "") + (fraction == null ? "" : fraction));
            if (fraction == null && !grouped && isYear(value)) {
                continue;
            }
            if (!grounded(value, fraction == null ? 0 : fraction.length() - 1, known)) {
                missing.add(matcher.group());
            }
        }
        return missing;
    }

    private static boolean grounded(BigDecimal value, int decimals, Set<BigDecimal> known) {
        BigDecimal target = value.stripTrailingZeros();
        if (known.contains(target)) {
            return true;
        }
        if (decimals == 0) {
            return false;
        }
        for (BigDecimal candidate : known) {
            if (candidate.scale() > decimals
                    && candidate.setScale(decimals, RoundingMode.HALF_UP).compareTo(value) == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isYear(BigDecimal value) {
        return value.scale() <= 0 && value.compareTo(BigDecimal.valueOf(1990)) >= 0
                && value.compareTo(BigDecimal.valueOf(2100)) <= 0;
    }

    static Set<BigDecimal> numbersIn(String evidence) {
        Set<BigDecimal> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(evidence);
        while (matcher.find()) {
            String digits = matcher.group(1).replace(",", "") + (matcher.group(2) == null ? "" : matcher.group(2));
            numbers.add(new BigDecimal(digits).stripTrailingZeros());
        }
        return numbers;
    }
}
