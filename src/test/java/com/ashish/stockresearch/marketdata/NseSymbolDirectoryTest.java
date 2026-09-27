package com.ashish.stockresearch.marketdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NseSymbolDirectoryTest {

    /** The bundled NSE equity list. */
    private static final NseSymbolDirectory NSE = new NseSymbolDirectory();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "Bharti Airtel, BHARTIARTL",
            "HDFC Bank, HDFCBANK",
            "Infosys, INFY",
            "ICICI Bank Ltd., ICICIBANK",
            "Tech Mahindra, TECHM",
            "Larsen and Toubro, LT",
            "The Tata Consultancy Services, TCS",
            "Tata Consultancy, TCS",
    })
    void resolvesCompanyNamesToTheirNseSymbol(String name, String symbol) {
        assertThat(NSE.resolve(name)).get().extracting(NseSymbolDirectory.Listing::symbol).isEqualTo(symbol);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INFY", "infy", " hdfcbank "})
    void acceptsAListedSymbolAsIs(String symbol) {
        assertThat(NSE.resolve(symbol)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Acme Widgets Unlisted", "Quantum Flux Capacitors", "", "   "})
    void resolvesAnUnknownNameToNothing(String name) {
        assertThat(NSE.resolve(name)).isEmpty();
    }

    @Test
    void resolvesNothingWhenTheBestPartialMatchIsTied() {
        NseSymbolDirectory twoBanks = new NseSymbolDirectory(new ByteArrayResource("""
                SYMBOL,NAME OF COMPANY
                CANBK,Canara Bank
                INDIANB,Indian Bank
                """.getBytes(StandardCharsets.UTF_8)));

        assertThat(twoBanks.resolve("Bank")).isEmpty();
        assertThat(twoBanks.resolve("Canara")).get().extracting(NseSymbolDirectory.Listing::symbol)
                .isEqualTo("CANBK");
    }

    @Test
    void normalisesCorporateSuffixesAndPunctuation() {
        assertThat(NseSymbolDirectory.normalise("The Larsen & Toubro Ltd.")).isEqualTo("larsen toubro");
        assertThat(NseSymbolDirectory.normalise("HDFC Bank Limited")).isEqualTo("hdfc bank");
    }
}
