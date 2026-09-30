package com.ashish.stockresearch.research.model;

/**
 * An item of a company's statutory financial results, as filed with the exchange: one line of the
 * statement of profit and loss, the balance sheet or the cash-flow statement, for the fiscal year.
 * Each is reported exactly as filed; nothing here is calculated (EBIT, debt and growth are calculated
 * from these by the service layer).
 *
 * <p>Banks file a different statement (Schedule III for banks): no "revenue from operations", equity
 * as capital plus reserves, deposits separately from borrowings. The items a bank does not file are
 * UNAVAILABLE for it, never approximated from other lines.
 */
public enum FiledItem {

    REVENUE_FROM_OPERATIONS(Kind.DURATION, Unit.INR_CRORE, "revenue from operations"),
    /** A bank's interest earned; its closest line to revenue, never presented as revenue from operations. */
    INTEREST_EARNED(Kind.DURATION, Unit.INR_CRORE, "interest earned"),
    PROFIT_BEFORE_TAX(Kind.DURATION, Unit.INR_CRORE, "profit before tax"),
    FINANCE_COSTS(Kind.DURATION, Unit.INR_CRORE, "finance costs"),
    /** Profit for the period, before it is split between the parent's owners and minority holders. */
    PROFIT_FOR_PERIOD(Kind.DURATION, Unit.INR_CRORE, "profit for the period"),
    PROFIT_ATTRIBUTABLE_TO_OWNERS(Kind.DURATION, Unit.INR_CRORE, "profit attributable to owners of the parent"),
    PROFIT_ATTRIBUTABLE_TO_NON_CONTROLLING_INTERESTS(Kind.DURATION, Unit.INR_CRORE,
            "profit attributable to non-controlling interests"),
    BASIC_EPS(Kind.DURATION, Unit.INR_PER_SHARE, "basic earnings per share"),
    OPERATING_CASH_FLOW(Kind.DURATION, Unit.INR_CRORE, "net cash from operating activities"),
    /** Dividends paid in cash, as the cash-flow statement's financing activities report them. */
    DIVIDENDS_PAID(Kind.DURATION, Unit.INR_CRORE, "dividends paid"),

    EQUITY_ATTRIBUTABLE_TO_OWNERS(Kind.INSTANT, Unit.INR_CRORE, "equity attributable to owners of the parent"),
    NON_CONTROLLING_INTEREST(Kind.INSTANT, Unit.INR_CRORE, "non-controlling interest"),
    TOTAL_EQUITY(Kind.INSTANT, Unit.INR_CRORE, "total equity"),
    /** A bank's share capital. */
    SHARE_CAPITAL(Kind.INSTANT, Unit.INR_CRORE, "capital"),
    /** A bank's reserves and surplus; with its capital, its shareholders' equity. */
    RESERVES_AND_SURPLUS(Kind.INSTANT, Unit.INR_CRORE, "reserves and surplus"),
    TOTAL_ASSETS(Kind.INSTANT, Unit.INR_CRORE, "total assets"),
    /** Total equity and liabilities: must equal total assets. */
    TOTAL_EQUITY_AND_LIABILITIES(Kind.INSTANT, Unit.INR_CRORE, "total equity and liabilities"),
    CURRENT_LIABILITIES(Kind.INSTANT, Unit.INR_CRORE, "total current liabilities"),
    BORROWINGS_NON_CURRENT(Kind.INSTANT, Unit.INR_CRORE, "non-current borrowings"),
    BORROWINGS_CURRENT(Kind.INSTANT, Unit.INR_CRORE, "current borrowings"),
    /** A bank's borrowings, one line. */
    BORROWINGS(Kind.INSTANT, Unit.INR_CRORE, "borrowings"),
    /** A bank's deposits: its raw material, never debt. */
    DEPOSITS(Kind.INSTANT, Unit.INR_CRORE, "deposits");

    /** Whether the item covers the fiscal year (a flow) or is at its end (a balance). */
    public enum Kind { DURATION, INSTANT }

    private final Kind kind;
    private final Unit unit;
    private final String label;

    FiledItem(Kind kind, Unit unit, String label) {
        this.kind = kind;
        this.unit = unit;
        this.label = label;
    }

    public Kind kind() {
        return kind;
    }

    public Unit unit() {
        return unit;
    }

    /** The line as the filing names it. */
    public String label() {
        return label;
    }
}
