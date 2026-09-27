package com.ashish.stockresearch.research.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Where a single data point came from.
 *
 * @param source      provider name as it should appear in a report
 * @param sourceType  the kind of source
 * @param sourceField the provider field the raw value was read from, so a wrong number can be
 *                    traced back to exactly one upstream value
 * @param publishedAt when the underlying figure was published (e.g. the results announcement
 *                    date), or null when the source does not say - never assumed
 * @param asOf        the date the value describes: the period end for a filing figure, the
 *                    trading date for a market value
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceInfo(
        String source,
        SourceType sourceType,
        String sourceField,
        LocalDate publishedAt,
        LocalDate asOf
) {

    public SourceInfo {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required - never fabricate or omit it");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType is required");
        }
    }

    public SourceInfo withField(String field) {
        return new SourceInfo(source, sourceType, field, publishedAt, asOf);
    }

    public SourceInfo withDates(LocalDate published, LocalDate asOfDate) {
        return new SourceInfo(source, sourceType, sourceField, published, asOfDate);
    }
}
