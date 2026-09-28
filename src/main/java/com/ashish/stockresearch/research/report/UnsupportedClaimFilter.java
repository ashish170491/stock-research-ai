package com.ashish.stockresearch.research.report;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last line of defence on model-written interpretation. Removes whole
 * sentences - it never rewrites a claim, so it cannot put words in the model's
 * mouth. The one edit it makes is to the sentence after a removed one: a
 * leading connective ("However, ...") that tied it to the removed sentence is
 * dropped, and a sentence about the removed sentence's figures ("These figures
 * ...") is removed with it.
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
 *       advice to go and check filings is allowed), and judgements against
 *       criteria nobody supplied ("appears to meet", "in line with sector
 *       norms"). Speculation about what share-price moves reflect ("investor
 *       sentiment", "market pressures") is removed too, unless the sentence is
 *       advice to find out. These are removed even if
 *       the words happen to occur in the evidence, e.g. a product named an
 *       "acceleration platform" in a business summary.</li>
 *   <li><b>Withheld topics</b> - when the application withheld every
 *       observation on a topic (its values are in a DATA_CONFLICT, failed
 *       verification, or are not primary indicators for the sector), any
 *       sentence drawing a trend or conclusion on that topic is removed. A
 *       sentence reporting the conflict or the withholding itself is kept -
 *       unless it also calls a withheld value valid or reliable, which
 *       contradicts the withholding.</li>
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

    /** Valuation judgements - no benchmark, fair value or target exists in the data. */
    private static final String VALUATION_JUDGEMENTS =
            "(?:under|over)[- ]?valued|fairly valued|fair values?|intrinsic values?|price targets?|target prices?|"
                    + "margin of safety|cheap(?:ly|er|est)?|expensive|richly valued|"
                    + "(?:low|high|reasonable|modest|elevated|demanding|rich|stretched|compelling|premium|discounted?)"
                    + " (?:valuations?|multiples?|P/?E|P/?B|price[- ]to[- ](?:book|earnings))";
    /**
     * "The P/E of 15.13x is low", "trades at a discount": judgements whose own wording asserts them, so
     * they are never merely mentioned.
     */
    private static final String VALUATION_LEVEL =
            "trad(?:es|ing|ed) at a (?:premium|discount)|(?:P/?E|P/?B|price[- ]to[- ](?:book|earnings)|valuations?|multiples?|(?:earnings|dividend) yield)"
                    + "(?: ratios?)?(?:[^.]|\\.\\d){0,40}?\\b(?:is|are|looks?|appears?|seems?|remains?)\\s+"
                    + "(?:relatively |quite |very |fairly |somewhat |rather )?(?:low|high|reasonable|modest|elevated|"
                    + "demanding|rich|stretched|compelling|lofty|undemanding|generous)";
    /** Benchmark comparisons - no benchmarks are supplied. */
    private static final String BENCHMARK_COMPARISONS =
            "(?:industry|sector|peer|market) (?:benchmarks?|averages?|norms?|standards?|medians?)|"
                    + "(?:relative|compared) to (?:its |the )?(?:peers|industry|sector|competitors)|"
                    + "than (?:its |the )?(?:peers|industry|sector|competitors)";
    /** "(would) require external context (sector averages ...)": says a benchmark is missing. */
    private static final String NEEDS_CONTEXT =
            "\\b(?:requires?|needs?|would need)\\s+(?:[\\w-]+\\s+){0,2}?(?:context|benchmarks?|comparisons?)\\b";

    private static final Pattern NEVER_SUPPORTED = Pattern.compile(
            "\\b(accelerat\\w*|decelerat\\w*|momentum|"
                    // leverage labels - no leverage criteria exist in the data
                    + "(?:high|low|elevated|excessive|significant|heavy|substantial|moderate|minimal|modest|conservative|"
                    + "balanced|reasonable|manageable|comfortable)"
                    // "a moderate level of leverage" as well as "moderate leverage"
                    + "(?: (?:level|degree|amount) of)? (?:leverage|gearing|indebtedness)|(?:highly|over|under|lowly|heavily)[- ]?(?:leveraged|geared)|"
                    + "conservative (?:capital structure|balance sheet|financing)|"
                    + "(?:debt[- ]to[- ]equity|leverage|gearing|debt levels?|indebtedness)\\b[^.]{0,40}?\\b(?:low|high|"
                    + "elevated|manageable|comfortable|concerning|excessive|limited|minimal)|"
                    // evaluative labels - the data supplies no criteria for any of them
                    + "financially (?:healthy|sound|strong|stable|robust)|"
                    + "steadily|steady (?:growth|performance|profitability)|resilien\\w*|remained stable|"
                    + "(?:stable|sound|prudent|healthy|comfortable|balanced|reasonable) (?:capital structure|balance sheet|"
                    + "financial position)|"
                    + "strong(?:er|est|ly)?|weak(?:er|est|ness)?|(?:un)?healthy|poor(?:er|ly)?|excellent|attractive|"
                    + "robust|solid|impressive|(?:in)?efficient(?:ly)?|efficiency gains|high[- ]quality|"
                    + "modest profitability|superior|inferior|"
                    + "(?:relatively |broadly |largely |fairly )?stable (?:profitability|margins?|returns?|earnings|"
                    + "performance|revenues?|growth|profits?)|"
                    // judgements against criteria - none are supplied (roadmap Step 3 will supply them)
                    + "(?:appears?|seems?|looks?|likely) to (?:meet|satisfy|fulfil+|exceed|match|align with)|"
                    + "(?:meets?|met|meeting|satisf(?:y|ies|ied|ying)|fulfil+(?:s|ed|ing)?|in line with|aligns? with) "
                    + "(?:the |its |these |those |all |most |such )?(?:[\\w-]+ ){0,2}?(?:expectations|norms|"
                    + "priorities|benchmarks?|standards?|criteria|requirements|thresholds?)|"
                    + "(?:out|under)perform\\w*|"
                    // readings of what a figure says about the future - nothing in the data measures them
                    + "growth (?:potential|prospects|opportunit(?:y|ies))|balance between growth|"
                    + "(?:investor|market) expectations|priced in|"
                    + VALUATION_JUDGEMENTS + "|" + VALUATION_LEVEL + "|" + BENCHMARK_COMPARISONS + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Judgement words a sentence may mention while declining to make the judgement ("the data does
     * not show whether the stock is undervalued"). Every other never-supported claim is removed however
     * it is phrased.
     */
    private static final Pattern MENTIONABLE = Pattern.compile(
            "(?:" + VALUATION_JUDGEMENTS + "|" + BENCHMARK_COMPARISONS + ")", Pattern.CASE_INSENSITIVE);
    /** A sentence declining to judge says something is missing. */
    private static final Pattern ABSENCE = Pattern.compile(
            "\\b(?:no|not|none|neither|nor|without|cannot|unable|insufficient|lacks?|lacking|absence)\\b|n['’]t\\b|"
                    + NEEDS_CONTEXT, Pattern.CASE_INSENSITIVE);
    /** "whether (the stock is) ..." / "if (it is) ...": the judgement is the open question, not the answer. */
    private static final Pattern HYPOTHETICAL = Pattern.compile("\\b(?:whether|if)\\b", Pattern.CASE_INSENSITIVE);
    /** "does not supply (a) ...", "no ...", "without (a) ...": the judgement is what the data lacks. */
    private static final Pattern NOT_SUPPLIED = Pattern.compile(
            "\\b(?:(?:does|do|did)\\s+not|doesn['’]t|don['’]t|didn['’]t)\\s+(?:include|supply|provide|contain|"
                    + "offer|give|cover|have|report|return)\\b|\\b(?:lacks?|lacking|absence of)\\b|"
                    + "\\bno\\b(?!\\s+(?:means|longer|doubt|question))|\\bwithout\\b(?!\\s+(?:doubt|question))|"
                    + NEEDS_CONTEXT, Pattern.CASE_INSENSITIVE);
    /** "industry benchmarks are not provided": the judgement term is the subject of what is missing. */
    private static final Pattern NOT_SUPPLIED_AFTER = Pattern.compile(
            "^(?:\\s+(?:or|and|,)?\\s*[\\w/-]+){0,4}?\\s*(?:is|are|was|were)\\s+(?:not\\s+(?:provided|supplied|included|"
                    + "available|given|reported|part of)|unavailable|missing|absent)\\b", Pattern.CASE_INSENSITIVE);
    /** Ends the clause an introducer governs: a judgement after one of these is asserted, not mentioned. */
    private static final Pattern PIVOT = Pattern.compile(
            "[;:—–]|\\b(?:but|however|though|although|yet|while|whereas|and it|so it)\\b",
            Pattern.CASE_INSENSITIVE);
    /** "without benchmarks, the stock looks cheap" asserts the judgement after the introducer. */
    private static final Pattern COPULA = Pattern.compile(
            "\\b(?:is|are|was|were|be|being|been|looks?|appears?|seems?|remains?|trades?|trading)\\b|['’]s\\b",
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
                    // "due to the lack of a capital-employed figure" explains a data gap, not the business
                    + "(?!\\W+(?:the\\W+)?(?:lack|absence)\\W+of\\W+(?:[\\w-]+\\W+){0,4}?(?:data|figures?|fields?|"
                    + "values?|inputs?|information|disclosures?)\\b)"
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

    /**
     * Guesses at what a share-price move or a change in the figures reflects. Nothing in the data
     * measures sentiment, pressures or headwinds, so a sentence asserting them is removed unless it
     * is advice to go and find out.
     */
    private static final Pattern SPECULATION = Pattern.compile(
            "\\b(?:suggest|indicat|reflect|point|signal)\\w*\\s+(?:to\\s+)?(?:[\\w-]+\\s+){0,3}?"
                    + "(?:investor (?:sentiment|concerns?|confidence|pessimism|optimism)|market (?:pressures?|"
                    + "sentiment|headwinds)|(?:sector|industry|company)[- ]specific (?:challenges|headwinds|issues)|"
                    + "headwinds|(?:pricing|cost|margin|competitive) pressures?|(?:potential |operational )?challenges)"
                    + "|\\brais(?:es|ed|ing) questions about"
                    + "|\\bpressures? on (?:profitability|margins?|earnings)",
            Pattern.CASE_INSENSITIVE);

    /** Calling a value valid or reliable - never true of a value the application withheld. */
    private static final Pattern CALLED_VALID = Pattern.compile(
            "\\b(?:is|are|was|were|remains?)\\s+(?:still\\s+|both\\s+|fully\\s+)?(?:valid|reliable|accurate|"
                    + "trustworthy|confirmed|verified|correct)\\b",
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
                    + "total debt)\\b", Pattern.CASE_INSENSITIVE),
            "cash conversion", Pattern.compile("\\b(?:operating cash flow|cash conversion|OCF)\\b",
                    Pattern.CASE_INSENSITIVE),
            "return on capital employed", Pattern.compile("\\b(?:return on capital employed|ROCE)\\b",
                    Pattern.CASE_INSENSITIVE));
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
                    + "cannot|could not|limits?|limiting|limitations?|uncertain\\w*|confidence|no conclusions?|"
                    + "not possible|impossible|prevent\\w*|mismatch\\w*|lack of|absence of|missing)\\b",
            Pattern.CASE_INSENSITIVE);

    /** After . ! or ?, also when a closing quote or bracket follows: 'is "cheap." The ...' is two sentences. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?][\"'”’)]?)\\s+");

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
        boolean afterRemoval = false;
        for (String line : text.split("\n", -1)) {
            StringBuilder keptLine = new StringBuilder();
            for (String sentence : SENTENCE_END.split(line)) {
                if (unsupported(sentence, haystack) || aboutWithheldTopic(sentence, withheldTopics)
                        || (afterRemoval && SentenceContinuity.refersBack(sentence))) {
                    removed.add(sentence.strip());
                    afterRemoval = true;
                    continue;
                }
                if (afterRemoval && !sentence.isBlank()) {
                    sentence = SentenceContinuity.detached(sentence);
                    afterRemoval = false;
                }
                if (!keptLine.isEmpty()) {
                    keptLine.append(' ');
                }
                keptLine.append(sentence);
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
        boolean namesWithheldTopic = withheldTopics.stream().map(TOPIC_TERMS::get).filter(java.util.Objects::nonNull)
                .anyMatch(terms -> terms.matcher(sentence).find());
        if (!namesWithheldTopic) {
            return false;
        }
        // "The reported ROE of 13.84% is valid, though revenue figures conflict" mentions the conflict,
        // but contradicts the withholding.
        if (CALLED_VALID.matcher(sentence).find()) {
            return true;
        }
        return !ABOUT_THE_GAP.matcher(sentence).find() && CONCLUSION.matcher(sentence).find();
    }

    private boolean unsupported(String sentence, String evidence) {
        if ((NEVER_SUPPORTED.matcher(sentence).find() && !declinesToJudge(sentence))
                || CAUSAL.matcher(sentence).find()) {
            return true;
        }
        boolean advice = ADVICE.matcher(sentence).find();
        if ((SOURCE_UPGRADE.matcher(sentence).find() || SPECULATION.matcher(sentence).find()) && !advice) {
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

    /**
     * A sentence saying the data cannot support a valuation or benchmark judgement ("does not indicate
     * whether the stock is overvalued", "no fair value is supplied") is kept. It qualifies only if it says
     * something is missing and every never-supported phrase in it is a valuation or benchmark term that is
     * quoted, or governed by "whether"/"if" or a "does not supply"/"no"/"without" introducer in the same
     * clause - so "without benchmarks, the stock looks cheap" and "there is no fair value; it is
     * undervalued" are still removed.
     */
    private static boolean declinesToJudge(String sentence) {
        if (!ABSENCE.matcher(sentence).find()) {
            return false;
        }
        Matcher matcher = NEVER_SUPPORTED.matcher(sentence);
        boolean any = false;
        while (matcher.find()) {
            any = true;
            if (!MENTIONABLE.matcher(matcher.group()).matches() || !mentioned(sentence, matcher.start(), matcher.end())) {
                return false;
            }
        }
        return any;
    }

    private static boolean mentioned(String sentence, int start, int end) {
        // "... does not show whether it is undervalued, though it probably is" asserts it after all
        String after = sentence.substring(end);
        Matcher pivot = PIVOT.matcher(after);
        if (pivot.find() && COPULA.matcher(after.substring(pivot.end())).find()) {
            return false;
        }
        if (quoted(sentence, start, end) || NOT_SUPPLIED_AFTER.matcher(after).find()) {
            return true;
        }
        String before = sentence.substring(0, start);
        // a "whether" clause may contain a verb ("whether the stock is cheap") but not a comma, which would
        // end it ("even if there is no benchmark, the stock is cheap")
        return governedBy(before, HYPOTHETICAL, gap -> !gap.contains(",") && !PIVOT.matcher(gap).find())
                || governedBy(before, NOT_SUPPLIED, gap -> !PIVOT.matcher(gap).find() && !COPULA.matcher(gap).find());
    }

    /** Whether the nearest {@code introducer} before a term exists and the words between pass {@code clause}. */
    private static boolean governedBy(String before, Pattern introducer, java.util.function.Predicate<String> clause) {
        Matcher matcher = introducer.matcher(before);
        int end = -1;
        while (matcher.find()) {
            end = matcher.end();
        }
        return end >= 0 && clause.test(before.substring(end));
    }

    private static boolean quoted(String sentence, int start, int end) {
        int after = end;
        while (after < sentence.length() && ".,!?".indexOf(sentence.charAt(after)) >= 0) {
            after++;
        }
        return start > 0 && "\"'“‘".indexOf(sentence.charAt(start - 1)) >= 0
                && after < sentence.length() && "\"'”’".indexOf(sentence.charAt(after)) >= 0;
    }
}
