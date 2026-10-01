package com.ashish.stockresearch.glossary;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.Rule;
import com.ashish.stockresearch.screening.ScreeningCriteria;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.ScreeningPresets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What each metric measures, how this application calculates it and how to read it, for a reader new to
 * company figures - rendered by Java from fixed, reviewed text ({@link GlossaryEntries}), never written by
 * a model. Two parts of an entry are read from the application at runtime rather than written again:
 *
 * <ul>
 *   <li><b>Industry notes</b> come from {@link IndustryGroup}: the groups for which a metric is not a primary
 *       measure, with the group's own reason, and the groups that name it among their primary measures.</li>
 *   <li><b>Screen settings</b> come from {@link ScreeningPresets}: a preset's threshold on the metric, shown as
 *       that screen's setting, which the user can change - never as a standard.</li>
 * </ul>
 *
 * No part gives an ideal value or calls a value good or bad.
 */
@Component
public class MetricGlossary {

    public static final String SOURCE_NOTE = "_From the application's glossary: written in advance and reviewed, not generated "
            + "by the language model. It gives no ideal values: what is usual depends on the industry and the period, "
            + "so a figure is read against the company's own past years and against companies in the same industry. "
            + "Where an entry says a figure comes from the company's filing with NSE, Yahoo Finance's fiscal years are "
            + "used instead when the filings cannot be read, and the figures say which source they are from._";
    public static final String SETTINGS_NOTE = "These are the screens' settings, which you can change "
            + "(app.screening.presets in application.yml); they are not standards.";

    /** The groups the industry notes speak of, in this order, by the name a reader knows them by. */
    private static final Map<IndustryGroup, String> GROUP_NAMES = groupNames();

    /** "What is ROCE?", "what does P/E mean", "explain CAGR", "how do I read debt to equity?" */
    private static final Pattern ASKS_WHAT_A_TERM_IS = Pattern.compile(
            "^\\s*(?:please\\s+)?(?:can you\\s+)?(?:what\\s+(?:is|are|does|do)|what's|whats|explain|define|"
                    + "meaning\\s+of|what\\s+is\\s+meant\\s+by|how\\s+(?:is|are)|how\\s+(?:do|should|can)\\s+"
                    + "(?:i|you|we|one)|how\\s+to)\\s+(?:me\\s+)?(?:read|interpret|calculate|understand|use)?\\s*"
                    + "(?:the\\s+|an?\\s+)?(?:term\\s+|ratio\\s+|metric\\s+)?(.+?)"
                    + "(?:\\s+(?:mean|means|stand\\s+for|stands\\s+for|calculated|measured|read|interpreted))?"
                    + "\\s*[?.!]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** "a good ROE", "an ideal P/E": a question that asks for a judgement the glossary does not give. */
    private static final Pattern JUDGEMENT = Pattern.compile(
            "\\b(?:good|ideal|healthy|normal|typical|acceptable|reasonable|right|safe|high|low|bad)\\b\\s*",
            Pattern.CASE_INSENSITIVE);
    /** "ROCE for banks", "P/E in IT": the term with what it is asked about. */
    private static final Pattern QUALIFIER = Pattern.compile("\\s+(?:for|in|of|at)\\s+.*$", Pattern.CASE_INSENSITIVE);

    private final List<GlossaryEntry> entries;
    private final Map<GlossaryEntry, List<Pattern>> patterns = new LinkedHashMap<>();
    private final ScreeningPresets presets;

    @Autowired
    public MetricGlossary(ScreeningPresets presets) {
        this(GlossaryEntries.ALL, presets);
    }

    MetricGlossary(List<GlossaryEntry> entries, ScreeningPresets presets) {
        this.entries = List.copyOf(entries);
        this.presets = presets;
        Map<String, GlossaryEntry> keys = new LinkedHashMap<>();
        for (GlossaryEntry entry : this.entries) {
            if (keys.put(entry.key(), entry) != null) {
                throw new IllegalStateException("Two glossary entries have the key " + entry.key());
            }
        }
        for (GlossaryEntry entry : this.entries) {
            for (String other : entry.readWith()) {
                if (!keys.containsKey(other)) {
                    throw new IllegalStateException("Glossary entry %s is read with '%s', which is not an entry"
                            .formatted(entry.key(), other));
                }
            }
            List<Pattern> own = new ArrayList<>();
            entry.terms().forEach(term -> own.add(termPattern(term)));
            entry.metricKeys().forEach(key -> own.add(Pattern.compile("(?<![\\w])" + Pattern.quote(key) + "(?![\\w])")));
            patterns.put(entry, own);
        }
    }

    /** The question a screening metric answers, from the glossary's text; needs no glossary instance. */
    public static Optional<MetricQuestion> questionOf(ScreeningMetric metric) {
        return GlossaryEntries.ALL.stream().filter(entry -> entry.screeningMetrics().contains(metric))
                .map(GlossaryEntry::question).findFirst();
    }

    public List<GlossaryEntry> entries() {
        return entries;
    }

    public Optional<GlossaryEntry> byKey(String key) {
        return entries.stream().filter(entry -> entry.key().equals(key)).findFirst();
    }

    public Optional<GlossaryEntry> forScreeningMetric(ScreeningMetric metric) {
        return entries.stream().filter(entry -> entry.screeningMetrics().contains(metric)).findFirst();
    }

    public Optional<GlossaryEntry> forMetricKey(String metricKey) {
        return entries.stream().filter(entry -> entry.metricKeys().contains(metricKey)).findFirst();
    }

    /** What a report calls each metric, for a reader: "Revenue growth, year on year" for revenueGrowthYoyPercent. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("returnOnEquityPercent", "Return on equity"),
            Map.entry("returnOnAssetsPercent", "Return on assets"),
            Map.entry("returnOnCapitalEmployedPercent", "Return on capital employed"),
            Map.entry("operatingMarginPercent", "Operating margin"),
            Map.entry("netProfitMarginPercent", "Net profit margin"),
            Map.entry("netProfitMarginTtmPercent", "Net profit margin, trailing twelve months"),
            Map.entry("ebitda", "EBITDA, trailing twelve months"),
            Map.entry("revenueGrowthYoyPercent", "Revenue growth, year on year"),
            Map.entry("netProfitGrowthYoyPercent", "Net profit growth, year on year"),
            Map.entry("revenueCagrPercent", "Revenue CAGR"),
            Map.entry("netProfitCagrPercent", "Net profit CAGR"),
            Map.entry("ocfToPat3yMultiple", "Operating cash flow to net profit, 3 years"),
            Map.entry("ocfToPat5yMultiple", "Operating cash flow to net profit, 5 years"),
            Map.entry("freeCashFlow", "Free cash flow, trailing twelve months"),
            Map.entry("debtToEquityMultiple", "Debt to equity"),
            Map.entry("trailingPe", "P/E, trailing twelve months"),
            Map.entry("peOnFiledEps", "P/E on the latest filed EPS"),
            Map.entry("trailingEps", "EPS, trailing twelve months"),
            Map.entry("priceToBook", "Price to book"),
            Map.entry("earningsYieldPercent", "Earnings yield"),
            Map.entry("dividendYieldPercent", "Dividend yield"),
            Map.entry("priceCagrPercent", "Share-price return, yearly rate"),
            Map.entry("totalReturnPercent", "Share-price return, total"),
            Map.entry("maxDrawdownPercent", "Maximum drawdown"));

    /** A reader's name for one of the application's metrics, or the entry's term if it has none of its own. */
    public String label(String metricKey) {
        return LABELS.getOrDefault(metricKey, forMetricKey(metricKey).map(MetricGlossary::shortName).orElse(metricKey));
    }

    /** The entry a term names exactly ("ROCE", "return on equity", "price-to-book"), if the glossary has one. */
    public Optional<GlossaryEntry> find(String term) {
        if (term == null || term.isBlank()) {
            return Optional.empty();
        }
        String wanted = normalise(term);
        for (GlossaryEntry entry : entries) {
            if (normalise(entry.name()).equals(wanted) || normalise(entry.key()).equals(wanted)
                    || entry.terms().stream().anyMatch(t -> normalise(t).equals(wanted))) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** The entries whose terms or metric names appear in a text, by question and then in glossary order. */
    public List<GlossaryEntry> termsIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return ordered(entries.stream()
                .filter(entry -> patterns.get(entry).stream().anyMatch(pattern -> pattern.matcher(text).find()))
                .toList());
    }

    /** Entries in the order a reader meets them: by question, then as the glossary lists them. */
    public List<GlossaryEntry> ordered(Collection<GlossaryEntry> chosen) {
        return chosen.stream().distinct()
                .sorted(Comparator.comparing(GlossaryEntry::question).thenComparing(entries::indexOf))
                .toList();
    }

    // --- Asking what a term means ----------------------------------------------------------------

    /** What a question asks about. */
    public record Asked(String words, Optional<GlossaryEntry> entry, boolean asksForAJudgement) {
    }

    /**
     * The term a "what is X?" question asks about, if it is one: "what is a good ROE for banks?" asks about ROE,
     * and for a judgement. Empty when the message is not shaped as such a question.
     */
    public Optional<Asked> asked(String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher matcher = ASKS_WHAT_A_TERM_IS.matcher(message);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String words = matcher.group(1).strip();
        boolean judgement = JUDGEMENT.matcher(words).find();
        String term = JUDGEMENT.matcher(words).replaceAll("").strip();
        Optional<GlossaryEntry> entry = find(term);
        if (entry.isEmpty()) {
            entry = find(QUALIFIER.matcher(term).replaceAll(""));
        }
        return Optional.of(new Asked(words, entry, judgement));
    }

    /** The answer to "what is X?": the entry itself, or that the glossary does not have it. */
    public String answer(String message) {
        Optional<Asked> asked = asked(message);
        Optional<GlossaryEntry> entry = asked.flatMap(Asked::entry).or(() -> termsIn(message).stream().findFirst());
        if (entry.isEmpty()) {
            String words = asked.map(Asked::words).orElse(message == null ? "" : message.strip());
            return ("“%s” is not in the application's glossary, so it is not explained here: definitions come only "
                    + "from the glossary, never from the language model. The glossary explains: %s.")
                    .formatted(words, entries.stream().map(GlossaryEntry::name).collect(Collectors.joining(", ")));
        }
        StringBuilder md = new StringBuilder();
        if (asked.isPresent() && asked.get().asksForAJudgement()) {
            md.append("There is no single good or ideal %s: what is usual depends on the industry and the period. "
                    .formatted(shortName(entry.get())))
                    .append("Here is what it measures and how to read it; compare a company with its own past years "
                            + "and with companies in the same industry.\n\n");
        }
        md.append("_").append(entry.get().question().heading()).append("_\n\n");
        md.append(render(entry.get())).append("\n").append(SOURCE_NOTE).append("\n");
        return md.toString();
    }

    // --- Rendering -------------------------------------------------------------------------------

    /** The "Explain the terms" section for an answer: the entries given, grouped by question. */
    public String section(Collection<GlossaryEntry> chosen) {
        List<GlossaryEntry> ordered = ordered(chosen);
        if (ordered.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder("## Explain the terms\n\n").append(SOURCE_NOTE).append("\n\n");
        MetricQuestion current = null;
        for (GlossaryEntry entry : ordered) {
            if (entry.question() != current) {
                current = entry.question();
                md.append("### ").append(current.heading()).append("\n\n");
            }
            md.append(render(entry, false)).append("\n");
        }
        // each industry's reason once, rather than under every entry it applies to
        List<IndustryGroup> named = GROUP_NAMES.keySet().stream()
                .filter(group -> ordered.stream().anyMatch(entry -> notPrimaryFor(entry, group))).toList();
        if (!named.isEmpty()) {
            md.append("### Industry notes\n\n");
            named.forEach(group -> md.append("- **").append(capitalised(GROUP_NAMES.get(group))).append("**: ")
                    .append(group.note()).append("\n"));
        }
        return md.toString();
    }

    /**
     * A screen's criteria arranged by the question each answers, one column per set of companies they apply to,
     * so a reader sees what the screen asks of a company before the terms are explained.
     */
    public String criteriaByQuestion(ScreeningCriteria criteria) {
        Map<String, List<Rule>> scopes = new LinkedHashMap<>();
        if (!criteria.nonFinancialRules().isEmpty()) {
            scopes.put("Non-financial companies", criteria.nonFinancialRules());
        }
        Map<IndustryGroup, List<Rule>> byGroup = new EnumMap<>(IndustryGroup.class);
        byGroup.putAll(criteria.groupRules());
        byGroup.forEach((group, rules) -> {
            if (!rules.isEmpty()) {
                scopes.put(capitalised(GROUP_NAMES.getOrDefault(group, group.name())), rules);
            }
        });
        if (scopes.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder("**The criteria, by question**\n\n| Question | ")
                .append(String.join(" | ", scopes.keySet())).append(" |\n|---|")
                .append("---|".repeat(scopes.size())).append("\n");
        for (MetricQuestion question : MetricQuestion.values()) {
            List<String> cells = scopes.values().stream().map(rules -> rules.stream()
                    .filter(rule -> forScreeningMetric(rule.metric()).map(GlossaryEntry::question).orElse(null) == question)
                    .map(Rule::label).collect(Collectors.joining(", "))).toList();
            if (cells.stream().anyMatch(cell -> !cell.isEmpty())) {
                md.append("| ").append(question.heading()).append(" | ")
                        .append(cells.stream().map(cell -> cell.isEmpty() ? "-" : cell).collect(Collectors.joining(" | ")))
                        .append(" |\n");
            }
        }
        return md.toString();
    }

    /**
     * A short list of the terms an answer uses, one line each, for a chat answer that is not a report: the
     * full entry is a "what is X?" away.
     */
    public String termsUsed(Collection<GlossaryEntry> chosen) {
        List<GlossaryEntry> ordered = ordered(chosen);
        if (ordered.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder("**Terms used** _(from the application's glossary, not the language "
                + "model; ask “what is %s?” for how it is calculated and read)_\n".formatted(shortName(ordered.get(0))));
        ordered.forEach(entry -> md.append("- **").append(entry.name()).append("**: ").append(entry.meaning())
                .append("\n"));
        return md.toString();
    }

    /** One entry, in full, with each industry's own reason. */
    public String render(GlossaryEntry entry) {
        return render(entry, true);
    }

    /** @param industryReasons whether to give each industry's reason here, or only name the industries */
    private String render(GlossaryEntry entry, boolean industryReasons) {
        StringBuilder md = new StringBuilder("**").append(entry.name()).append("**: ").append(entry.meaning())
                .append("\n");
        md.append("- *How it is calculated here:* ").append(entry.calculation()).append("\n");
        md.append("- *How to read it:* ").append(entry.howToRead()).append("\n");
        if (!entry.readWith().isEmpty()) {
            md.append("- *Read it with:* ").append(entry.readWith().stream().map(key -> byKey(key).orElseThrow().name())
                    .collect(Collectors.joining("; "))).append("\n");
        }
        List<String> industry = industryReasons ? industryNotes(entry) : industryNames(entry);
        if (!industry.isEmpty()) {
            md.append("- *By industry:* ").append(String.join(" ", industry)).append("\n");
        }
        List<String> settings = screenSettings(entry);
        if (!settings.isEmpty()) {
            md.append("- *In this application's screens:* ").append(String.join(" ", settings)).append(" ")
                    .append(SETTINGS_NOTE).append("\n");
        }
        return md.toString();
    }

    /**
     * The industry groups a metric is not a primary measure for, each with the group's own reason, and those
     * that name it among their primary measures - all from {@link IndustryGroup}.
     */
    public List<String> industryNotes(GlossaryEntry entry) {
        List<String> notes = new ArrayList<>();
        GROUP_NAMES.forEach((group, name) -> {
            if (notPrimaryFor(entry, group)) {
                notes.add("Not a primary measure for %s: %s".formatted(name, group.note()));
            }
        });
        primaryFor(entry).ifPresent(notes::add);
        return notes;
    }

    /** The industry notes naming the groups only; their reasons are given once, at the end of a section. */
    private List<String> industryNames(GlossaryEntry entry) {
        List<String> notes = new ArrayList<>();
        List<String> notPrimary = GROUP_NAMES.entrySet().stream().filter(group -> notPrimaryFor(entry, group.getKey()))
                .map(Map.Entry::getValue).toList();
        if (!notPrimary.isEmpty()) {
            notes.add("Not a primary measure for %s (see the industry notes).".formatted(joined(notPrimary)));
        }
        primaryFor(entry).ifPresent(notes::add);
        return notes;
    }

    private static boolean notPrimaryFor(GlossaryEntry entry, IndustryGroup group) {
        return entry.sectorKeys().stream().anyMatch(group::isNonPrimary);
    }

    private static Optional<String> primaryFor(GlossaryEntry entry) {
        List<String> primary = GROUP_NAMES.entrySet().stream()
                .filter(group -> !notPrimaryFor(entry, group.getKey()) && entry.sectorTerms().stream()
                        .anyMatch(term -> group.getKey().primaryMetrics().stream().anyMatch(metric ->
                                metric.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)))))
                .map(Map.Entry::getValue).toList();
        return primary.isEmpty() ? Optional.empty()
                : Optional.of("Named as a primary measure for %s.".formatted(joined(primary)));
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * Each preset's thresholds on the entry's metrics, by the companies they apply to, read from the presets.
     * Companies with the same threshold are named together, and so are presets with the same settings.
     */
    public List<String> screenSettings(GlossaryEntry entry) {
        Map<String, List<String>> presetsByUse = new LinkedHashMap<>();
        for (String name : presets.names()) {
            ScreeningCriteria preset = presets.find(name).orElseThrow();
            Map<String, List<String>> scopesByLabel = new LinkedHashMap<>();
            String nonFinancial = labels(preset.nonFinancialRules(), entry);
            if (!nonFinancial.isEmpty()) {
                scopesByLabel.computeIfAbsent(nonFinancial, key -> new ArrayList<>()).add("non-financial companies");
            }
            Map<IndustryGroup, List<Rule>> byGroup = new EnumMap<>(IndustryGroup.class);
            byGroup.putAll(preset.groupRules());
            byGroup.forEach((group, rules) -> {
                String own = labels(rules, entry);
                if (!own.isEmpty()) {
                    scopesByLabel.computeIfAbsent(own, key -> new ArrayList<>())
                            .add(GROUP_NAMES.getOrDefault(group, group.name()));
                }
            });
            if (!scopesByLabel.isEmpty()) {
                String uses = joined(scopesByLabel.entrySet().stream()
                        .map(use -> use.getKey() + " for " + joined(use.getValue())).toList());
                presetsByUse.computeIfAbsent(uses, key -> new ArrayList<>()).add(name);
            }
        }
        return presetsByUse.entrySet().stream().map(use -> use.getValue().size() == 1
                ? "The %s screen uses %s.".formatted(use.getValue().get(0), use.getKey())
                : "The %s screens use %s.".formatted(joined(use.getValue()), use.getKey())).toList();
    }

    private static String labels(List<Rule> rules, GlossaryEntry entry) {
        return rules.stream().filter(rule -> entry.screeningMetrics().contains(rule.metric())).map(Rule::label)
                .collect(Collectors.joining(" and "));
    }

    /** The term a reader uses for it: "ROE", "operating margin". */
    static String shortName(GlossaryEntry entry) {
        return entry.terms().isEmpty() ? entry.name() : entry.terms().get(0);
    }

    private static String joined(List<String> parts) {
        if (parts.size() <= 1) {
            return String.join("", parts);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /**
     * A term in capitals ("ROE", "P/E", "FY") is matched in capitals only and may be followed by digits ("FY26");
     * any other term in any case, with any spacing or hyphen between its words.
     */
    private static Pattern termPattern(String term) {
        boolean capitals = term.equals(term.toUpperCase(Locale.ROOT)) && term.chars().anyMatch(Character::isLetter);
        if (capitals) {
            return Pattern.compile("(?<![\\p{L}\\d/])" + Pattern.quote(term) + "(?![\\p{L}/])");
        }
        String words = java.util.Arrays.stream(term.split("[\\s-]+")).map(Pattern::quote)
                .collect(Collectors.joining("[\\s-]+"));
        return Pattern.compile("(?<![\\p{L}\\d])" + words + "(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE);
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[“”\"'’()?.!,]", " ").replaceAll("\\bratio\\b", " ")
                .replaceAll("[\\s-]+", " ").strip();
    }

    private static Map<IndustryGroup, String> groupNames() {
        Map<IndustryGroup, String> names = new LinkedHashMap<>();
        names.put(IndustryGroup.BANK, "banks");
        names.put(IndustryGroup.NBFC, "NBFCs (non-bank lenders)");
        names.put(IndustryGroup.INSURANCE, "insurers");
        names.put(IndustryGroup.FINANCIAL_OTHER, "other financial companies (holding companies, asset managers, "
                + "exchanges and brokers)");
        names.put(IndustryGroup.IT_SERVICES, "IT services");
        names.put(IndustryGroup.PHARMA, "pharma");
        names.put(IndustryGroup.MANUFACTURING, "manufacturing");
        names.put(IndustryGroup.CONSUMER, "consumer companies");
        return names;
    }
}
