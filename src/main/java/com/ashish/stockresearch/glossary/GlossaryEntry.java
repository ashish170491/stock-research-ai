package com.ashish.stockresearch.glossary;

import com.ashish.stockresearch.screening.ScreeningMetric;

import java.util.List;
import java.util.Set;

/**
 * One term, explained once, in fixed text that a person has reviewed. It never states a benchmark or an
 * ideal value, and never calls a value good or bad: what is usual depends on the industry and the period,
 * which the application shows from data instead.
 *
 * @param key              a stable identifier, used by {@code readWith}
 * @param name             the term as a heading, e.g. "Return on equity (ROE)"
 * @param terms            the words that name it in a question or an answer; an all-capital term (ROE, P/E)
 *                         is matched in capitals only
 * @param question         the question it answers
 * @param metricKeys       the application's metric names it explains, as reports show them
 * @param screeningMetrics the screening metrics it explains
 * @param sectorKeys       the metric names {@code IndustryGroup} marks as primary or not for each group
 * @param sectorTerms      the words {@code IndustryGroup} uses for it in a group's primary metrics
 * @param meaning          what it measures, in plain words
 * @param calculation      how this application calculates it, from which source
 * @param howToRead        what raises or lowers it, and what distorts it
 * @param readWith         the keys of the entries to read it with
 */
public record GlossaryEntry(
        String key,
        String name,
        List<String> terms,
        MetricQuestion question,
        Set<String> metricKeys,
        Set<ScreeningMetric> screeningMetrics,
        Set<String> sectorKeys,
        List<String> sectorTerms,
        String meaning,
        String calculation,
        String howToRead,
        List<String> readWith
) {

    public GlossaryEntry {
        terms = List.copyOf(terms);
        metricKeys = Set.copyOf(metricKeys);
        screeningMetrics = Set.copyOf(screeningMetrics);
        sectorKeys = Set.copyOf(sectorKeys);
        sectorTerms = List.copyOf(sectorTerms);
        readWith = List.copyOf(readWith);
        if (key == null || name == null || question == null || meaning == null || calculation == null
                || howToRead == null) {
            throw new IllegalArgumentException("a glossary entry needs a key, name, question, meaning, calculation "
                    + "and a note on how to read it");
        }
    }

    /** The entry's own fixed text, which must hold no benchmark or judgement. */
    public List<String> fixedText() {
        return List.of(name, meaning, calculation, howToRead);
    }
}
