package com.ashish.stockresearch.research.report;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last line of defence on model-written interpretation. Removes whole
 * sentences - it never rewrites, so it cannot put words in the model's mouth.
 *
 * Two kinds of claim:
 * <ul>
 *   <li><b>Evidence-checked</b> - market-position and superlative claims
 *       ("dominates", "largest"). Kept only if the key phrase appears in the
 *       evidence the model was given (e.g. the provider's business summary).</li>
 *   <li><b>Never supported</b> - claims the application never computes or
 *       retrieves, so no evidence can back them: growth acceleration or
 *       momentum (CAGRs are compared, never accelerations), leverage and other
 *       evaluative labels ("strong", "healthy", "efficient" - no criteria for
 *       them exist in the data), benchmark comparisons (no benchmarks are
 *       supplied), causal explanations ("driven by", "suggesting better cost
 *       control" - no cause is ever established), and source upgrades
 *       ("according to company filings" - the data comes from Yahoo Finance;
 *       advice to go and check filings is allowed). These are removed even if
 *       the words happen to occur in the evidence, e.g. a product named an
 *       "acceleration platform" in a business summary.</li>
 *   <li><b>Withheld topics</b> - when the application withheld every
 *       observation on a topic (its values are in a DATA_CONFLICT, failed
 *       verification, or are not primary indicators for the sector), any
 *       sentence drawing a trend or conclusion on that topic is removed. A
 *       sentence reporting the conflict or the withholding itself is kept.</li>
 * </ul>
 */
@Component
public class UnsupportedClaimFilter {

    private static final Pattern EVIDENCE_CHECKED = Pattern.compile(
            "\\b(dominat\\w*|market[- ]leader\\w*|market[- ]leadership|leading (?:bank|lender|player|company|"
                    + "position|provider|franchise)|largest|biggest|best[- ]in[- ]class|monopol\\w*|unrivall?ed|"
                    + "undisputed|world[- ]class|industry[- ]leading|number one|no\\. ?1|#1|top[- ]tier|"
                    + "market share|moat|major player|key player|significant scale|dominant player)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NEVER_SUPPORTED = Pattern.compile(
            "\\b(accelerat\\w*|decelerat\\w*|momentum|"
                    // leverage labels - no leverage criteria exist in the data
                    + "(?:high|low|elevated|excessive|significant|heavy|substantial|moderate|minimal|modest|conservative)"
                    + " (?:leverage|gearing|indebtedness)|(?:highly|over|under|lowly|heavily)[- ]?(?:leveraged|geared)|"
                    + "conservative (?:capital structure|balance sheet|financing)|"
                    + "(?:debt[- ]to[- ]equity|leverage|gearing|debt levels?|indebtedness)\\b[^.]{0,40}?\\b(?:low|high|"
                    + "elevated|manageable|comfortable|concerning|excessive|limited|minimal)|"
                    // evaluative labels - the data supplies no criteria for any of them
                    + "financially (?:healthy|sound|strong|stable|robust)|"
                    + "steadily|steady (?:growth|performance|profitability)|resilien\\w*|remained stable|"
                    + "(?:stable|sound|prudent|healthy|comfortable) (?:capital structure|balance sheet|financial position)|"
                    + "strong(?:er|est|ly)?|weak(?:er|est|ness)?|(?:un)?healthy|poor(?:er|ly)?|excellent|attractive|"
                    + "robust|solid|impressive|(?:in)?efficient(?:ly)?|efficiency gains|high[- ]quality|"
                    + "modest profitability|superior|inferior|"
                    // benchmark comparisons - no benchmarks are supplied
                    + "(?:industry|sector|peer|market) (?:benchmarks?|averages?|norms?|standards?|medians?)|"
                    + "(?:relative|compared) to (?:its |the )?(?:peers|industry|sector|competitors)|"
                    + "than (?:its |the )?(?:peers|industry|sector|competitors))\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Causal claims - the data never establishes a cause. A connective that
     * explains a data problem ("differ due to different definitions") is not a
     * business cause and is allowed.
     */
    private static final Pattern CAUSAL = Pattern.compile(
            "\\b(?:due to|because of|driven by|as a result of|owing to|attributable to|thanks to|caused by|"
                    + "stemming from|on the back of|on account of)\\b"
                    + "(?!(?:\\W+\\w+){0,4}?\\W+(?:definitions?|data|conflicts?|DATA_CONFLICT|discrepanc\\w*|gaps?|"
                    + "unavailab\\w*|missing|methodolog\\w*|reporting)\\b)"
                    + "|\\b(?:suggest|indicat|reflect|point)\\w*\\s+(?:to\\s+)?(?:better|improved|weaker|tighter|effective|"
                    + "disciplined|higher|lower|rising|falling)?\\s*(?:cost control|cost management|pricing power|"
                    + "operational efficiency|execution|demand|management actions?|competitive pressure|market share)",
            Pattern.CASE_INSENSITIVE);

    /** Upgrading the source's credibility, e.g. "according to company filings". Advice to go and check them is fine. */
    private static final Pattern SOURCE_UPGRADE = Pattern.compile(
            "\\b(?:(?:company|official|regulatory|statutory|annual|quarterly|exchange|stock exchange|sebi) filings?|"
                    + "exchange data|audited (?:accounts|financials|statements))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVICE = Pattern.compile(
            "\\b(?:verify|check|consult|review|seek|refer to|investigat\\w*|examin\\w*|should|may be necessary)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Words that name a withheld topic in a sentence. */
    private static final java.util.Map<String, Pattern> TOPIC_TERMS = java.util.Map.of(
            "revenue growth", Pattern.compile("\\b(?:revenue|sales|top[- ]line)\\b", Pattern.CASE_INSENSITIVE),
            "net profit growth", Pattern.compile("\\b(?:net profit|profit|earnings|PAT|net income)\\b",
                    Pattern.CASE_INSENSITIVE),
            "net profit margin", Pattern.compile("\\b(?:net (?:profit )?margin|profit margin|profitability)\\b",
                    Pattern.CASE_INSENSITIVE),
            "operating margin", Pattern.compile("\\b(?:operating margin|EBIT margin)\\b", Pattern.CASE_INSENSITIVE),
            "return on equity", Pattern.compile("\\b(?:return on equity|ROE)\\b", Pattern.CASE_INSENSITIVE),
            "return on assets", Pattern.compile("\\b(?:return on assets|ROA)\\b", Pattern.CASE_INSENSITIVE),
            "debt-to-equity", Pattern.compile("\\b(?:debt[- ]to[- ]equity|leverage|gearing|capital structure|"
                    + "total debt)\\b", Pattern.CASE_INSENSITIVE));
    /** A trend, level or conclusion - what may not be said about a withheld topic. */
    private static final Pattern CONCLUSION = Pattern.compile(
            "\\d|\\b(?:gr[eo]w\\w*|rose|rise|rising|increas\\w*|decreas\\w*|declin\\w*|fell|fall\\w*|drop\\w*|"
                    + "improv\\w*|expan\\w*|contract\\w*|stable|steady|consistent|higher|lower|trend\\w*|CAGR|"
                    + "indicat\\w*|suggest\\w*|reflect\\w*|demonstrat\\w*|signal\\w*|stood at)\\b",
            Pattern.CASE_INSENSITIVE);
    /** A sentence about the data problem or the withholding itself, which is always allowed. */
    private static final Pattern ABOUT_THE_GAP = Pattern.compile(
            "\\b(?:conflict\\w*|DATA_CONFLICT|discrepan\\w*|differ\\w*|disagree\\w*|withheld|withhold\\w*|"
                    + "not generated|inconsisten\\w*|unavailable|not calculated|verif\\w*|INVALID|not (?:a )?primary|"
                    // not "limit\\w*": that would match company names ending in "Limited"
                    + "cannot|could not|limits?|limiting|limitations?|uncertain\\w*|confidence)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    public record Result(String text, List<String> removedSentences) {
    }

    /**
     * @param text     model output
     * @param evidence the text the model was given; an evidence-checked claim phrase that appears here
     *                 is supported
     */
    public Result filter(String text, String evidence) {
        return filter(text, evidence, List.of());
    }

    /**
     * @param withheldTopics topics the application withheld every observation on; sentences drawing a
     *                       trend or conclusion about them are removed
     */
    public Result filter(String text, String evidence, List<String> withheldTopics) {
        if (text == null || text.isBlank()) {
            return new Result(text, List.of());
        }
        String haystack = evidence == null ? "" : evidence.toLowerCase(Locale.ROOT);
        List<String> removed = new ArrayList<>();
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            StringBuilder keptLine = new StringBuilder();
            for (String sentence : SENTENCE_END.split(line)) {
                if (unsupported(sentence, haystack) || aboutWithheldTopic(sentence, withheldTopics)) {
                    removed.add(sentence.strip());
                } else {
                    if (!keptLine.isEmpty()) {
                        keptLine.append(' ');
                    }
                    keptLine.append(sentence);
                }
            }
            String result = keptLine.toString();
            // Drop a bullet or heading whose only sentence was removed, rather than leaving a stub.
            if (!line.isBlank() && result.replaceAll("^[\\s*#>\\-\\d.]+", "").isBlank()) {
                continue;
            }
            kept.append(result).append('\n');
        }
        return new Result(kept.toString().stripTrailing(), List.copyOf(removed));
    }

    private boolean aboutWithheldTopic(String sentence, List<String> withheldTopics) {
        if (ABOUT_THE_GAP.matcher(sentence).find() || !CONCLUSION.matcher(sentence).find()) {
            return false;
        }
        return withheldTopics.stream().map(TOPIC_TERMS::get).filter(java.util.Objects::nonNull)
                .anyMatch(terms -> terms.matcher(sentence).find());
    }

    private boolean unsupported(String sentence, String evidence) {
        if (NEVER_SUPPORTED.matcher(sentence).find() || CAUSAL.matcher(sentence).find()) {
            return true;
        }
        if (SOURCE_UPGRADE.matcher(sentence).find() && !ADVICE.matcher(sentence).find()) {
            return true;
        }
        Matcher matcher = EVIDENCE_CHECKED.matcher(sentence);
        while (matcher.find()) {
            if (!evidence.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
