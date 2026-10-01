package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.AnnualCrossCheckFigures;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.FiledFinancials;
import com.ashish.stockresearch.research.model.FiledPerShare;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Makes a company's filed results the source of its fiscal-year figures, and the market-data provider's
 * the cross-check:
 *
 * <ul>
 *   <li><b>Fiscal years</b> come from the company's filings with NSE ({@link FiledFinancialsProvider}), one
 *       scope throughout, each year checked against itself. A year the filings do not supply is a gap; it is
 *       never filled from another source, so no calculation spans two sources.</li>
 *   <li><b>The cross-check</b> is the provider's net profit for the same year, which measures the same thing
 *       (profit attributable to the parent's owners). A year on which they disagree is a DATA_CONFLICT for that
 *       year ({@link FinancialMetricsService}). Revenue is not compared: the provider's revenue is defined
 *       differently (it may include other operating income or exclude excise duty), so a gap between the two
 *       says nothing about either.</li>
 *   <li><b>When there are no filings</b> - a company not listed on NSE, NSE unreachable, no filings source
 *       configured - the provider's own fiscal-year figures stand, cross-checked between its two feeds as
 *       before, and a gap says so.</li>
 * </ul>
 *
 * The headline (trailing-twelve-month) figures and the quarters stay the provider's.
 */
@Service
public class FiledAnnualHistoryService {

    private static final Logger log = LoggerFactory.getLogger(FiledAnnualHistoryService.class);
    /** Six fiscal years, so that a five-year span has both its ends. */
    static final int FISCAL_YEARS = 6;
    static final String AREA = "Financials (fiscal years)";

    private final ObjectProvider<FiledFinancialsProvider> filings;

    public FiledAnnualHistoryService(ObjectProvider<FiledFinancialsProvider> filings) {
        this.filings = filings;
    }

    public ReportedFinancials withFiledAnnualHistory(ReportedFinancials provider) {
        FiledFinancialsProvider source = filings.getIfAvailable();
        if (source == null) {
            return provider;
        }
        if (provider.exchange() == null || !"NSE".equalsIgnoreCase(provider.exchange().strip())) {
            return providerFigures(provider, "%s is not listed on NSE (%s), so its NSE filings are not read"
                    .formatted(provider.symbol(), provider.exchange() == null ? "exchange not stated" : provider.exchange()));
        }
        FiledFinancials filed;
        try {
            filed = source.getFiledFinancials(provider.symbol(), FISCAL_YEARS);
        } catch (ResearchDataUnavailableException ex) {
            log.warn("NSE filings for {} could not be used [{}]: {}", provider.symbol(), ex.status(), ex.getMessage());
            return providerFigures(provider, "the NSE filings could not be used (%s)".formatted(ex.getMessage()));
        }
        if (filed.years().isEmpty()) {
            ReportedFinancials fallback = providerFigures(provider, "no fiscal year could be read from the NSE filings");
            List<DataGap> gaps = new ArrayList<>(fallback.dataGaps());
            gaps.addAll(filed.dataGaps());
            return replace(fallback, fallback.annualHistory(), fallback.annualCrossCheckFigures(), gaps);
        }

        List<AnnualFinancials> annual = filed.years().stream().map(FiledAnnualFinancials::from).toList();
        List<AnnualCrossCheckFigures> crossCheck = provider.annualHistory().stream()
                .map(year -> new AnnualCrossCheckFigures(year.period(), notCompared(year.revenue(), provider),
                        year.netProfit()))
                .toList();
        List<DataGap> gaps = new ArrayList<>(provider.dataGaps().stream()
                .filter(gap -> !isProviderAnnualGap(gap)).toList());
        filed.dataGaps().forEach(gap -> gaps.add(new DataGap(AREA, gap.item(), gap.reason())));
        gaps.addAll(uncheckedYears(annual, provider));
        return replace(provider, annual, crossCheck, gaps);
    }

    /**
     * The per-share figures of the latest fiscal year NSE lists results for. If that year's filing could not
     * be read there are none: an earlier year's EPS is never presented as the latest.
     */
    public FiledPerShare latestFiledPerShare(String symbol, String exchange) {
        FiledFinancialsProvider source = filings.getIfAvailable();
        if (source == null) {
            return FiledPerShare.unavailable("no source of filed results is configured");
        }
        if (exchange == null || !"NSE".equalsIgnoreCase(exchange.strip())) {
            return FiledPerShare.unavailable("%s is not listed on NSE, so its NSE filings are not read".formatted(symbol));
        }
        try {
            FiledFinancials latest = source.getFiledFinancials(symbol, 1);
            if (latest.years().isEmpty()) {
                return FiledPerShare.unavailable(latest.dataGaps().isEmpty()
                        ? "no fiscal year could be read from the NSE filings"
                        : "%s: %s".formatted(latest.dataGaps().get(0).item(), latest.dataGaps().get(0).reason()));
            }
            return FiledPerShare.of(latest.years().get(latest.years().size() - 1));
        } catch (ResearchDataUnavailableException ex) {
            return FiledPerShare.unavailable("the NSE filings could not be used (%s)".formatted(ex.getMessage()));
        }
    }

    /** The provider's own fiscal-year figures, with the reason the filings are not their source. */
    private static ReportedFinancials providerFigures(ReportedFinancials provider, String why) {
        List<DataGap> gaps = new ArrayList<>(provider.dataGaps());
        gaps.add(new DataGap(AREA, "source", "The fiscal-year figures are %s's, not the company's filings: %s"
                .formatted(provider.provenance().source(), why)));
        return replace(provider, provider.annualHistory(), provider.annualCrossCheckFigures(), gaps);
    }

    /** The provider's revenue, kept visible but marked as not compared. */
    private static FinancialDataPoint notCompared(FinancialDataPoint revenue, ReportedFinancials provider) {
        return FinancialDataPoint.unavailable(revenue.metric(), revenue.unit(), revenue.period(), revenue.source(),
                ("not compared with the filed revenue from operations: %s defines revenue differently (it may "
                        + "include other operating income or exclude excise duty)").formatted(provider.provenance().source()));
    }

    /** Filed years whose net profit the provider's could not be compared with, and why. */
    private static List<DataGap> uncheckedYears(List<AnnualFinancials> annual, ReportedFinancials provider) {
        List<DataGap> gaps = new ArrayList<>();
        for (AnnualFinancials year : annual) {
            FinancialDataPoint filed = year.netProfit();
            if (!filed.available()) {
                continue;
            }
            Optional<FinancialDataPoint> other = provider.annualHistory().stream()
                    .filter(y -> y.period().known() && y.period().end().equals(year.period().end()))
                    .map(AnnualFinancials::netProfit).findFirst();
            String label = year.period().label();
            String why = other.isEmpty() ? "%s has no figure for %s".formatted(provider.provenance().source(), label)
                    : other.get().value() == null ? "%s's figure is %s".formatted(provider.provenance().source(),
                    other.get().status().name().toLowerCase(Locale.ROOT))
                    : other.get().unit() != filed.unit() ? "%s reports it in %s, and currencies are never converted"
                    .formatted(provider.provenance().source(), other.get().unit())
                    : null;
            if (why != null) {
                gaps.add(new DataGap(AREA, label + " net profit cross-check",
                        "The filed net profit for %s is not cross-checked: %s".formatted(label, why)));
            }
        }
        return gaps;
    }

    /** The provider's gaps about its own fiscal-year statements, which no longer describe the figures used. */
    private static boolean isProviderAnnualGap(DataGap gap) {
        return "annualHistory".equals(gap.item()) || (gap.item() != null && gap.item().startsWith("fiscal year "));
    }

    private static ReportedFinancials replace(ReportedFinancials provider, List<AnnualFinancials> annual,
                                              List<AnnualCrossCheckFigures> crossCheck, List<DataGap> gaps) {
        return new ReportedFinancials(provider.symbol(), provider.exchange(), provider.companyName(),
                provider.reportingCurrency(), provider.latestReportedQuarter(), provider.latestFiscalYear(),
                provider.headline(), provider.recentQuarters(), annual, crossCheck, List.copyOf(gaps),
                provider.provenance());
    }
}
