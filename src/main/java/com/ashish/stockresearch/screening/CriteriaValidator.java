package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stage 2 of a screening request in words: turns what the extraction model read ({@link ExtractedCriteria})
 * into {@link ScreeningCriteria} that {@link StockScreener} can apply, in Java, and says how every part of
 * the request was read. The model's reading is never trusted as it stands:
 *
 * <ul>
 *   <li><b>Metrics</b> must be on the {@link ScreeningMetric} whitelist; anything else is rejected with the
 *       list of what can be screened.</li>
 *   <li><b>Thresholds</b> must be a number the user actually wrote - a model that turns "18%" into 0.18, or
 *       supplies a number for "low debt", is caught here - and within the metric's sane bounds. A threshold
 *       out of bounds is rejected, never clamped or converted.</li>
 *   <li><b>Operators</b> follow the user's words ("at least", "below") where the words say; the model's
 *       choice is used only where they do not.</li>
 *   <li><b>Vague qualities</b> ("low debt", "profitable") are read as a preset's own threshold for the metric
 *       ({@code app.screening.vague-terms}), found in the user's words by Java as well as by the model.
 *       Judgements ("cheap", "undervalued") are never read as a threshold at all.</li>
 *   <li><b>Presets and industry groups</b> are used only when the request names them.</li>
 * </ul>
 *
 * A group is screened only by criteria that are all primary metrics for it: when the request does not
 * limit itself to a group, a bank is not screened on "low debt" at all rather than on the request's other
 * criteria alone. When the request asks for that group, the criteria that do not apply to it are dropped
 * and the user is told.
 */
@Component
public class CriteriaValidator {

    static final String CUSTOM = "custom";

    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?");
    private static final Pattern THRESHOLD = Pattern.compile("^\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*(?:%|x|times)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** Universes that cannot be screened yet. */
    private static final Pattern OTHER_UNIVERSE = Pattern.compile(
            "\\b(?:nifty\\s*(?:next\\s*50|100|200|500|midcap\\s*\\d*|smallcap\\s*\\d*|bank)|next\\s*50|mid[- ]?caps?"
                    + "|small[- ]?caps?|large[- ]?caps? beyond|sensex|bse\\s*\\d+)\\b",
            Pattern.CASE_INSENSITIVE);
    /** The user's comparison words, in the order they are checked: "not less than" is "at least", not "less than". */
    private static final List<Map.Entry<Pattern, Operator>> OPERATOR_WORDS = List.of(
            Map.entry(Pattern.compile("\\bat least\\b|\\bor (?:more|higher|above)\\b|\\bminimum\\b|\\bmin\\b|≥|>=|"
                    + "\\b(?:not|no) (?:less|lower) than\\b", Pattern.CASE_INSENSITIVE), Operator.AT_LEAST),
            Map.entry(Pattern.compile("\\bat most\\b|\\bor (?:less|lower|below)\\b|\\bmaximum\\b|\\bmax\\b|≤|<=|"
                    + "\\bup to\\b|\\b(?:not|no) (?:more|higher) than\\b", Pattern.CASE_INSENSITIVE), Operator.AT_MOST),
            Map.entry(Pattern.compile("\\babove\\b|\\bover\\b|\\b(?:more|greater|higher) than\\b|\\bexceed\\w*|>",
                    Pattern.CASE_INSENSITIVE), Operator.ABOVE),
            Map.entry(Pattern.compile("\\bbelow\\b|\\bunder\\b|\\b(?:less|lower) than\\b|<",
                    Pattern.CASE_INSENSITIVE), Operator.BELOW));
    /**
     * Words that name an industry group, as {@code SectorClassifier} assigns them. "IT" only in capitals, so
     * that "it" is never read as IT services.
     */
    private static final Map<IndustryGroup, Pattern> GROUP_WORDS = groupWords();

    private final ScreeningPresets presets;
    private final List<ScreeningProperties.VagueTerm> vagueTerms;
    private final List<String> judgementTerms;

    public CriteriaValidator(ScreeningPresets presets, ScreeningProperties properties) {
        this.presets = presets;
        this.vagueTerms = properties.vagueTerms();
        this.judgementTerms = properties.judgementTerms();
        for (ScreeningProperties.VagueTerm term : vagueTerms) {
            ScreeningCriteria preset = presets.find(term.preset()).orElseThrow(() -> new IllegalStateException(
                    "Vague term %s names an unknown preset '%s'".formatted(term.phrases(), term.preset())));
            for (ScreeningMetric metric : term.metrics()) {
                if (presetRule(preset, metric, Set.of()).isEmpty()) {
                    throw new IllegalStateException("Vague term %s: preset %s has no rule on %s to read it as"
                            .formatted(term.phrases(), term.preset(), metric));
                }
            }
        }
    }

    /**
     * @param message   the user's request, which every extracted value is checked against
     * @param extracted what the extraction model read; {@link ExtractedCriteria#none()} when it gave nothing
     * @param extractionFailed true when the extraction model gave no usable answer
     */
    public ScreenInterpretation interpret(String message, ExtractedCriteria extracted, boolean extractionFailed) {
        String text = message == null ? "" : message;
        ExtractedCriteria read = extracted == null ? ExtractedCriteria.none() : extracted;
        List<String> readings = new ArrayList<>();
        List<String> notApplied = new ArrayList<>();

        Optional<ScreeningCriteria> preset = preset(text, read.preset(), readings, notApplied);
        Set<IndustryGroup> groups = groups(text, read.industryGroups(), readings, notApplied);
        Matcher otherUniverse = OTHER_UNIVERSE.matcher(text);
        if (otherUniverse.find()) {
            notApplied.add("“%s”: only the Nifty 50 can be screened so far, so the screen covers the Nifty 50."
                    .formatted(otherUniverse.group().strip()));
        }

        // Stated criteria first, so a vague word the user also gave a number for is read as their number.
        List<Rule> rules = new ArrayList<>();
        Set<String> vague = new LinkedHashSet<>();
        Set<BigDecimal> numbers = numbersIn(text);
        Set<BigDecimal> accounted = new java.util.HashSet<>();
        for (ExtractedCriteria.Criterion criterion : read.criteria()) {
            stated(criterion, text, numbers, rules, readings, notApplied, vague, accounted);
        }
        Optional<String> unread = unreadNumbers(text, accounted, notApplied);
        vague.addAll(vaguePhrasesIn(text));
        for (String term : read.vagueTerms()) {
            if (contains(text, term)) {
                vague.add(normalise(term));
            }
        }
        for (String phrase : longestOnly(vague)) {
            vagueTerm(phrase, preset, groups, rules, readings, notApplied);
        }
        for (String judgement : judgementsIn(text)) {
            notApplied.add(("“%s” is a judgement, and this application does not judge stocks, so it was not "
                    + "applied. To screen on price or returns, give a metric and a number, such as a maximum P/E "
                    + "or a minimum earnings yield.").formatted(judgement));
        }

        if (unread.isPresent() && extractionFailed) {
            return ScreenInterpretation.refused(nothingToScreen(text, true), readings, notApplied);
        }
        if (unread.isPresent()) {
            // Screening without a criterion the user stated would list stocks that do not meet it.
            return ScreenInterpretation.refused(("I could not read every criterion in your request (%s was not "
                    + "matched to a criterion), so I did not run a screen rather than run one without it. Rephrase it "
                    + "as a metric and a number, for example “ROE above 18%%”.").formatted(unread.get()), readings,
                    notApplied);
        }
        if (preset.isEmpty() && rules.isEmpty()) {
            return ScreenInterpretation.refused(nothingToScreen(text, extractionFailed), readings, notApplied);
        }
        try {
            ScreeningCriteria criteria = preset.isPresent()
                    ? onPreset(preset.get(), rules, groups, readings)
                    : custom(rules, groups, readings);
            return ScreenInterpretation.run(criteria, readings, notApplied);
        } catch (IllegalArgumentException ex) {
            return ScreenInterpretation.refused("These criteria cannot be combined into one screen: "
                    + ex.getMessage() + ".", readings, notApplied);
        }
    }

    /** "Nifty 50", "BSE 500": a universe's name, not a criterion. */
    private static final Pattern UNIVERSE_NUMBER = Pattern.compile(
            "\\b(?:nifty\\s*(?:next\\s*|midcap\\s*|smallcap\\s*)?|next\\s*|bse\\s*|sensex\\s*)\\d+\\b", Pattern.CASE_INSENSITIVE);
    /** "top 10": the screen ranks every stock and is never cut to a number. */
    private static final Pattern TOP_N = Pattern.compile("\\btop\\s*(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    /** "5-year", "3 yr": a span; the snapshot's growth and averages are over 3 fiscal years. */
    private static final Pattern SPAN = Pattern.compile("\\b(\\d+)[\\s-]*(?:years?|yrs?|y)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * A number in the request that no criterion accounts for - the extraction model missed a criterion the
     * user stated. Universe names, "top N" and spans are not criteria; the last two are explained instead.
     */
    private static Optional<String> unreadNumbers(String text, Set<BigDecimal> accounted, List<String> notApplied) {
        String rest = UNIVERSE_NUMBER.matcher(text).replaceAll(" ");
        Matcher top = TOP_N.matcher(rest);
        if (top.find()) {
            notApplied.add("“%s”: the screen ranks every stock it screens and is not cut to a number of them."
                    .formatted(top.group()));
        }
        rest = TOP_N.matcher(rest).replaceAll(" ");
        Matcher span = SPAN.matcher(rest);
        while (span.find()) {
            if (!span.group(1).equals("3")) {
                notApplied.add(("“%s”: growth and averages are measured over the last 3 fiscal years, the longest "
                        + "span the data supports, so a %s-year span cannot be screened.").formatted(span.group(),
                        span.group(1)));
            }
        }
        rest = SPAN.matcher(rest).replaceAll(" ");
        return numbersIn(rest).stream()
                .filter(n -> accounted.stream().noneMatch(a -> a.compareTo(n) == 0))
                .filter(n -> !(n.scale() <= 0 && n.compareTo(BigDecimal.valueOf(1990)) >= 0
                        && n.compareTo(BigDecimal.valueOf(2100)) <= 0))
                .map(BigDecimal::toPlainString).sorted().findFirst();
    }

    /** A request the extraction could not read, or one naming nothing that can be screened. */
    private String nothingToScreen(String text, boolean extractionFailed) {
        String how = "Name a preset (%s), or give a metric and a number, for example “ROE above 18%% and D/E below "
                + "0.5”. The metrics that can be screened are: %s.";
        how = how.formatted(String.join(", ", presets.names()), Arrays.stream(ScreeningMetric.values())
                .map(ScreeningMetric::shortLabel).collect(Collectors.joining(", ")));
        if (extractionFailed && !numbersIn(text).isEmpty()) {
            return "I could not read the criteria in your request, so I did not run a screen rather than run one "
                    + "without them. " + how;
        }
        return "I found no criteria in your request that the screen can apply, so I did not run one. " + how;
    }

    // --- Presets and groups ---------------------------------------------------------------------------

    /** The preset the request names; the model's choice counts only if the request names it too. */
    private Optional<ScreeningCriteria> preset(String text, String modelPreset, List<String> readings,
                                               List<String> notApplied) {
        List<String> named = presets.names().stream().filter(name -> presetNamed(text, name)).toList();
        if (named.isEmpty()) {
            if (modelPreset != null && !modelPreset.isBlank() && presets.find(modelPreset).isEmpty()
                    && contains(text, modelPreset)) {
                notApplied.add("“%s” is not a screening preset; the presets are %s.".formatted(modelPreset,
                        String.join(", ", presets.names())));
            }
            return Optional.empty();
        }
        ScreeningCriteria preset = presets.find(named.get(0)).orElseThrow();
        readings.add("“%s” → the %s preset: %s".formatted(named.get(0).replace('-', ' '), preset.name(),
                preset.description() == null ? "" : preset.description().strip()));
        if (named.size() > 1) {
            notApplied.add("Only one preset can be applied at a time, so %s was used and %s not."
                    .formatted(named.get(0), String.join(", ", named.subList(1, named.size()))));
        }
        return Optional.of(preset);
    }

    /**
     * "quality-compounder", "quality compounders"; a one-word name only as "dividend preset" / "dividend screen",
     * so "dividend stocks" is a vague quality, not the preset.
     */
    private static boolean presetNamed(String text, String name) {
        String words = Arrays.stream(name.split("-")).map(Pattern::quote).collect(Collectors.joining("[\\s_-]+"));
        String suffix = name.contains("-") ? "s?" : "[\\s_-]+(?:preset|screen|criteria|rules)";
        return Pattern.compile("(?<![\\w-])" + words + suffix + "(?![\\w-])", Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
    }

    /**
     * The groups the request limits itself to. Where the request's own words name a group, those decide;
     * otherwise the model's groups are used if they are real groups. Every one is shown to the user.
     */
    private static Set<IndustryGroup> groups(String text, List<String> modelGroups, List<String> readings,
                                             List<String> notApplied) {
        Map<IndustryGroup, String> inWords = new EnumMap<>(IndustryGroup.class);
        GROUP_WORDS.forEach((group, words) -> {
            Matcher matcher = words.matcher(text);
            if (matcher.find()) {
                inWords.put(group, matcher.group().strip());
            }
        });
        Set<IndustryGroup> fromModel = EnumSet.noneOf(IndustryGroup.class);
        for (String name : modelGroups) {
            Optional<IndustryGroup> group = industryGroup(name);
            if (group.isEmpty() || group.get() == IndustryGroup.UNKNOWN) {
                notApplied.add("“%s” is not an industry group the screen knows, so the screen was not limited to it. "
                        .formatted(name) + "The groups are: " + Arrays.stream(IndustryGroup.values())
                        .filter(g -> g != IndustryGroup.UNKNOWN).map(IndustryGroup::name)
                        .collect(Collectors.joining(", ")) + ".");
            } else {
                fromModel.add(group.get());
            }
        }
        Set<IndustryGroup> groups = inWords.isEmpty() ? fromModel : EnumSet.copyOf(inWords.keySet());
        for (IndustryGroup group : groups) {
            String words = inWords.get(group);
            readings.add(words != null
                    ? "“%s” → only companies in the %s industry group".formatted(words, group)
                    : "Only companies in the %s industry group".formatted(group));
        }
        return groups;
    }

    static Optional<IndustryGroup> industryGroup(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return Arrays.stream(IndustryGroup.values()).filter(group -> group.name().equals(key)).findFirst();
    }

    // --- Stated criteria ------------------------------------------------------------------------------

    /** @param accounted the numbers of the request a criterion was read from, used or rejected */
    private static void stated(ExtractedCriteria.Criterion criterion, String text, Set<BigDecimal> numbers,
                               List<Rule> rules, List<String> readings, List<String> notApplied, Set<String> vague,
                               Set<BigDecimal> accounted) {
        String phrase = criterion.phrase() == null || criterion.phrase().isBlank()
                ? String.valueOf(criterion.metric()) : criterion.phrase().strip();
        boolean inRequest = contains(text, phrase);
        if (criterion.threshold() == null || criterion.threshold().isBlank()) {
            // no number: a quality asked for in words
            if (inRequest) {
                vague.add(normalise(phrase));
            }
            return;
        }
        Matcher number = THRESHOLD.matcher(criterion.threshold());
        if (!number.matches()) {
            notApplied.add("“%s”: the threshold ‘%s’ is not a number, so the criterion was not applied."
                    .formatted(phrase, criterion.threshold()));
            return;
        }
        BigDecimal threshold = new BigDecimal(number.group(1));
        Set<BigDecimal> written = inRequest ? numbersIn(phrase) : numbers;
        if (written.stream().noneMatch(n -> n.compareTo(threshold) == 0)) {
            if (inRequest && !written.isEmpty()) {
                // "ROE above 18%" read as 0.18: a converted number is never used
                notApplied.add(("“%s”: the threshold was read as %s, which is not the number written, so the "
                        + "criterion was not applied; thresholds are never converted.").formatted(phrase,
                        threshold.toPlainString()));
            } else if (inRequest) {
                // e.g. "low debt" given a number the user never wrote: read it as the vague quality it is
                vague.add(normalise(phrase));
            } else {
                notApplied.add(("“%s”: the threshold %s is not a number in your request, so it was not used; "
                        + "thresholds are never converted or supplied for you.").formatted(phrase, threshold.toPlainString()));
            }
            return;
        }
        accounted.add(threshold);
        Optional<ScreeningMetric> metric = metric(criterion.metric());
        if (metric.isEmpty()) {
            notApplied.add("“%s”: %s is not a metric the screen can test, so the criterion was not applied. It can "
                    .formatted(phrase, criterion.metric() == null ? "that" : "‘" + criterion.metric() + "’")
                    + "test: " + Arrays.stream(ScreeningMetric.values()).map(ScreeningMetric::shortLabel)
                    .collect(Collectors.joining(", ")) + ".");
            return;
        }
        if (!metric.get().withinBounds(threshold)) {
            ScreeningMetric m = metric.get();
            notApplied.add(("“%s”: %s is outside the range the screen accepts for %s (%s to %s), so the criterion "
                    + "was not applied. Thresholds are in the metric's own unit: %s.").formatted(phrase,
                    threshold.toPlainString(), m.shortLabel(), new Rule(m, Operator.AT_LEAST, m.lowestThreshold())
                            .thresholdDisplay(), new Rule(m, Operator.AT_LEAST, m.highestThreshold()).thresholdDisplay(),
                    m.unit() == com.ashish.stockresearch.research.model.Unit.PERCENT ? "a percentage, 15 for 15%"
                            : "a multiple, 0.5 for 0.5x"));
            return;
        }
        Optional<Operator> operator = inRequest ? operatorInWords(phrase) : Optional.empty();
        if (operator.isEmpty()) {
            operator = operator(criterion.operator());
        }
        if (operator.isEmpty()) {
            notApplied.add("“%s”: it does not say whether the value should be above or below %s, so the criterion "
                    .formatted(phrase, threshold.toPlainString()) + "was not applied.");
            return;
        }
        Rule rule = new Rule(metric.get(), operator.get(), threshold);
        if (rules.stream().anyMatch(r -> r.metric() == rule.metric() && r.operator() == rule.operator()
                && r.threshold().compareTo(rule.threshold()) == 0)) {
            return;
        }
        rules.add(rule);
        readings.add("“%s” → %s (%s)".formatted(phrase, rule.label(), metric.get().description()));
    }

    static Optional<ScreeningMetric> metric(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        String label = name.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(ScreeningMetric.values())
                .filter(metric -> metric.name().equals(key) || metric.shortLabel().toLowerCase(Locale.ROOT).equals(label))
                .findFirst();
    }

    static Optional<Operator> operator(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        for (Operator operator : Operator.values()) {
            if (operator.name().equals(key) || operator.symbol().equals(name.strip())) {
                return Optional.of(operator);
            }
        }
        return operatorInWords(name);
    }

    static Optional<Operator> operatorInWords(String phrase) {
        for (Map.Entry<Pattern, Operator> words : OPERATOR_WORDS) {
            if (words.getKey().matcher(phrase).find()) {
                return Optional.of(words.getValue());
            }
        }
        return Optional.empty();
    }

    // --- Vague qualities and judgements ---------------------------------------------------------------

    /** Configured vague phrases in the request that no number follows ("growing" but not "growing above 15%"). */
    private List<String> vaguePhrasesIn(String text) {
        List<String> found = new ArrayList<>();
        for (ScreeningProperties.VagueTerm term : vagueTerms) {
            for (String phrase : term.phrases()) {
                if (phrasePattern(phrase, true).matcher(text).find()) {
                    found.add(normalise(phrase));
                }
            }
        }
        return found;
    }

    private List<String> judgementsIn(String text) {
        return longestOnly(judgementTerms.stream().filter(term -> phrasePattern(term, false).matcher(text).find())
                .map(CriteriaValidator::normalise).toList());
    }

    private void vagueTerm(String phrase, Optional<ScreeningCriteria> preset, Set<IndustryGroup> groups,
                           List<Rule> rules, List<String> readings, List<String> notApplied) {
        if (judgementTerms.stream().anyMatch(term -> phrasePattern(term, false).matcher(phrase).find())) {
            return; // reported as a judgement
        }
        if (GROUP_WORDS.values().stream().anyMatch(words -> words.matcher(phrase).find())
                || presets.names().stream().anyMatch(name -> presetNamed(phrase, name))) {
            return; // a kind of company or a preset, already read as such
        }
        Optional<ScreeningProperties.VagueTerm> term = vagueTerms.stream()
                .filter(t -> t.phrases().stream().anyMatch(p -> phrasePattern(p, false).matcher(phrase).find()))
                .findFirst();
        if (term.isEmpty()) {
            notApplied.add(("“%s”: the application has no defined reading for this, so it was not applied. Give a "
                    + "metric and a number instead.").formatted(phrase));
            return;
        }
        for (ScreeningMetric metric : term.get().metrics()) {
            if (rules.stream().anyMatch(rule -> rule.metric() == metric)) {
                readings.add("“%s”: covered by your own %s criterion".formatted(phrase, metric.shortLabel()));
                continue;
            }
            if (preset.isPresent() && presetCovers(preset.get(), metric, groups)) {
                readings.add("“%s”: covered by the %s preset's %s criterion".formatted(phrase, preset.get().name(),
                        metric.shortLabel()));
                continue;
            }
            ScreeningCriteria source = presets.find(term.get().preset()).orElseThrow();
            Rule rule = presetRule(source, metric, groups).orElseThrow();
            rules.add(rule);
            readings.add("“%s” → %s (%s): the %s preset's threshold, as no number was given".formatted(phrase,
                    rule.label(), metric.description(), source.name()));
        }
    }

    /** Whether the preset already tests the metric for every group asked for (non-financial ones when none is). */
    private static boolean presetCovers(ScreeningCriteria preset, ScreeningMetric metric, Set<IndustryGroup> groups) {
        java.util.function.Predicate<List<Rule>> tests = rules -> rules.stream().anyMatch(rule -> rule.metric() == metric);
        return groups.isEmpty() ? tests.test(preset.nonFinancialRules())
                : groups.stream().allMatch(group -> preset.rulesFor(group).map(tests::test).orElse(false));
    }

    /** The preset's rule on a metric: for the one group asked for if it has its own, else the default rule. */
    private static Optional<Rule> presetRule(ScreeningCriteria preset, ScreeningMetric metric, Set<IndustryGroup> groups) {
        if (groups.size() == 1) {
            Optional<Rule> own = preset.groupRules().getOrDefault(groups.iterator().next(), List.of()).stream()
                    .filter(rule -> rule.metric() == metric).findFirst();
            if (own.isPresent()) {
                return own;
            }
        }
        return preset.nonFinancialRules().stream().filter(rule -> rule.metric() == metric).findFirst();
    }

    // --- Building the criteria ------------------------------------------------------------------------

    /** Criteria from the request alone. */
    private static ScreeningCriteria custom(List<Rule> rules, Set<IndustryGroup> groups, List<String> readings) {
        Map<IndustryGroup, List<Rule>> byGroup = new EnumMap<>(IndustryGroup.class);
        Map<IndustryGroup, String> reasons = new EnumMap<>(IndustryGroup.class);
        Map<IndustryGroup, String> excluded = new EnumMap<>(IndustryGroup.class);
        for (IndustryGroup group : ScreeningCriteria.FINANCIAL) {
            if (!groups.isEmpty() && !groups.contains(group)) {
                continue;
            }
            Optional<List<Rule>> applicable = forFinancialGroup(group, rules, groups, excluded, readings);
            if (applicable.isPresent() && !applicable.get().isEmpty()) {
                byGroup.put(group, applicable.get());
            } else if (applicable.isPresent()) {
                reasons.put(group, "none of the criteria in the request applies to %s companies".formatted(group));
            }
        }
        excluded.forEach((group, labels) -> reasons.put(group, notPrimary(labels, group)));
        boolean onlyFinancial = !groups.isEmpty() && ScreeningCriteria.FINANCIAL.containsAll(groups);
        List<Rule> nonFinancial = onlyFinancial ? List.of() : rules;
        if (nonFinancial.isEmpty() && byGroup.isEmpty()) {
            throw new IllegalArgumentException("none of them applies to %s companies".formatted(groups.stream()
                    .map(IndustryGroup::name).collect(Collectors.joining(", "))));
        }
        notScreenedNote(excluded, readings);
        return new ScreeningCriteria(CUSTOM, "criteria read from your request", nonFinancial, byGroup,
                tieBreak(rules, byGroup), groups, reasons);
    }

    /** A preset with the request's own criteria added; one on the same metric replaces the preset's. */
    private static ScreeningCriteria onPreset(ScreeningCriteria preset, List<Rule> extra, Set<IndustryGroup> groups,
                                              List<String> readings) {
        if (extra.isEmpty()) {
            return preset.withIndustryGroups(groups);
        }
        Set<ScreeningMetric> replaced = extra.stream().map(Rule::metric).collect(Collectors.toSet());
        List<Rule> nonFinancial = merged(preset.nonFinancialRules(), extra, replaced, preset.name(), readings);
        Map<IndustryGroup, List<Rule>> byGroup = new EnumMap<>(IndustryGroup.class);
        Map<IndustryGroup, String> reasons = new EnumMap<>(IndustryGroup.class);
        Map<IndustryGroup, String> excluded = new EnumMap<>(IndustryGroup.class);
        preset.groupRules().forEach((group, presetRules) -> {
            if (!groups.isEmpty() && !groups.contains(group)) {
                return;
            }
            forFinancialGroup(group, extra, groups, excluded, readings).ifPresent(applicable ->
                    byGroup.put(group, merged(presetRules, applicable, replaced, null, null)));
        });
        excluded.forEach((group, labels) -> reasons.put(group, notPrimary(labels, group)));
        notScreenedNote(excluded, readings);
        return new ScreeningCriteria(preset.name() + " with your criteria", preset.description(), nonFinancial,
                byGroup, preset.tieBreak(), groups, reasons);
    }

    private static List<Rule> merged(List<Rule> presetRules, List<Rule> extra, Set<ScreeningMetric> replaced,
                                     String presetName, List<String> readings) {
        List<Rule> rules = new ArrayList<>();
        for (Rule rule : presetRules) {
            if (!replaced.contains(rule.metric())) {
                rules.add(rule);
            } else if (readings != null) {
                readings.add("Your %s criterion replaces the %s preset's %s".formatted(rule.metric().shortLabel(),
                        presetName, rule.label()));
            }
        }
        rules.addAll(extra);
        return rules;
    }

    /**
     * The request's rules that a financial group can be screened by. A group the request asked for keeps
     * the rules that apply to it (possibly none), and the user is told which were dropped; any other group
     * is screened only if every rule applies, and otherwise is empty and recorded in {@code excluded}.
     */
    private static Optional<List<Rule>> forFinancialGroup(IndustryGroup group, List<Rule> rules,
                                                          Set<IndustryGroup> requested,
                                                          Map<IndustryGroup, String> excluded, List<String> readings) {
        List<Rule> notPrimary = rules.stream().filter(rule -> group.isNonPrimary(rule.metric().sectorKey())).toList();
        if (notPrimary.isEmpty()) {
            return Optional.of(rules);
        }
        String labels = notPrimary.stream().map(rule -> rule.metric().shortLabel()).distinct()
                .collect(Collectors.joining(", "));
        if (requested.contains(group)) {
            readings.add("%s: not a primary metric for %s companies, so not applied to them".formatted(labels, group));
            return Optional.of(rules.stream().filter(rule -> !notPrimary.contains(rule)).toList());
        }
        excluded.put(group, labels);
        return Optional.empty();
    }

    private static String notPrimary(String labels, IndustryGroup group) {
        return "%s is not a primary metric for %s companies, so these criteria are not applied to them"
                .formatted(labels, group);
    }

    private static void notScreenedNote(Map<IndustryGroup, String> excluded, List<String> readings) {
        Map<String, List<String>> byLabels = new LinkedHashMap<>();
        excluded.forEach((group, labels) -> byLabels.computeIfAbsent(labels, key -> new ArrayList<>()).add(group.name()));
        byLabels.forEach((labels, groupNames) -> readings.add("Not screened: %s companies, as %s is not a primary "
                .formatted(String.join(", ", groupNames), labels) + "metric for them"));
    }

    /** The first rule whose metric is primary for every group that has rules, in the rule's direction. */
    private static ScreeningCriteria.TieBreak tieBreak(List<Rule> rules, Map<IndustryGroup, List<Rule>> byGroup) {
        for (Rule rule : rules) {
            if (byGroup.keySet().stream().noneMatch(group -> group.isNonPrimary(rule.metric().sectorKey()))) {
                boolean higherFirst = rule.operator() == Operator.AT_LEAST || rule.operator() == Operator.ABOVE;
                return new ScreeningCriteria.TieBreak(rule.metric(), higherFirst);
            }
        }
        throw new IllegalArgumentException("no criterion applies to every group screened, to order the results by");
    }

    // --- Text helpers ---------------------------------------------------------------------------------

    /**
     * A phrase matched whole, with any spacing or hyphen between its words. With {@code noNumberAfter}, not
     * when a number follows within three words ("growing above 15%"): then the phrase is part of a criterion
     * the user stated. "and", "with", "or" and "but" end the look-ahead, so "low debt and ROE above 18%" still
     * asks for low debt.
     */
    private static Pattern phrasePattern(String phrase, boolean noNumberAfter) {
        String words = Arrays.stream(phrase.strip().split("[\\s-]+")).map(Pattern::quote)
                .collect(Collectors.joining("[\\s-]+"));
        String after = noNumberAfter
                ? "(?!(?:\\s+(?!(?:and|with|or|but)\\b)[\\p{L}/%-]+){0,3}\\s*[<>≥≤=]?\\s*-?\\d)" : "";
        return Pattern.compile("(?<![\\p{L}\\d-])" + words + "(?![\\p{L}\\d-])" + after, Pattern.CASE_INSENSITIVE);
    }

    private static boolean contains(String text, String phrase) {
        return phrase != null && !phrase.isBlank() && normalise(text).contains(normalise(phrase));
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", " ").strip();
    }

    /** Drops a phrase that is part of a longer one found too ("high dividend" in "high dividend yield"). */
    private static List<String> longestOnly(java.util.Collection<String> phrases) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(phrases));
        return distinct.stream().filter(phrase -> distinct.stream()
                .noneMatch(other -> !other.equals(phrase) && (" " + other + " ").contains(" " + phrase + " ")))
                .toList();
    }

    static Set<BigDecimal> numbersIn(String text) {
        Set<BigDecimal> numbers = new java.util.HashSet<>();
        Matcher matcher = NUMBER.matcher(text.replaceAll("(?<=\\d),(?=\\d)", ""));
        while (matcher.find()) {
            numbers.add(new BigDecimal(matcher.group()));
        }
        return numbers;
    }

    private static Map<IndustryGroup, Pattern> groupWords() {
        Map<IndustryGroup, String> words = new EnumMap<>(IndustryGroup.class);
        // "IT" only in capitals; the rest in any case
        words.put(IndustryGroup.IT_SERVICES, "\\bIT\\b|\\bI\\.T\\.|(?i:\\b(?:software|tech|technology|infotech|it services)\\b)");
        // not the "bank" of "non-bank lenders", which are NBFCs
        words.put(IndustryGroup.BANK, "(?i:(?<![\\w-])bank(?:s|ing)?\\b)");
        words.put(IndustryGroup.NBFC, "(?i:\\bnbfcs?\\b|\\bnon[- ]bank(?:ing)?\\b)");
        words.put(IndustryGroup.INSURANCE, "(?i:\\binsur(?:ance|ers?)\\b)");
        words.put(IndustryGroup.FINANCIAL_OTHER, "(?i:\\bother financial (?:compan(?:y|ies)|services)\\b"
                + "|\\bfinancial (?:conglomerates?|holding compan(?:y|ies))\\b)");
        words.put(IndustryGroup.PHARMA, "(?i:\\bpharma\\w*|\\bdrug ?makers?\\b)");
        words.put(IndustryGroup.MANUFACTURING, "(?i:\\bmanufactur\\w*|\\bindustrials?\\b)");
        words.put(IndustryGroup.CONSUMER, "(?i:\\bconsumer\\b|\\bfmcg\\b)");
        words.put(IndustryGroup.OTHER, "(?i:\\bother industries\\b)");
        Map<IndustryGroup, Pattern> patterns = new EnumMap<>(IndustryGroup.class);
        // the group's own name, as the Screener page and the tool write it, always counts
        words.forEach((group, pattern) -> patterns.put(group, Pattern.compile(pattern + "|\\b" + group.name() + "\\b")));
        return patterns;
    }
}
