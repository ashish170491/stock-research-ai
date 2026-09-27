package com.ashish.stockresearch.trace;

/**
 * Formats JSON payloads and model text for a single log line.
 *
 * Tool results are pretty-printed JSON several hundred characters long; left
 * alone they wrap across dozens of lines and bury the flow they are supposed
 * to illustrate. INFO gets a collapsed, truncated view; DEBUG gets the whole
 * thing.
 */
final class Payloads {

    private static final String ELLIPSIS = " ...(truncated, enable DEBUG on com.ashish.stockresearch.trace for the rest)";

    private Payloads() {
    }

    /** Collapses whitespace and truncates, for the readable INFO trace. */
    static String oneLine(String payload, TraceProperties properties) {
        if (payload == null || payload.isBlank()) {
            return "<empty>";
        }
        String collapsed = payload.replaceAll("\\s+", " ").strip();
        return collapsed.length() <= properties.maxPayloadChars()
                ? collapsed
                : collapsed.substring(0, properties.maxPayloadChars()) + ELLIPSIS;
    }

    /** The untouched payload, for DEBUG. */
    static String full(String payload) {
        return payload == null || payload.isBlank() ? "<empty>" : payload;
    }
}
