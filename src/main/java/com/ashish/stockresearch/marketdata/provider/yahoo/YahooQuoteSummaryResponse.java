package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.List;

/**
 * Minimal shape of Yahoo Finance's {@code /v10/finance/quoteSummary}
 * response. Only the modules this application actually reads are mapped;
 * everything else is ignored.
 *
 * Numeric fields arrive wrapped as {@code {"raw": ..., "fmt": ...}} and are
 * flattened by {@link RawValueDeserializer}. Fields Yahoo does not publish
 * for a given company come back null - which the providers must pass
 * through as null rather than defaulting to zero.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record YahooQuoteSummaryResponse(QuoteSummary quoteSummary) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuoteSummary(List<Result> result, SummaryError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SummaryError(String code, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(
            AssetProfile assetProfile,
            FinancialData financialData,
            DefaultKeyStatistics defaultKeyStatistics,
            Price price
    ) {
    }

    /** Descriptive company data. Unusually for Yahoo, these fields are plain values, not raw/fmt objects. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AssetProfile(
            String sector,
            String industry,
            String country,
            String city,
            String website,
            Integer fullTimeEmployees,
            String longBusinessSummary
    ) {
    }

    /**
     * Trailing-twelve-month and most-recent-quarter financials.
     *
     * Margins and returns are fractions here (0.2396 = 23.96%) and are
     * converted to percentages by the provider.
     *
     * {@code financialCurrency} is the currency of the accounts and is NOT
     * always the currency of the Indian listing - it must be read, never
     * assumed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinancialData(
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal totalRevenue,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal ebitda,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal totalCash,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal totalDebt,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal debtToEquity,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal freeCashflow,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal operatingMargins,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal profitMargins,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal returnOnEquity,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal returnOnAssets,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal revenueGrowth,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal earningsGrowth,
            String financialCurrency
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DefaultKeyStatistics(
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal netIncomeToCommon,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal mostRecentQuarter,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal lastFiscalYearEnd
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Price(
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal marketCap,
            String longName,
            String shortName,
            String currency,
            String exchangeName
    ) {
    }
}
