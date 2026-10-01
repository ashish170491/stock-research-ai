package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.FiledFinancialsProvider;
import com.ashish.stockresearch.research.FiledStatementChecks;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.FiledFinancials;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.StatementScope;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A company's annual results from its NSE filings, one per fiscal year, all consolidated or all standalone:
 *
 * <ul>
 *   <li><b>Which filings:</b> the full-year results listed as annual (to FY24) and the integrated filings for
 *       the fiscal year's last quarter (from FY25), whose documents carry the twelve-month figures.</li>
 *   <li><b>Which scope:</b> consolidated whenever the company files consolidated results at all; a year for
 *       which only standalone results are listed is then a gap, never filled with them. Standalone only for
 *       a company that files no consolidated results.</li>
 *   <li><b>Which filing, when a year has several:</b> the latest filed (a revision replaces the original);
 *       if its document cannot be read, the next latest.</li>
 *   <li><b>A renamed company:</b> its filings under a former symbol are read only when its filings show that
 *       symbol to be its own ({@link NseXbrlReader.Security}).</li>
 *   <li>Each year read is checked against itself ({@link FiledStatementChecks}).</li>
 * </ul>
 */
class NseFiledFinancialsProvider implements FiledFinancialsProvider {

    private static final Logger log = LoggerFactory.getLogger(NseFiledFinancialsProvider.class);
    static final MonthDay INDIAN_FISCAL_YEAR_END = MonthDay.of(3, 31);

    private final NseFilingsClient client;
    private final NseDocumentStore store;
    private final NseXbrlReader reader;
    private final FiledStatementChecks checks;
    /** Each company's two listings; null when not cached. A failure to list is never kept. */
    private final Cache<String, Listings> listings;

    /** Both of a company's listings, as NSE served them. */
    private record Listings(List<NseFilingListing> annual, List<NseFilingListing> integrated) {
    }

    NseFiledFinancialsProvider(NseFilingsClient client, NseDocumentStore store, NseXbrlReader reader,
                               FiledStatementChecks checks) {
        this(client, store, reader, checks, Duration.ZERO);
    }

    /**
     * @param listingTtl how long a company's listings are reused; zero to list every time. A filing never
     *                   changes once made, so only a new filing can be missed, and only for this long.
     */
    NseFiledFinancialsProvider(NseFilingsClient client, NseDocumentStore store, NseXbrlReader reader,
                               FiledStatementChecks checks, Duration listingTtl) {
        this.client = client;
        this.store = store;
        this.reader = reader;
        this.checks = checks;
        this.listings = listingTtl.isZero() ? null
                : Caffeine.newBuilder().expireAfterWrite(listingTtl).maximumSize(2_000).build();
    }

    @Override
    public FiledFinancials getFiledFinancials(String symbol, int fiscalYears) {
        String nseSymbol = symbol.strip().toUpperCase(Locale.ROOT);
        Listings listed = this.listings == null ? list(nseSymbol) : this.listings.get(nseSymbol, this::list);
        List<NseFilingListing> annual = listed.annual();
        MonthDay fiscalYearEnd = fiscalYearEnd(annual);
        List<NseFilingListing> listings = new ArrayList<>(annual);
        listed.integrated().stream()
                .filter(listing -> MonthDay.from(listing.periodEnd()).equals(fiscalYearEnd))
                .forEach(listings::add);
        if (listings.isEmpty()) {
            throw new ResearchDataUnavailableException(ResearchStatus.DATA_NOT_AVAILABLE,
                    "NSE lists no annual results with an XBRL document for " + nseSymbol);
        }
        StatementScope scope = listings.stream().anyMatch(l -> l.scope() == StatementScope.CONSOLIDATED)
                ? StatementScope.CONSOLIDATED : StatementScope.STANDALONE;
        Map<LocalDate, List<NseFilingListing>> byYear = listings.stream()
                .collect(Collectors.groupingBy(NseFilingListing::periodEnd));
        LocalDate latest = byYear.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();

        // Newest first, so that what the filings under the current symbol show about the security is known
        // before the filings a renamed company made under its former symbol are reached; reported oldest first.
        List<FiledYear> years = new ArrayList<>();
        List<DataGap> gaps = new ArrayList<>();
        NseXbrlReader.Security security = NseXbrlReader.Security.UNKNOWN;
        for (int i = 0; i < fiscalYears; i++) {
            LocalDate end = latest.minusYears(i).withDayOfMonth(1).plusMonths(1).minusDays(1);
            String label = "FY" + String.valueOf(end.getYear()).substring(2);
            List<NseFilingListing> candidates = byYear.getOrDefault(end, List.of()).stream()
                    .filter(listing -> listing.scope() == scope)
                    .sorted(Comparator.comparing(NseFilingListing::filedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .toList();
            if (candidates.isEmpty()) {
                gaps.add(new DataGap("Filed results", label, byYear.containsKey(end)
                        ? "NSE lists only %s results for %s, and %s and %s results are never mixed".formatted(
                        lower(other(scope)), label, lower(scope), lower(other(scope)))
                        : "NSE lists no annual results with an XBRL document for " + label));
                continue;
            }
            java.util.Optional<Read> read = read(candidates, label, security);
            if (read.isPresent()) {
                years.add(read.get().year());
                security = read.get().security();
            } else {
                gaps.add(new DataGap("Filed results", label,
                        "no %s filing for %s could be read; see the log for why".formatted(lower(scope), label)));
            }
        }
        return new FiledFinancials(nseSymbol, NseXbrlReader.SOURCE, scope, years.reversed(), gaps.reversed());
    }

    private Listings list(String nseSymbol) {
        return new Listings(client.annualResults(nseSymbol), client.integratedFinancials(nseSymbol));
    }

    /** A year read, and what is known of the security once its filing has been read. */
    private record Read(FiledYear year, NseXbrlReader.Security security) {
    }

    /** @param security what the newer years' filings have shown about the security */
    private java.util.Optional<Read> read(List<NseFilingListing> candidates, String label,
                                          NseXbrlReader.Security security) {
        for (NseFilingListing listing : candidates) {
            try {
                byte[] document = store.get(client.documentUri(listing.xbrlUrl()), () -> client.document(listing.xbrlUrl()));
                XbrlInstance xbrl = XbrlInstance.parse(document);
                FiledYear year = checks.check(reader.read(xbrl, listing, security));
                return java.util.Optional.of(new Read(year, reader.learn(security, xbrl, listing)));
            } catch (IllegalArgumentException | ResearchDataUnavailableException ex) {
                log.warn("NSE filing {} for {} {} could not be used: {}", listing.xbrlUrl(), listing.symbol(), label,
                        ex.getMessage());
            }
        }
        return java.util.Optional.empty();
    }

    /** The company's fiscal-year end, as its annual results filings state it; 31 March if they do not. */
    static MonthDay fiscalYearEnd(List<NseFilingListing> annual) {
        return annual.stream().map(listing -> MonthDay.from(listing.periodEnd()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(INDIAN_FISCAL_YEAR_END);
    }

    private static StatementScope other(StatementScope scope) {
        return scope == StatementScope.CONSOLIDATED ? StatementScope.STANDALONE : StatementScope.CONSOLIDATED;
    }

    private static String lower(StatementScope scope) {
        return scope.name().toLowerCase(Locale.ROOT);
    }
}
