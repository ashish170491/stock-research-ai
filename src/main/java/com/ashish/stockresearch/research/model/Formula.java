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
    SELECTED_VALUE,
    /** 100 / value, e.g. earnings yield from a P/E. Inputs: the value. */
    RECIPROCAL_PERCENT,
    /**
     * sum(numerators) / sum(denominators), as a multiple, over the same years - e.g. operating cash flow
     * over net profit. Inputs: the numerators, then the denominators, the same number of each.
     */
    SUM_RATIO,
    /**
     * EBIT / capital employed x 100, capital employed being total assets minus current liabilities,
     * averaged over the opening and closing balance sheets when both are available.
     * Inputs: EBIT, closing total assets, closing current liabilities[, opening total assets,
     * opening current liabilities].
     */
    RETURN_ON_CAPITAL_EMPLOYED_PERCENT,
    /** The arithmetic mean of the inputs, e.g. three fiscal years' return on equity. Inputs: the values. */
    MEAN,
    /**
     * Dividends paid as a percentage of operating cash flow in the same fiscal year:
     * -(dividends paid) / operating cash flow x 100. Inputs: dividends paid as the cash-flow statement
     * reports them (an outflow, zero or negative), then operating cash flow (positive).
     */
    DIVIDENDS_TO_OPERATING_CASH_FLOW_PERCENT
}
