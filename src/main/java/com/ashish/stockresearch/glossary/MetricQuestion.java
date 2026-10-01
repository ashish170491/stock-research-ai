package com.ashish.stockresearch.glossary;

/**
 * The questions a new investor asks of a company, which the metrics are grouped under so that a page of
 * ratios reads as answers to them. {@link #GENERAL} holds terms that are not metrics (TTM, fiscal year).
 */
public enum MetricQuestion {

    PROFITABLE("Is it profitable?"),
    GROWING("Is it growing?"),
    CASH("Are the profits real cash?"),
    STRETCHED("Is it financially stretched?"),
    PRICE("How much does the market charge for it?"),
    GENERAL("Terms used in the figures");

    private final String heading;

    MetricQuestion(String heading) {
        this.heading = heading;
    }

    public String heading() {
        return heading;
    }
}
