package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.FiledStatementChecks;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FiledFinancials;
import com.ashish.stockresearch.research.model.FiledItem;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.StatementScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ashish.stockresearch.marketdata.provider.nse.NseXbrlReaderTest.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The listings, the documents and the choice of filing per year, over recorded NSE responses: the API and
 * the archive are both mocked, so no test reaches NSE.
 */
class NseFiledFinancialsProviderTest {

    private static final String ARCHIVE = "https://nsearchives.nseindia.com/corporate/xbrl/";

    @TempDir
    Path documents;

    private final RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://www.nseindia.com");
    private final MockRestServiceServer api = MockRestServiceServer.bindTo(apiBuilder).ignoreExpectOrder(true).build();
    private final RestClient.Builder archiveBuilder = RestClient.builder();
    private final MockRestServiceServer archive = MockRestServiceServer.bindTo(archiveBuilder).ignoreExpectOrder(true)
            .build();

    private NseFilingsClient client() {
        return new NseFilingsClient(apiBuilder.build(), archiveBuilder.build(), "nsearchives.nseindia.com");
    }

    private NseFiledFinancialsProvider provider() {
        return new NseFiledFinancialsProvider(client(), new NseDocumentStore(documents), new NseXbrlReader(),
                new FiledStatementChecks());
    }

    private void listings(String symbol) {
        String lower = symbol.toLowerCase(java.util.Locale.ROOT);
        api.expect(manyTimes(), requestToUriTemplate("https://www.nseindia.com/api/corporates-financial-results"
                        + "?index=equities&symbol={s}&period=Annual", symbol))
                .andRespond(withSuccess(fixture(lower + "-annual-results.json"), MediaType.APPLICATION_JSON));
        api.expect(manyTimes(), requestToUriTemplate("https://www.nseindia.com/api/integrated-filing-results"
                        + "?index=equities&symbol={s}&size=100&page=1", symbol))
                .andRespond(withSuccess(fixture(lower + "-integrated-filings.json"), MediaType.APPLICATION_JSON));
    }

    private void document(String file, String fixture) {
        archive.expect(manyTimes(), requestTo(ARCHIVE + file)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture(fixture), MediaType.APPLICATION_XML));
    }

    private void missingDocument(String file) {
        archive.expect(manyTimes(), requestTo(ARCHIVE + file)).andRespond(withStatus(HttpStatus.NOT_FOUND));
    }

    // --- The listings ---------------------------------------------------------------------------------

    @Test
    void readsBothListingsLeavingOutEntriesWithoutADocument() {
        listings("RELIANCE");

        List<NseFilingListing> annual = client().annualResults("RELIANCE");
        List<NseFilingListing> integrated = client().integratedFinancials("RELIANCE");

        // the filings before XBRL ("-") are left out
        assertThat(annual).allMatch(l -> l.xbrlUrl().startsWith(ARCHIVE))
                .extracting(NseFilingListing::periodEnd).contains(LocalDate.of(2024, 3, 31))
                .doesNotContain(LocalDate.of(2018, 3, 31));
        NseFilingListing fy24 = annual.stream().filter(l -> l.periodEnd().equals(LocalDate.of(2024, 3, 31))
                && l.scope() == StatementScope.CONSOLIDATED).findFirst().orElseThrow();
        assertThat(fy24.audited()).isTrue();
        assertThat(fy24.filedAt()).isEqualTo(java.time.LocalDateTime.of(2024, 4, 22, 19, 47));
        assertThat(fy24.xbrlUrl()).isEqualTo(ARCHIVE + "INDAS_104634_1106038_22042024074704.xml");
        // governance filings are left out; every quarter's financials are listed
        assertThat(integrated).allMatch(l -> l.format() == NseFilingListing.Format.INTEGRATED)
                .extracting(NseFilingListing::periodEnd).contains(LocalDate.of(2026, 3, 31), LocalDate.of(2025, 12, 31));
        assertThat(integrated).filteredOn(l -> l.periodEnd().equals(LocalDate.of(2026, 3, 31)))
                .extracting(NseFilingListing::scope)
                .containsExactlyInAnyOrder(StatementScope.CONSOLIDATED, StatementScope.STANDALONE);
    }

    @Test
    void asksForTheNextPageUntilTheStatedTotalIsRead() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://www.nseindia.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] all = fixture("hcltech-integrated-filings.json");
        // NSE has served 20 entries a page whatever size was asked for
        server.expect(requestTo(org.hamcrest.Matchers.containsString("page=1")))
                .andRespond(withSuccess(slice(all, 0, 20), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("page=2")))
                .andRespond(withSuccess(slice(all, 20, Integer.MAX_VALUE), MediaType.APPLICATION_JSON));
        NseFilingsClient client = new NseFilingsClient(builder.build(), RestClient.create(), "nsearchives.nseindia.com");

        List<NseFilingListing> listings = client.integratedFinancials("HCLTECH");

        server.verify();
        // FY25's consolidated filing is past the first 20 entries
        assertThat(listings).anyMatch(l -> l.periodEnd().equals(LocalDate.of(2025, 3, 31))
                && l.scope() == StatementScope.CONSOLIDATED);
    }

    /** The recorded listing's entries {@code from} to {@code from + size}, with its full total. */
    private static byte[] slice(byte[] listing, int from, int size) {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.node.ObjectNode page = (tools.jackson.databind.node.ObjectNode) mapper.readTree(listing);
        tools.jackson.databind.node.ArrayNode data = (tools.jackson.databind.node.ArrayNode) page.get("data");
        tools.jackson.databind.node.ArrayNode part = mapper.createArrayNode();
        for (int i = from; i < Math.min(data.size(), from + (long) size); i++) {
            part.add(data.get(i));
        }
        page.set("data", part);
        return mapper.writeValueAsBytes(page);
    }

    @Test
    void neverDownloadsFromAnywhereButNsesArchive() {
        NseFilingsClient client = client();

        assertThatThrownBy(() -> client.document("https://example.com/corporate/xbrl/INDAS_1.xml"))
                .hasMessageContaining("Refusing a document link");
        assertThatThrownBy(() -> client.document("http://nsearchives.nseindia.com/corporate/xbrl/INDAS_1.xml"))
                .hasMessageContaining("Refusing a document link");
        assertThatThrownBy(() -> client.document(ARCHIVE + "../../etc/passwd"))
                .hasMessageContaining("Refusing a document link");
    }

    @Test
    void anUnreachableNseIsAProviderOutageNotAnEmptyAnswer() {
        api.expect(manyTimes(), requestTo(org.hamcrest.Matchers.containsString("/api/")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client().annualResults("RELIANCE"))
                .isInstanceOfSatisfying(ResearchDataUnavailableException.class,
                        ex -> assertThat(ex.status()).isEqualTo(ResearchStatus.PROVIDER_UNAVAILABLE));
    }

    // --- The document store ---------------------------------------------------------------------------

    @Test
    void keepsEachDocumentAndNeverFetchesItTwice() throws Exception {
        NseDocumentStore store = new NseDocumentStore(documents);
        AtomicInteger fetches = new AtomicInteger();
        URI uri = URI.create(ARCHIVE + "INDAS_1.xml");

        byte[] first = store.get(uri, () -> {
            fetches.incrementAndGet();
            return "<x/>".getBytes();
        });
        byte[] second = store.get(uri, () -> {
            throw new AssertionError("fetched again");
        });

        assertThat(fetches).hasValue(1);
        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(documents.resolve("INDAS_1.xml"))).isEqualTo("<x/>");
        assertThat(Files.list(documents)).hasSize(1);
    }

    // --- Choosing a filing per year -------------------------------------------------------------------

    @Test
    void readsEachYearsConsolidatedFilingFromEitherListing() {
        listings("RELIANCE");
        document("INDAS_104634_1106038_22042024074704.xml", "reliance-fy24-consolidated.xml");
        document("INTEGRATED_FILING_INDAS_1658776_24042026105714_WEB.xml", "reliance-fy26-consolidated.xml");
        missingDocument("INTEGRATED_FILING_INDAS_1425445_25042025095716_WEB.xml");

        FiledFinancials filed = provider().getFiledFinancials("reliance", 3);

        assertThat(filed.symbol()).isEqualTo("RELIANCE");
        assertThat(filed.scope()).isEqualTo(StatementScope.CONSOLIDATED);
        assertThat(filed.years()).extracting(y -> y.period().label()).containsExactly("FY24", "FY26");
        assertThat(filed.years()).extracting(y -> y.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).value())
                .usingComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .containsExactly(new java.math.BigDecimal("69621"), new java.math.BigDecimal("80775"));
        // the year whose document could not be fetched is a gap, not a guess
        assertThat(filed.dataGaps()).extracting(DataGap::item, DataGap::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple("FY25", "no consolidated filing for FY25 could be read; see the log for why"));
        assertThat(filed.years()).allMatch(y -> !y.checks().isEmpty());
    }

    @Test
    void neverFillsAConsolidatedHistoryWithAStandaloneYear() {
        listings("RELIANCE");
        document("INDAS_104634_1106038_22042024074704.xml", "reliance-fy24-consolidated.xml");
        document("INTEGRATED_FILING_INDAS_1658776_24042026105714_WEB.xml", "reliance-fy26-consolidated.xml");
        missingDocument("INTEGRATED_FILING_INDAS_1425445_25042025095716_WEB.xml");
        missingDocument("INDAS_90782_831396_21042023075933.xml");

        FiledFinancials filed = provider().getFiledFinancials("RELIANCE", 5);

        // NSE lists only Reliance's standalone results for FY22
        assertThat(filed.dataGaps()).filteredOn(gap -> gap.item().equals("FY22")).singleElement()
                .extracting(DataGap::reason).isEqualTo("NSE lists only standalone results for FY22, and consolidated "
                        + "and standalone results are never mixed");
        assertThat(filed.years()).allMatch(y -> y.scope() == StatementScope.CONSOLIDATED);
    }

    @Test
    void aYearWhoseFilingContradictsItselfIsKeptWithTheContradictionMarked() {
        listings("HCLTECH");
        document("INDAS_104835_1109692_26042024084717.xml", "hcltech-fy24-consolidated.xml");
        document("INTEGRATED_FILING_INDAS_1422697_22042025070125_WEB.xml", "hcltech-fy25-consolidated.xml");
        archive.expect(manyTimes(), requestTo(org.hamcrest.Matchers.containsString("INTEGRATED_FILING_INDAS_16")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        FiledFinancials filed = provider().getFiledFinancials("HCLTECH", 3);

        FiledYear fy24 = filed.years().stream().filter(y -> y.period().label().equals("FY24")).findFirst().orElseThrow();
        FiledYear fy25 = filed.years().stream().filter(y -> y.period().label().equals("FY25")).findFirst().orElseThrow();
        assertThat(fy24.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).status()).isEqualTo(DataStatus.INVALID);
        assertThat(fy25.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).value()).isEqualByComparingTo("17390");
    }

    @Test
    void readsARenamedCompanysEarlierFilingsAsTheSameSecurity() {
        // Zomato became Eternal: NSE lists its FY22-FY24 results under ETERNAL, and the documents say ZOMATO
        listings("ETERNAL");
        missingDocument("INTEGRATED_FILING_INDAS_1660628_28042026082148_WEB.xml");
        document("INTEGRATED_FILING_INDAS_1430257_01052025070843_WEB.xml", "eternal-fy25-consolidated.xml");
        document("INDAS_105795_1122859_13052024092656.xml", "zomato-fy24-consolidated-before-rename-to-eternal.xml");
        missingDocument("INDAS_92162_847523_19052023092307.xml");
        document("INDAS_85187_659384_24052022043331_WEB.xml", "zomato-fy22-consolidated-no-scrip-code.xml");

        FiledFinancials filed = provider().getFiledFinancials("ETERNAL", 5);

        // FY25's document, filed as ETERNAL, states the scrip code 543320 (FY26's could not be fetched, so it is
        // learnt from the next latest year); FY24's, filed as ZOMATO, states it too, which shows ZOMATO to be
        // Eternal's; so FY22's, which states no scrip code, is read under ZOMATO
        assertThat(filed.years()).extracting(y -> y.period().label()).containsExactly("FY22", "FY24", "FY25");
        assertThat(filed.years()).extracting(y -> y.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).value())
                .usingComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class)
                .containsExactly(new java.math.BigDecimal("-1208.7"), new java.math.BigDecimal("351"),
                        new java.math.BigDecimal("527"));
        assertThat(filed.dataGaps()).extracting(DataGap::item).containsExactly("FY23", "FY26");
    }

    @Test
    void neverReadsAnotherSymbolsFilingWithoutTheCompanysOwnFilingsLinkingThem() {
        // the same listings, but no filing under ETERNAL can be read: nothing shows ZOMATO to be Eternal
        listings("ETERNAL");
        missingDocument("INTEGRATED_FILING_INDAS_1660628_28042026082148_WEB.xml");
        missingDocument("INTEGRATED_FILING_INDAS_1430257_01052025070843_WEB.xml");
        document("INDAS_105795_1122859_13052024092656.xml", "zomato-fy24-consolidated-before-rename-to-eternal.xml");

        FiledFinancials filed = provider().getFiledFinancials("ETERNAL", 3);

        assertThat(filed.years()).isEmpty();
        assertThat(filed.dataGaps()).extracting(DataGap::item).containsExactly("FY24", "FY25", "FY26");
    }

    @Test
    void asksNseForACompanysListingsOnceWhileTheyAreCached() {
        api.expect(org.springframework.test.web.client.ExpectedCount.once(), requestToUriTemplate(
                        "https://www.nseindia.com/api/corporates-financial-results?index=equities&symbol={s}&period=Annual",
                        "RELIANCE"))
                .andRespond(withSuccess(fixture("reliance-annual-results.json"), MediaType.APPLICATION_JSON));
        api.expect(org.springframework.test.web.client.ExpectedCount.once(), requestToUriTemplate(
                        "https://www.nseindia.com/api/integrated-filing-results?index=equities&symbol={s}&size=100&page=1",
                        "RELIANCE"))
                .andRespond(withSuccess(fixture("reliance-integrated-filings.json"), MediaType.APPLICATION_JSON));
        document("INTEGRATED_FILING_INDAS_1658776_24042026105714_WEB.xml", "reliance-fy26-consolidated.xml");
        NseFiledFinancialsProvider cached = new NseFiledFinancialsProvider(client(), new NseDocumentStore(documents),
                new NseXbrlReader(), new FiledStatementChecks(), java.time.Duration.ofHours(12));

        cached.getFiledFinancials("RELIANCE", 1);
        FiledFinancials again = cached.getFiledFinancials("reliance", 1);

        api.verify();
        assertThat(again.years()).extracting(y -> y.period().label()).containsExactly("FY26");
    }

    @Test
    void saysSoWhenNseListsNothingForACompany() {
        api.expect(manyTimes(), requestTo(org.hamcrest.Matchers.containsString("corporates-financial-results")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        api.expect(manyTimes(), requestTo(org.hamcrest.Matchers.containsString("integrated-filing-results")))
                .andRespond(withSuccess("{\"data\":[],\"totalCount\":0}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider().getFiledFinancials("NOSUCH", 3))
                .isInstanceOfSatisfying(ResearchDataUnavailableException.class,
                        ex -> assertThat(ex.status()).isEqualTo(ResearchStatus.DATA_NOT_AVAILABLE));
    }

    @Test
    void takesTheFiscalYearEndFromTheCompanysOwnFilings() {
        NseFilingListing december = new NseFilingListing("X", LocalDate.of(2020, 12, 31), StatementScope.CONSOLIDATED,
                true, null, ARCHIVE + "a.xml", NseFilingListing.Format.RESULTS);

        assertThat(NseFiledFinancialsProvider.fiscalYearEnd(List.of(december, december)))
                .isEqualTo(java.time.MonthDay.of(12, 31));
        assertThat(NseFiledFinancialsProvider.fiscalYearEnd(List.of()))
                .isEqualTo(NseFiledFinancialsProvider.INDIAN_FISCAL_YEAR_END);
    }
}
