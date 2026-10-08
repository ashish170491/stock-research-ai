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

    /**
     * What a financial term or ratio means or how to read it - "what is ROCE?", "what is a good P/E?" - naming no
     * company. Answered from the application's glossary, with no model call.
     */
    EXPLAIN_TERM,

    /** Not about stocks at all. Answered without tools. */
    NOT_STOCK_RELATED,

    /**
     * A multi-step research goal - screen for candidates, then deep-dive or compare them, then a memo -
     * not answerable by a single screen, comparison or report. Names no single company. Answered by the
     * autonomous research orchestrator (roadmap Step 8), which pauses after screening for a human
     * checkpoint before continuing.
     */
    ORCHESTRATE
}
