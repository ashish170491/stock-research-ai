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
