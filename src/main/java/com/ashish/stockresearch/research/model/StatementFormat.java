package com.ashish.stockresearch.research.model;

/**
 * The statement format a company files its results in. Companies, NBFCs among them, file under Ind-AS
 * Schedule III; banks under the banking format, whose lines differ: interest earned rather than revenue
 * from operations, capital and reserves rather than equity, deposits beside borrowings.
 */
public enum StatementFormat {
    COMPANY,
    BANK
}
