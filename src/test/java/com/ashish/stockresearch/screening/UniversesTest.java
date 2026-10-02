package com.ashish.stockresearch.screening;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class UniversesTest {

    // S3-1
    @Test
    void theNifty50HasFiftyDistinctMembersAllListedOnNse() throws IOException {
        var members = new Universes().members(Universe.NIFTY50);
        Set<String> nseSymbols = new ClassPathResource("nse/equity-list.csv").getContentAsString(StandardCharsets.UTF_8)
                .lines().skip(1).map(line -> line.split(",", 2)[0].strip()).collect(Collectors.toSet());

        assertThat(members).hasSize(50);
        assertThat(members).extracting(Universes.Member::symbol).doesNotHaveDuplicates()
                .allSatisfy(symbol -> assertThat(nseSymbols).contains(symbol))
                .contains("RELIANCE", "TCS", "HDFCBANK", "INFY", "M&M", "BAJAJ-AUTO");
    }

    // S12-1
    @Test
    void theNifty500HasFiveHundredDistinctMembersIncludingEveryNifty50Member() throws IOException {
        Universes universes = new Universes();
        var members = universes.members(Universe.NIFTY500);

        assertThat(members).hasSize(500);
        assertThat(members).extracting(Universes.Member::symbol).doesNotHaveDuplicates()
                .containsAll(universes.members(Universe.NIFTY50).stream().map(Universes.Member::symbol).toList())
                .contains("360ONE", "TMPV", "TMCV", "ZYDUSLIFE", "ECLERX")
                // NSE Indices' placeholder for the HEG demerger is in the file, but is not a company
                .doesNotContain("DUMMYHEG");
    }

    // S12-1: the file is as downloaded, under a comment naming its source and date
    @Test
    void eachUniverseFileNamesItsSourceAndDateAboveTheDownloadedRows() throws IOException {
        for (Universe universe : Universe.values()) {
            var lines = new ClassPathResource(universe.resource()).getContentAsString(StandardCharsets.UTF_8).lines()
                    .toList();
            assertThat(lines.get(0)).as(universe.name()).startsWith("# Source: https://niftyindices.com/IndexConstituent/")
                    .containsPattern("20\\d\\d-\\d\\d-\\d\\d");
            assertThat(lines.get(1)).isEqualTo("Company Name,Industry,Symbol,Series,ISIN Code");
        }
        var nifty500 = new ClassPathResource(Universe.NIFTY500.resource()).getContentAsString(StandardCharsets.UTF_8)
                .lines().skip(2).toList();
        assertThat(nifty500).hasSize(501).filteredOn(line -> line.endsWith(",DUM545A01024")).singleElement()
                .asString().startsWith("Dummy HEG Ltd.,");
    }

    @Test
    void parsesUniverseNamesLoosely() {
        assertThat(Universe.parse("Nifty 50")).contains(Universe.NIFTY50);
        assertThat(Universe.parse("Nifty 500")).contains(Universe.NIFTY500);
        assertThat(Universe.parse("NIFTY-500")).contains(Universe.NIFTY500);
        assertThat(Universe.DEFAULT).isEqualTo(Universe.NIFTY50);
        assertThat(Universe.parse("nifty-50")).contains(Universe.NIFTY50);
        assertThat(Universe.parse("SENSEX")).isEmpty();
        assertThat(Universe.parse(null)).isEmpty();
    }
}
