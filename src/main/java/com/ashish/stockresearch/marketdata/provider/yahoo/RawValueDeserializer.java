package com.ashish.stockresearch.marketdata.provider.yahoo;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;

/**
 * Yahoo wraps most numbers as {@code {"raw": 2082.0, "fmt": "2,082.00"}},
 * but not consistently - the same field can arrive as a bare number
 * depending on the module and symbol. This flattens both shapes to a plain
 * {@link BigDecimal} so the response records stay readable and an upstream
 * shape change degrades to {@code null} instead of failing the whole parse.
 *
 * {@code null} here always means "the provider did not give us this value",
 * which callers must surface as unknown rather than zero.
 */
class RawValueDeserializer extends ValueDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        JsonNode node = parser.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        JsonNode raw = node.get("raw");
        return raw != null && raw.isNumber() ? raw.decimalValue() : null;
    }
}
