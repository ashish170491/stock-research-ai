package com.ashish.stockresearch.research.report;

/**
 * An observation the application did not generate, and why. Listed in the
 * report so a missing conclusion is visible as withheld rather than silently
 * absent - and so the interpretation is told not to draw it either.
 *
 * @param subject what the observation would have been about, e.g. "Revenue growth (FY26 YoY)"
 * @param topic   the broader topic no interpretation may be drawn about, e.g. "revenue growth"
 * @param reason  DATA_CONFLICT, INVALID or SECTOR_CONTEXT
 * @param detail  the specific reason, taken from the data point's status or the sector context
 */
public record WithheldObservation(String subject, String topic, Reason reason, String detail) {

    public enum Reason {
        /** An input is in a DATA_CONFLICT, so the metric was not calculated. */
        DATA_CONFLICT,
        /** The calculated value failed verification against its inputs. */
        INVALID,
        /** The metric is not a primary indicator for the company's industry group. */
        SECTOR_CONTEXT
    }
}
