package com.ashish.stockresearch.agent;

import java.util.List;

/**
 * A request's intent and the companies it names, as symbols or names. Also
 * the shape the routing model is asked to return.
 *
 * @param companies the companies named in the request, in order; empty when none is named
 */
public record RoutedRequest(Intent intent, List<String> companies) {

    public RoutedRequest {
        companies = companies == null ? List.of()
                : companies.stream().filter(c -> c != null && !c.isBlank()).map(String::strip).toList();
    }

    public static RoutedRequest specificQuestion() {
        return new RoutedRequest(Intent.SPECIFIC_QUESTION, List.of());
    }
}
