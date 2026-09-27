package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.model.ProviderUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The meaning of a raw number comes from Yahoo's own format, never from the number's size. */
class YahooFieldSemanticsTest {

    private static YahooValue v(String raw, String fmt) {
        return new YahooValue(new BigDecimal(raw), fmt);
    }

    @Test
    void identifiesAFractionFromItsPercentFormat() {
        assertThat(YahooFieldSemantics.verify(v("0.086780004", "8.68%"), YahooFieldSemantics.RATIO))
                .contains(ProviderUnit.FRACTION);
    }

    @Test
    void identifiesAValueAlreadyInPercentagePoints() {
        // Tech Mahindra's live debtToEquity.
        assertThat(YahooFieldSemantics.verify(v("7.269", "7.27%"), YahooFieldSemantics.RATIO))
                .contains(ProviderUnit.PERCENTAGE);
    }

    @Test
    void cannotEstablishARatioWhoseFormatStatesNoUnit() {
        assertThat(YahooFieldSemantics.verify(v("7.269", "7.27"), YahooFieldSemantics.RATIO)).isEmpty();
        assertThat(YahooFieldSemantics.verify(v("7.269", null), YahooFieldSemantics.RATIO)).isEmpty();
    }

    @Test
    void cannotEstablishARatioWhenBothReadingsFitAValueNearZero() {
        assertThat(YahooFieldSemantics.verify(v("0.00", "0.00%"), YahooFieldSemantics.RATIO)).isEmpty();
    }

    @Test
    void confirmsWholeCurrencyUnitsFromTheScaleSuffix() {
        assertThat(YahooFieldSemantics.verify(v("2949644288000", "2.95T"), YahooFieldSemantics.AMOUNT))
                .contains(ProviderUnit.WHOLE_CURRENCY_UNITS);
        assertThat(YahooFieldSemantics.verify(v("591760982016", "591.76B"), YahooFieldSemantics.AMOUNT))
                .contains(ProviderUnit.WHOLE_CURRENCY_UNITS);
    }

    @Test
    void rejectsAnAmountTenTimesLargerThanItsOwnFormat() {
        assertThat(YahooFieldSemantics.verify(v("29496442880000", "2.95T"), YahooFieldSemantics.AMOUNT)).isEmpty();
    }

    @Test
    void neverReadsAPercentFormattedValueAsAnAmount() {
        assertThat(YahooFieldSemantics.verify(v("7.269", "7.27%"), YahooFieldSemantics.AMOUNT)).isEmpty();
    }

    @Test
    void confirmsPerShareValuesAndShareCountsIncludingGroupedDigits() {
        assertThat(YahooFieldSemantics.verify(v("1548.0", "1,548.00"), YahooFieldSemantics.PER_SHARE))
                .contains(ProviderUnit.PER_SHARE);
        assertThat(YahooFieldSemantics.verify(v("885923456", "885.92M"), YahooFieldSemantics.SHARES))
                .contains(ProviderUnit.SHARE_COUNT);
    }
}
