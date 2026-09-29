package com.ashish.stockresearch.screening;

import java.math.BigDecimal;

/** How a rule compares a metric with its threshold. */
public enum Operator {

    AT_LEAST("≥"),
    AT_MOST("≤"),
    ABOVE(">"),
    BELOW("<");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public boolean holds(BigDecimal value, BigDecimal threshold) {
        int comparison = value.compareTo(threshold);
        return switch (this) {
            case AT_LEAST -> comparison >= 0;
            case AT_MOST -> comparison <= 0;
            case ABOVE -> comparison > 0;
            case BELOW -> comparison < 0;
        };
    }
}
