package com.ashish.stockresearch.verdict;

/** The only rating the application gives, produced by {@link VerdictEngine} and never by a model. */
public enum Rating {
    BUY("Buy"),
    HOLD("Hold"),
    SELL("Sell or avoid"),
    NO_RATING("No rating");

    private final String label;

    Rating(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
