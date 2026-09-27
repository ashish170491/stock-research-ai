package com.ashish.stockresearch.marketdata.provider.yahoo;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

/**
 * A Yahoo number together with Yahoo's own formatted rendering of it:
 * {@code {"raw": 7.269, "fmt": "7.27%"}}. The formatted string is the only
 * statement Yahoo makes about what the raw number means (a fraction, a
 * percentage, whole currency units), so it is kept for
 * {@link YahooFieldSemantics} to verify against instead of guessing from the
 * number's size.
 *
 * @param fmt null when Yahoo sent a bare number - which leaves the meaning unverifiable
 */
@JsonDeserialize(using = YahooValue.Deserializer.class)
record YahooValue(BigDecimal raw, String fmt) {

    static YahooValue of(BigDecimal raw, String fmt) {
        return raw == null ? null : new YahooValue(raw, fmt);
    }

    /** Maps a raw/fmt object or a bare number; anything else (including {@code {}}) becomes null. */
    static class Deserializer extends ValueDeserializer<YahooValue> {

        @Override
        public YahooValue deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            return fromNode(parser.readValueAsTree());
        }
    }

    static YahooValue fromNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return new YahooValue(node.decimalValue(), null);
        }
        JsonNode raw = node.get("raw");
        if (raw == null || !raw.isNumber()) {
            return null;
        }
        JsonNode fmt = node.get("fmt");
        return new YahooValue(raw.decimalValue(), fmt != null && fmt.isString() ? fmt.asString() : null);
    }
}
