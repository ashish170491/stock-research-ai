package com.ashish.stockresearch.research.model;

/**
 * Something the report could not establish, stated explicitly so it is
 * reported as unknown rather than silently dropped.
 *
 * @param area   report section, e.g. "Shareholding"
 * @param item   what is missing, e.g. "returnOnCapitalEmployed"
 * @param reason why - taken from the provider or validator, never guessed
 */
public record DataGap(String area, String item, String reason) {
}
