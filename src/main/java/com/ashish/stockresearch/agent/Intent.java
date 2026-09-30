package com.ashish.stockresearch.agent;

/** What a chat request is asking for, which decides how it is answered. */
public enum Intent {

    /** A broad view of one company - "fundamental overview of X", "analyse X". Answered with the verified report. */
    FULL_RESEARCH,

    /** The current share price of one or more companies. */
    QUOTE,

    /** Two or more companies side by side. */
    COMPARE,

    /**
     * Find, screen or shortlist many stocks against criteria - "find profitable IT companies with low debt".
     * Names no single company. Answered by {@link ScreenerAgent}'s chain.
     */
    SCREEN,

    /** A narrower question about a company or stock, answered by the model with the research tools. */
    SPECIFIC_QUESTION,

    /** Not about stocks at all. Answered without tools. */
    NOT_STOCK_RELATED
}
