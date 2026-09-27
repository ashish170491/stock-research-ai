package com.ashish.stockresearch.research.model;

/**
 * The arithmetic behind a CALCULATED data point, named so the result can be
 * recomputed independently from its recorded inputs. Each constant documents
 * the order of the {@link CalculationInput}s it expects.
 */
public enum Formula {

    /** (current / previous - 1) x 100. Inputs: previous, current. */
    GROWTH_PERCENT,

    /** ((end / start)^(1 / years) - 1) x 100. Inputs: start, end, years. */
    CAGR_PERCENT,

    /** numerator / denominator x 100. Inputs: numerator, denominator. */
    RATIO_PERCENT,

    /**
     * numerator / average(opening, closing) x 100, or numerator / closing x 100 when no opening balance
     * is available. Inputs: numerator, closing[, opening].
     */
    AVERAGE_BALANCE_RETURN_PERCENT,

    /** numerator / denominator, as a multiple (x). Inputs: numerator, denominator. */
    MULTIPLE,

    /** (trough / peak - 1) x 100, the largest fall from a running peak. Inputs: peak, trough. */
    DRAWDOWN_PERCENT,

    /** days / 365.25. Inputs: days. */
    DAYS_TO_YEARS,

    /** A value selected from a series (e.g. the highest close). Inputs: the selected value. */
    SELECTED_VALUE
}
