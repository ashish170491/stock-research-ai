package com.ashish.stockresearch.focus;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.glossary.GlossaryEntry;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.MetricQuestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Follow-up questions offered after an answer, chosen in Java from what the answer was about. */
public final class Suggestions {

    static final int MAX = 4;
    private static final Pattern ABBREVIATION = Pattern.compile("\\(([^)]+)\\)\\s*$");

    private Suggestions() {
    }

    public static List<Suggestion> after(Intent intent, List<String> companies, String answer, MetricGlossary glossary) {
        List<Suggestion> out = new ArrayList<>();
        String first = companies == null || companies.isEmpty() ? null : companies.get(0);
        switch (intent) {
            case FULL_RESEARCH, QUOTE -> {
                if (first != null) {
                    out.add(Suggestion.focus(first, Topic.RATING));
                    out.add(Suggestion.focus(first, Topic.PERFORMANCE));
                }
            }
            case COMPARE -> companies.stream().limit(2).forEach(company -> out.add(Suggestion.focus(company, Topic.RATING)));
            case SPECIFIC_QUESTION -> {
                if (first != null) {
                    out.add(Suggestion.focus(first, Topic.RATING));
                    out.add(Suggestion.focus(first, Topic.GROWING));
                    out.add(Suggestion.focus(first, Topic.PERFORMANCE));
                }
            }
            default -> {
            }
        }
        terms(out, answer, glossary, intent == Intent.EXPLAIN_TERM ? 1 : 0);
        return out.stream().limit(MAX).toList();
    }

    /** After a focused answer: the topics not yet chosen, the full report, and a term the answer used. */
    public static List<Suggestion> afterFocus(String company, List<Topic> answered, String answer,
                                              MetricGlossary glossary) {
        List<Suggestion> out = new ArrayList<>();
        for (Topic topic : Topic.values()) {
            if (!answered.contains(topic) && topic != Topic.FULL_REPORT && out.size() < 2) {
                out.add(Suggestion.focus(company, topic));
            }
        }
        if (!answered.contains(Topic.FULL_REPORT)) {
            out.add(Suggestion.focus(company, Topic.FULL_REPORT));
        }
        terms(out, answer, glossary, 0);
        return out.stream().limit(MAX).toList();
    }

    /** "What is ROE?" for metrics the answer uses, skipping the first {@code skip} (the term just explained). */
    private static void terms(List<Suggestion> out, String answer, MetricGlossary glossary, int skip) {
        if (answer == null || glossary == null) {
            return;
        }
        glossary.termsIn(answer).stream().filter(entry -> entry.question() != MetricQuestion.GENERAL).skip(skip)
                .limit(Math.max(0, MAX - out.size())).forEach(entry -> out.add(Suggestion.ask(whatIs(entry))));
    }

    static String whatIs(GlossaryEntry entry) {
        Matcher abbreviation = ABBREVIATION.matcher(entry.name());
        String term = abbreviation.find() ? abbreviation.group(1)
                : Character.toLowerCase(entry.name().charAt(0)) + entry.name().substring(1);
        return "What is " + term + "?";
    }
}
