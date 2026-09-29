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

    @Test
    void parsesUniverseNamesLoosely() {
        assertThat(Universe.parse("Nifty 50")).contains(Universe.NIFTY50);
        assertThat(Universe.parse("nifty-50")).contains(Universe.NIFTY50);
        assertThat(Universe.parse("SENSEX")).isEmpty();
        assertThat(Universe.parse(null)).isEmpty();
    }
}
