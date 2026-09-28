package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.ReportedValuation;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** Valuation figures shaped as the Yahoo provider reports them; Tech Mahindra's are Yahoo's, 2026-09-25. */
public final class ValuationFixtures {

    public static final LocalDate AS_OF = LocalDate.of(2026, 9, 25);
    private static final ReportingPeriod AT = ReportingPeriod.pointInTime(AS_OF);
    private static final SourceInfo SOURCE = new SourceInfo("Yahoo Finance", SourceType.MARKET_DATA_PROVIDER, null,
            null, AS_OF);

    private ValuationFixtures() {
    }

    /** Tech Mahindra at the close on 2026-09-25: every multiple agrees with its inputs. */
    public static ReportedValuation techm() {
        return valuation("1548.00", "57.90", "334.41", "51.00", "26.74", "4.63", "0.0329");
    }

    /**
     * @param dividendYieldFraction as Yahoo sends it, e.g. 0.0329 for 3.29%; null for none published
     * @param pe                    trailing P/E; null for none published
     */
    public static ReportedValuation valuation(String price, String eps, String book, String dividend, String pe,
                                              String pb, String dividendYieldFraction) {
        return new ReportedValuation("TECHM", "NSE", "Tech Mahindra Limited", AS_OF,
                perShare("sharePrice", price, "price.regularMarketPrice"),
                perShare("trailingEps", eps, "defaultKeyStatistics.trailingEps"),
                perShare("bookValuePerShare", book, "defaultKeyStatistics.bookValue"),
                perShare("dividendPerShare", dividend, "summaryDetail.dividendRate"),
                multiple("trailingPe", pe, "summaryDetail.trailingPE"),
                multiple("priceToBook", pb, "defaultKeyStatistics.priceToBook"),
                dividendYieldFraction == null
                        ? FinancialDataPoint.unavailable("dividendYieldPercent", Unit.PERCENT, AT,
                                SOURCE.withField("summaryDetail.dividendYield"), "Yahoo Finance published no dividend yield")
                        : FinancialDataPoint.reported("dividendYieldPercent", new BigDecimal(dividendYieldFraction),
                                ProviderUnit.FRACTION, new BigDecimal(dividendYieldFraction).movePointRight(2)
                                        .setScale(2, RoundingMode.HALF_UP), Unit.PERCENT, AT,
                                SOURCE.withField("summaryDetail.dividendYield")),
                new DataProvenance("Yahoo Finance", SourceType.MARKET_DATA_PROVIDER, DataFreshness.DELAYED,
                        Instant.parse("2026-09-27T06:00:00Z"), null, AS_OF, null, "Point-in-time valuation."));
    }

    private static FinancialDataPoint perShare(String metric, String value, String field) {
        if (value == null) {
            return FinancialDataPoint.unavailable(metric, Unit.INR_PER_SHARE, AT, SOURCE.withField(field),
                    "Yahoo Finance did not publish " + field);
        }
        return FinancialDataPoint.reported(metric, new BigDecimal(value), ProviderUnit.PER_SHARE, new BigDecimal(value),
                Unit.INR_PER_SHARE, AT, SOURCE.withField(field));
    }

    private static FinancialDataPoint multiple(String metric, String value, String field) {
        if (value == null) {
            return FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, AT, SOURCE.withField(field),
                    "Yahoo Finance did not publish " + field);
        }
        return FinancialDataPoint.reported(metric, new BigDecimal(value), ProviderUnit.MULTIPLE, new BigDecimal(value),
                Unit.MULTIPLE, AT, SOURCE.withField(field));
    }
}
