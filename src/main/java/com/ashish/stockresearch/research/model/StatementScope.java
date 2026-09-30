package com.ashish.stockresearch.research.model;

/** Whose accounts a filed statement is: the group (parent and subsidiaries) or the parent company alone. */
public enum StatementScope {

    /** The group: what the Yahoo figures and most analysis describe, and the scope used whenever it is filed. */
    CONSOLIDATED,

    /** The listed company alone. Used only for a company that files no consolidated results at all. */
    STANDALONE
}
