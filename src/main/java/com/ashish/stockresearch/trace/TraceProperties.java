package com.ashish.stockresearch.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls the development trace that shows how a question flows through
 * the model and the research tools.
 *
 * @param enabled          turn the whole trace on or off
 * @param maxPayloadChars  how much of a tool result to print at INFO. The full,
 *                         untruncated payload is always available at DEBUG, so this
 *                         only decides how noisy the normal log is.
 */
@ConfigurationProperties(prefix = "app.trace")
public record TraceProperties(
        boolean enabled,
        int maxPayloadChars
) {

    public TraceProperties {
        if (maxPayloadChars <= 0) {
            maxPayloadChars = 600;
        }
    }
}
