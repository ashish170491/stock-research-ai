package com.ashish.stockresearch.screening;

import java.util.List;

/**
 * Stage 1 of a screening request in words: the screening criteria as the extraction model read them,
 * before anything checks them. Every field is a plain string on purpose - a metric the application
 * does not know, or a threshold written as "18%", must reach {@link CriteriaValidator} and be rejected
 * there with a reason the user is shown, not fail JSON binding and disappear.
 *
 * @param preset         a preset the request names ("quality-compounder"), or null
 * @param industryGroups the kinds of company the request limits itself to (IT_SERVICES, BANK, ...)
 * @param criteria       conditions the request states with a number
 * @param vagueTerms     qualities the request asks for without a number ("low debt", "profitable")
 */
public record ExtractedCriteria(String preset, List<String> industryGroups, List<Criterion> criteria,
                                List<String> vagueTerms) {

    public ExtractedCriteria {
        industryGroups = industryGroups == null ? List.of() : industryGroups.stream()
                .filter(group -> group != null && !group.isBlank()).toList();
        criteria = criteria == null ? List.of() : criteria.stream().filter(java.util.Objects::nonNull).toList();
        vagueTerms = vagueTerms == null ? List.of() : vagueTerms.stream()
                .filter(term -> term != null && !term.isBlank()).toList();
    }

    /** Nothing extracted: what stage 2 starts from when the extraction model gave no usable answer. */
    public static ExtractedCriteria none() {
        return new ExtractedCriteria(null, List.of(), List.of(), List.of());
    }

    /**
     * One condition as the model read it.
     *
     * @param phrase    the user's own words for it ("ROE above 18%")
     * @param metric    a {@link ScreeningMetric} name
     * @param operator  an {@link Operator} name
     * @param threshold the number the user wrote, in the metric's unit
     */
    public record Criterion(String phrase, String metric, String operator, String threshold) {
    }
}
