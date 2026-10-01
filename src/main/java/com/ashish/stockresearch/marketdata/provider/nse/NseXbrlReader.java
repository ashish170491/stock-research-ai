package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.calc.FinancialUnits;
import com.ashish.stockresearch.research.calc.FiscalCalendar;
import com.ashish.stockresearch.research.calc.FinancialUnits.ProviderScale;
import com.ashish.stockresearch.research.model.FiledItem;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.StatementFormat;
import com.ashish.stockresearch.research.model.StatementScope;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads one fiscal year of results from an NSE XBRL filing: each {@link FiledItem} exactly as filed, for
 * the twelve months ending at the filing's period end. Nothing is derived; consistency is checked later.
 *
 * <p>Two statement formats are read. Companies, NBFCs among them, file under Ind-AS Schedule III; banks
 * under the banking format, whose lines differ (interest earned, capital and reserves, deposits). An item
 * the format has no line for is UNAVAILABLE with that reason.
 */
final class NseXbrlReader {

    static final String SOURCE = "NSE corporate filings (XBRL)";
    static final String FIELD_PREFIX = "nse-xbrl.";

    /** The statement format of a filing. */
    enum Taxonomy { IND_AS, BANKING }

    /** Items that only a consolidated statement splits between the parent's owners and minority holders. */
    private static final Set<FiledItem> CONSOLIDATED_ONLY = EnumSet.of(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS,
            FiledItem.PROFIT_ATTRIBUTABLE_TO_NON_CONTROLLING_INTERESTS, FiledItem.EQUITY_ATTRIBUTABLE_TO_OWNERS,
            FiledItem.NON_CONTROLLING_INTEREST);

    private static final Map<FiledItem, String> IND_AS = new EnumMap<>(Map.ofEntries(
            Map.entry(FiledItem.REVENUE_FROM_OPERATIONS, "RevenueFromOperations"),
            Map.entry(FiledItem.PROFIT_BEFORE_TAX, "ProfitBeforeTax"),
            Map.entry(FiledItem.FINANCE_COSTS, "FinanceCosts"),
            Map.entry(FiledItem.TOTAL_EXPENSES, "Expenses"),
            Map.entry(FiledItem.PROFIT_FOR_PERIOD, "ProfitLossForPeriod"),
            Map.entry(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS, "ProfitOrLossAttributableToOwnersOfParent"),
            Map.entry(FiledItem.PROFIT_ATTRIBUTABLE_TO_NON_CONTROLLING_INTERESTS,
                    "ProfitOrLossAttributableToNonControllingInterests"),
            Map.entry(FiledItem.BASIC_EPS, "BasicEarningsLossPerShareFromContinuingAndDiscontinuedOperations"),
            Map.entry(FiledItem.PAID_UP_EQUITY_CAPITAL, "PaidUpValueOfEquityShareCapital"),
            Map.entry(FiledItem.FACE_VALUE_PER_SHARE, "FaceValueOfEquityShareCapital"),
            Map.entry(FiledItem.OPERATING_CASH_FLOW, "CashFlowsFromUsedInOperatingActivities"),
            Map.entry(FiledItem.DIVIDENDS_PAID, "DividendsPaidClassifiedAsFinancingActivities"),
            Map.entry(FiledItem.EQUITY_ATTRIBUTABLE_TO_OWNERS, "EquityAttributableToOwnersOfParent"),
            Map.entry(FiledItem.NON_CONTROLLING_INTEREST, "NonControllingInterest"),
            Map.entry(FiledItem.TOTAL_EQUITY, "Equity"),
            Map.entry(FiledItem.TOTAL_ASSETS, "Assets"),
            Map.entry(FiledItem.TOTAL_EQUITY_AND_LIABILITIES, "EquityAndLiabilities"),
            Map.entry(FiledItem.CURRENT_LIABILITIES, "CurrentLiabilities"),
            Map.entry(FiledItem.BORROWINGS_NON_CURRENT, "BorrowingsNoncurrent"),
            Map.entry(FiledItem.BORROWINGS_CURRENT, "BorrowingsCurrent")));

    private static final Map<FiledItem, String> BANKING = new EnumMap<>(Map.ofEntries(
            Map.entry(FiledItem.INTEREST_EARNED, "InterestEarned"),
            Map.entry(FiledItem.PROFIT_BEFORE_TAX, "ProfitLossFromOrdinaryActivitiesBeforeTax"),
            Map.entry(FiledItem.PROFIT_FOR_PERIOD, "ProfitLossForThePeriod"),
            Map.entry(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS,
                    "ProfitLossAfterTaxesMinorityInterestAndShareOfProfitLossOfAssociates"),
            Map.entry(FiledItem.PROFIT_ATTRIBUTABLE_TO_NON_CONTROLLING_INTERESTS, "ProfitLossOfMinorityInterest"),
            Map.entry(FiledItem.BASIC_EPS, "BasicEarningsPerShareAfterExtraordinaryItems"),
            Map.entry(FiledItem.PAID_UP_EQUITY_CAPITAL, "PaidUpValueOfEquityShareCapital"),
            Map.entry(FiledItem.FACE_VALUE_PER_SHARE, "FaceValueOfEquityShareCapital"),
            Map.entry(FiledItem.OPERATING_CASH_FLOW, "CashFlowsFromUsedInOperatingActivities"),
            Map.entry(FiledItem.DIVIDENDS_PAID, "DividendsPaidClassifiedAsFinancingActivities"),
            Map.entry(FiledItem.SHARE_CAPITAL, "Capital"),
            Map.entry(FiledItem.RESERVES_AND_SURPLUS, "ReservesAndSurplus"),
            Map.entry(FiledItem.TOTAL_ASSETS, "Assets"),
            Map.entry(FiledItem.TOTAL_EQUITY_AND_LIABILITIES, "CapitalAndLiabilities"),
            Map.entry(FiledItem.BORROWINGS, "Borrowings"),
            Map.entry(FiledItem.DEPOSITS, "Deposits")));

    /**
     * What a company's filings have shown about its security, learnt newest filing first. A renamed company
     * (Zomato to Eternal) filed its earlier results under its former symbol. The BSE scrip code, which a
     * rename does not change, links them: a filing under the current symbol states it, and a filing under a
     * former symbol that states the same code shows that symbol to be the company's. The oldest filings state
     * no scrip code; they are accepted under a symbol so shown, and under no other.
     */
    record Security(String scripCode, Set<String> formerSymbols) {

        static final Security UNKNOWN = new Security(null, Set.of());

        Security {
            formerSymbols = Set.copyOf(formerSymbols);
        }
    }

    FiledYear read(XbrlInstance xbrl, NseFilingListing listing) {
        return read(xbrl, listing, Security.UNKNOWN);
    }

    /**
     * @param security what the company's newer filings have shown about its security ({@link #learn})
     * @throws IllegalArgumentException if the document is not the full-year results the listing says it is:
     *                                  another company's, the other scope, not in INR, or with no twelve-month period
     */
    FiledYear read(XbrlInstance xbrl, NseFilingListing listing, Security security) {
        Optional<String> nature = xbrl.text("NatureOfReportStandaloneConsolidated");
        StatementScope scope = nature.map(n -> n.toLowerCase(Locale.ROOT).startsWith("consolidated")
                ? StatementScope.CONSOLIDATED : StatementScope.STANDALONE).orElse(null);
        if (nature.isEmpty() && !xbrl.unplacedContexts().isEmpty()) {
            throw new IllegalArgumentException(unplaced(xbrl, listing));
        }
        if (scope != listing.scope()) {
            throw new IllegalArgumentException("%s: NSE lists it as %s but the document states %s".formatted(
                    listing.xbrlUrl(), listing.scope(), nature.orElse("no scope")));
        }
        // "BAJAJAUTO" in Bajaj Auto's documents is NSE's "BAJAJ-AUTO": punctuation is not part of the name
        xbrl.text("Symbol").ifPresent(symbol -> {
            if (!lettersAndDigits(symbol).equals(lettersAndDigits(listing.symbol()))) {
                formerSymbol(xbrl, listing, symbol, security);
            }
        });
        String currency = xbrl.text("DescriptionOfPresentationCurrency").orElse(null);
        if (!"INR".equalsIgnoreCase(currency)) {
            throw new IllegalArgumentException("%s: presented in %s, not INR".formatted(listing.xbrlUrl(),
                    currency == null ? "an unstated currency" : currency));
        }
        LocalDate end = listing.periodEnd();
        LocalDate start = end.plusDays(1).minusYears(1);
        if (!xbrl.durationPeriods().contains(List.of(start, end))) {
            throw new IllegalArgumentException(xbrl.unplacedContexts().isEmpty()
                    ? "%s reports no figures for the twelve months %s to %s".formatted(listing.xbrlUrl(), start, end)
                    : unplaced(xbrl, listing));
        }
        Taxonomy taxonomy = taxonomy(xbrl);
        ReportingPeriod fiscalYear = FiscalCalendar.forFiscalYearEnd(end).fiscalYear(end);
        ReportingPeriod closing = ReportingPeriod.pointInTime(end);
        LocalDate filedOn = listing.filedAt() == null ? null : listing.filedAt().toLocalDate();
        Map<FiledItem, FinancialDataPoint> items = new EnumMap<>(FiledItem.class);
        for (FiledItem item : FiledItem.values()) {
            ReportingPeriod period = item.kind() == FiledItem.Kind.DURATION ? fiscalYear : closing;
            items.put(item, point(xbrl, taxonomy, scope, item, start, end, period, filedOn));
        }
        return new FiledYear(fiscalYear, scope, taxonomy == Taxonomy.BANKING ? StatementFormat.BANK : StatementFormat.COMPANY,
                listing.audited(), filedOn, listing.xbrlUrl(), items, List.of());
    }

    private static FinancialDataPoint point(XbrlInstance xbrl, Taxonomy taxonomy, StatementScope scope, FiledItem item,
                                            LocalDate start, LocalDate end, ReportingPeriod period, LocalDate filedOn) {
        String metric = metric(item);
        String concept = (taxonomy == Taxonomy.BANKING ? BANKING : IND_AS).get(item);
        SourceInfo source = new SourceInfo(SOURCE, SourceType.OFFICIAL_FILING,
                concept == null ? null : FIELD_PREFIX + concept, filedOn, end);
        if (concept == null) {
            return FinancialDataPoint.unavailable(metric, item.unit(), period, source, taxonomy == Taxonomy.BANKING
                    ? "a bank's statement has no line for " + item.label()
                    : "this statement format has no line for " + item.label());
        }
        if (scope == StatementScope.STANDALONE && CONSOLIDATED_ONLY.contains(item)) {
            return FinancialDataPoint.unavailable(metric, item.unit(), period, source,
                    "a standalone statement does not split " + item.label().replace(" attributable to", "") + " by holder");
        }
        XbrlInstance.Lookup found = item.kind() == FiledItem.Kind.DURATION ? xbrl.duration(concept, start, end)
                : xbrl.instant(concept, end);
        return switch (found) {
            case XbrlInstance.Lookup.Missing ignored -> FinancialDataPoint.unavailable(metric, item.unit(), period,
                    source, "the filing does not report %s (%s) %s".formatted(item.label(), concept,
                    item.kind() == FiledItem.Kind.DURATION ? "for " + period.label() : "at " + period.end()));
            case XbrlInstance.Lookup.Ambiguous ambiguous -> FinancialDataPoint.rejected(metric, item.unit(), period,
                    source, "the filing reports %s (%s) for %s more than once with different values (%s); neither is used"
                            .formatted(item.label(), concept, period.label(), ambiguous.values().stream()
                                    .map(java.math.BigDecimal::toPlainString).toList()));
            case XbrlInstance.Lookup.NotANumber text -> FinancialDataPoint.rejected(metric, item.unit(), period, source,
                    "the filing's %s (%s) is not a number: '%s'".formatted(item.label(), concept, text.text()));
            case XbrlInstance.Lookup.Found value -> value(metric, item, value, period, source);
        };
    }

    private static FinancialDataPoint value(String metric, FiledItem item, XbrlInstance.Lookup.Found found,
                                            ReportingPeriod period, SourceInfo source) {
        String unitRef = found.unitRef();
        if (item.unit() == com.ashish.stockresearch.research.model.Unit.INR_PER_SHARE) {
            if (unitRef != null && !unitRef.equalsIgnoreCase("INRPerShare")) {
                return FinancialDataPoint.unavailable(metric, item.unit(), period, source,
                        "the filing states %s in '%s', not rupees per share".formatted(item.label(), unitRef));
            }
            return FinancialDataPoint.reported(metric, found.value(), ProviderUnit.PER_SHARE, found.value(),
                    item.unit(), period, source);
        }
        // Old-format bank filings omit the unit on some balance-sheet lines; the document's INR presentation
        // currency, checked above, then states it.
        if (unitRef != null && !unitRef.equalsIgnoreCase("INR")) {
            return FinancialDataPoint.unavailable(metric, item.unit(), period, source,
                    "the filing states %s in '%s', not rupees".formatted(item.label(), unitRef));
        }
        return FinancialUnits.normalizeAmount(found.value(), ProviderScale.ONES, "INR")
                .map(normalized -> FinancialDataPoint.reported(metric, found.value(), ProviderUnit.WHOLE_CURRENCY_UNITS,
                        normalized.value(), normalized.unit(), period, source).withCurrency(normalized.currency()))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, item.unit(), period, source,
                        "the amount could not be normalised"));
    }

    /**
     * A bank's statement is known by its balance sheet, "capital and liabilities". An NBFC files Ind-AS
     * statements that also state interest earned, beside revenue from operations; it is not a bank.
     */
    static Taxonomy taxonomy(XbrlInstance xbrl) {
        return xbrl.hasConcept("CapitalAndLiabilities")
                || (xbrl.hasConcept("InterestEarned") && !xbrl.hasConcept("RevenueFromOperations"))
                ? Taxonomy.BANKING : Taxonomy.IND_AS;
    }

    private static String unplaced(XbrlInstance xbrl, NseFilingListing listing) {
        return "%s refers to columns it neither defines nor dates (%s), so its figures cannot be placed in a period"
                .formatted(listing.xbrlUrl(), String.join(", ", xbrl.unplacedContexts()));
    }

    /** Refuses a document filed under another symbol unless the company's filings show it to be the company's. */
    private static void formerSymbol(XbrlInstance xbrl, NseFilingListing listing, String symbol, Security security) {
        String code = xbrl.text("ScripCode").map(String::strip).orElse(null);
        String why;
        if (security.scripCode() == null) {
            why = "no filing under %s states a scrip code to show they are one security".formatted(listing.symbol());
        } else if (code != null && !code.equals(security.scripCode())) {
            why = "its scrip code %s is not %s's %s".formatted(code, listing.symbol(), security.scripCode());
        } else if (code == null && !security.formerSymbols().contains(lettersAndDigits(symbol))) {
            why = "it states no scrip code, and no filing shows %s to be a former symbol of %s".formatted(symbol,
                    listing.symbol());
        } else {
            return;
        }
        throw new IllegalArgumentException("%s: the document is for %s, not %s, and %s".formatted(listing.xbrlUrl(),
                symbol, listing.symbol(), why));
    }

    /** What this document, once read, adds to what is known of the security. */
    Security learn(Security security, XbrlInstance xbrl, NseFilingListing listing) {
        String symbol = xbrl.text("Symbol").map(NseXbrlReader::lettersAndDigits).orElse(null);
        String code = xbrl.text("ScripCode").map(String::strip).orElse(null);
        if (symbol == null || code == null) {
            return security;
        }
        if (symbol.equals(lettersAndDigits(listing.symbol()))) {
            return security.scripCode() == null ? new Security(code, security.formerSymbols()) : security;
        }
        if (code.equals(security.scripCode()) && !security.formerSymbols().contains(symbol)) {
            Set<String> former = new java.util.HashSet<>(security.formerSymbols());
            former.add(symbol);
            return new Security(security.scripCode(), former);
        }
        return security;
    }

    private static String lettersAndDigits(String symbol) {
        return symbol.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** "PROFIT_ATTRIBUTABLE_TO_OWNERS" as "filedProfitAttributableToOwners". */
    static String metric(FiledItem item) {
        StringBuilder name = new StringBuilder("filed");
        for (String word : item.name().toLowerCase(Locale.ROOT).split("_")) {
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }
}
