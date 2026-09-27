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
 * Numeric fields arrive wrapped as {@code {"raw": ..., "fmt": ...}}. Financial
 * values are kept as {@link YahooValue} (raw plus Yahoo's formatted string, which
 * is how their meaning is verified); dates are flattened by
 * {@link RawValueDeserializer}. Fields Yahoo does not publish
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
            Price price,
            Earnings earnings
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
     * Whether a ratio is a fraction or already a percentage is not assumed
     * from the field name; {@link YahooFieldSemantics} verifies it per value.
     *
     * {@code financialCurrency} is the currency of the accounts and is NOT
     * always the currency of the Indian listing - it must be read, never
     * assumed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinancialData(
            YahooValue totalRevenue,
            YahooValue ebitda,
            YahooValue totalCash,
            YahooValue totalDebt,
            YahooValue debtToEquity,
            YahooValue freeCashflow,
            YahooValue operatingMargins,
            YahooValue profitMargins,
            YahooValue returnOnEquity,
            YahooValue returnOnAssets,
            YahooValue revenueGrowth,
            YahooValue earningsGrowth,
            String financialCurrency
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DefaultKeyStatistics(
            YahooValue netIncomeToCommon,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal mostRecentQuarter,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal lastFiscalYearEnd,
            YahooValue sharesOutstanding
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Price(
            YahooValue marketCap,
            YahooValue regularMarketPrice,
            // Epoch seconds of the last trade - the as-of instant for market cap.
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal regularMarketTime,
            String longName,
            String shortName,
            String currency,
            String exchangeName
    ) {
    }

    /**
     * Quarterly results. {@code earningsChart} dates each quarter (period end,
     * results announcement) and gives its fiscal label; {@code financialsChart}
     * carries the same quarters' revenue and earnings keyed by calendar
     * quarter ("2Q2026"). The two are joined on that key.
     *
     * {@code financialCurrency} here is the currency of these charts, which
     * is not always the currency of {@code financialData}: Yahoo can report a
     * company's headline figures in one currency and its quarterly charts in
     * another.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Earnings(EarningsChart earningsChart, FinancialsChart financialsChart, String financialCurrency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EarningsChart(List<EarningsQuarter> quarterly) {
    }

    /** @param actual reported EPS for the quarter */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EarningsQuarter(
            YahooValue actual,
            String fiscalQuarter,
            String calendarQuarter,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal periodEndDate,
            @JsonDeserialize(using = RawValueDeserializer.class) BigDecimal reportedDate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinancialsChart(List<FinancialsQuarter> quarterly, List<FinancialsYear> yearly) {
    }

    /** @param date the calendar year in which the fiscal year ends, e.g. 2026 for FY26 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinancialsYear(
            Integer date,
            YahooValue revenue,
            YahooValue earnings
    ) {
    }

    /** @param date calendar-quarter key, e.g. "2Q2026" */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FinancialsQuarter(
            String date,
            YahooValue revenue,
            YahooValue earnings
    ) {
    }
}
