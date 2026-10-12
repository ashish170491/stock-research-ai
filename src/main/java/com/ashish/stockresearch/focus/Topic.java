package com.ashish.stockresearch.focus;

import com.ashish.stockresearch.glossary.MetricQuestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One thing a beginner can choose to be told about a company, instead of being handed everything at once. */
public enum Topic {

    RATING("Overall rating: Buy, Hold or Sell", "The rating from fixed rules, and why", null,
            "Is %s a good company to invest in?"),
    PROFITABLE(MetricQuestion.PROFITABLE.heading(), "Return on equity and on capital", MetricQuestion.PROFITABLE,
            "Is %s profitable?"),
    GROWING(MetricQuestion.GROWING.heading(), "Sales and profit growth over 5 years", MetricQuestion.GROWING,
            "Is %s growing?"),
    CASH(MetricQuestion.CASH.heading(), "Whether reported profit turns into cash", MetricQuestion.CASH,
            "Are %s's profits real cash?"),
    STRETCHED("Does it carry a lot of debt?", "Debt compared with shareholders' money", MetricQuestion.STRETCHED,
            "How much debt does %s have?"),
    PRICE(MetricQuestion.PRICE.heading(), "Price compared with earnings or book value", MetricQuestion.PRICE,
            "How much does the market charge for %s?"),
    MOAT("Does it have a lasting edge (moat)?", "Signs in its figures, what the market charges, what it says",
            null, "Does %s have a lasting edge over its rivals?"),
    PERFORMANCE("How has the share price done?", "Returns and the biggest fall over 5 years", null,
            "How has %s's share price done?"),
    FULL_REPORT("Everything: the full research report", "Long: every figure, risk and source", null,
            "Show me the full report on %s");

    private final String label;
    private final String hint;
    private final MetricQuestion question;
    private final String prompt;

    Topic(String label, String hint, MetricQuestion question, String prompt) {
        this.label = label;
        this.hint = hint;
        this.question = question;
        this.prompt = prompt;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    /** The glossary question this topic answers, or null for one that is not a group of metrics. */
    public MetricQuestion question() {
        return question;
    }

    /** The topic as a question a user could type about a company, e.g. "Is TCS growing?". */
    public String prompt(String company) {
        return prompt.formatted(company);
    }

    private static final java.util.Map<Topic, java.util.regex.Pattern> WORDS = new java.util.EnumMap<>(Topic.class);

    static {
        int flags = java.util.regex.Pattern.CASE_INSENSITIVE;
        WORDS.put(RATING, java.util.regex.Pattern.compile("\\b(?:rating|rated|buy|sell|hold)\\b", flags));
        WORDS.put(PROFITABLE, java.util.regex.Pattern.compile(
                "\\b(?:profitab\\w*|roe|roce|return on (?:equity|capital)|margins?)\\b", flags));
        WORDS.put(GROWING, java.util.regex.Pattern.compile("\\b(?:grow\\w*|cagr|sales|revenue)\\b", flags));
        WORDS.put(CASH, java.util.regex.Pattern.compile("\\bcash\\b", flags));
        WORDS.put(STRETCHED, java.util.regex.Pattern.compile("\\b(?:debt|borrow\\w*|leverag\\w*|stretched|loans?)\\b", flags));
        WORDS.put(PRICE, java.util.regex.Pattern.compile(
                "\\b(?:valuation|valued|p\\s*/\\s*e|expensive|cheap|market charge|earnings yield|price to)\\b", flags));
        WORDS.put(MOAT, java.util.regex.Pattern.compile("\\b(?:moat|edge|competitive advantage|premium|pricing power)\\b",
                flags));
        WORDS.put(PERFORMANCE, java.util.regex.Pattern.compile(
                "\\b(?:share price|stock price|price history|returns?|perform\\w*|drawdown)\\b", flags));
        WORDS.put(FULL_REPORT, java.util.regex.Pattern.compile(
                "\\b(?:everything|full report|full research|whole report|all of (?:it|them))\\b", flags));
    }

    /** How a question asks about a topic in its own words: "are valuations expensive?", "how much debt?". */
    private static final java.util.Map<Topic, java.util.regex.Pattern> ASKED = new java.util.EnumMap<>(Topic.class);
    /** A question about a particular period or a comparison wants more than the topic's summary. */
    private static final java.util.regex.Pattern NARROWER = java.util.regex.Pattern.compile(
            "\\b(?:fy\\s?\\d{2,4}|20\\d\\d|quarter\\w*|q[1-4]|ttm|last year|this year|month\\w*|compare\\w*|vs\\.?|versus|than)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    static {
        int flags = java.util.regex.Pattern.CASE_INSENSITIVE;
        ASKED.put(PRICE, java.util.regex.Pattern.compile("\\b(?:expensive|cheap|over-?valued|under-?valued|fairly valued"
                + "|valuations?|over-?priced|under-?priced|p\\s*/\\s*e|pe ratio|price to (?:earnings|book))\\b", flags));
        ASKED.put(STRETCHED, java.util.regex.Pattern.compile(
                "\\b(?:debt|borrowings?|leverage[d]?|financially stretched)\\b", flags));
        ASKED.put(CASH, java.util.regex.Pattern.compile("\\b(?:cash flows?|real cash|cash conversion)\\b", flags));
        ASKED.put(PROFITABLE, java.util.regex.Pattern.compile("\\b(?:profitable|profitability|roe|roce)\\b", flags));
        ASKED.put(GROWING, java.util.regex.Pattern.compile("\\b(?:growing|growth)\\b", flags));
        ASKED.put(MOAT, java.util.regex.Pattern.compile("\\b(?:moats?|economic moat|competitive (?:advantage|edge)s?"
                + "|lasting edge|durable advantage|pricing power|edge over (?:its )?(?:rivals|competitors|peers)"
                + "|(?:pay|paying|charge|charging|command|commands|trades? at|trading at)\\s+(?:a\\s+)?premium"
                + "|premium valuation|valuation premium)\\b", flags));
        ASKED.put(PERFORMANCE, java.util.regex.Pattern.compile(
                "\\b(?:performed|performance|how has (?:the |its )?(?:share|stock) price|(?:share|stock) price history)\\b",
                flags));
    }

    /**
     * The topics a question asks about in its own words, when it asks about them in general ("is the debt
     * high?"); empty for one about a particular period or a comparison ("FY24 debt", "debt compared with TCS"),
     * which the research tools answer.
     */
    public static List<Topic> askedAbout(String question) {
        List<Topic> topics = new ArrayList<>();
        if (question == null || question.isBlank() || NARROWER.matcher(question).find()) {
            return topics;
        }
        for (Topic topic : values()) {
            java.util.regex.Pattern asked = ASKED.get(topic);
            if (asked != null && asked.matcher(question).find()) {
                topics.add(topic);
            }
        }
        return topics;
    }

    /**
     * The topics a typed reply picks, in this enum's order: the options whose label it contains (a reply that
     * repeats the choices as they were shown), else those its words name ("rating and debt"). Empty when it
     * names none.
     */
    public static List<Topic> matching(String text) {
        List<Topic> topics = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return topics;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Topic topic : values()) {
            if (lower.contains(topic.label.toLowerCase(Locale.ROOT))) {
                topics.add(topic);
            }
        }
        if (!topics.isEmpty()) {
            return topics;
        }
        for (Topic topic : values()) {
            if (WORDS.get(topic).matcher(text).find()) {
                topics.add(topic);
            }
        }
        return topics;
    }

    /** The topics named by these ids, in this enum's order; an unknown id is ignored. */
    public static List<Topic> parse(List<String> ids) {
        List<Topic> topics = new ArrayList<>();
        if (ids == null) {
            return topics;
        }
        for (Topic topic : values()) {
            if (ids.stream().anyMatch(id -> id != null && id.strip().equalsIgnoreCase(topic.id()))) {
                topics.add(topic);
            }
        }
        return topics;
    }
}
