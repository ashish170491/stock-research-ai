package com.ashish.stockresearch.tool;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

/**
 * Passes a String tool result through unchanged. The default converter
 * JSON-encodes it, which would turn a Markdown report returned directly to
 * the user into one escaped string literal.
 */
public class PlainTextResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : result.toString();
    }
}
