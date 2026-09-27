package com.ashish.stockresearch.trace;

import java.util.Optional;
import java.util.UUID;

/**
 * Carries the current {@link ConversationTrace} on the request thread so the
 * advisor and the tool-calling manager can label their lines with the same
 * id without threading a parameter through Spring AI's API, which has no
 * slot for one.
 *
 * Tool execution happens on the thread that called the ChatClient, so a
 * plain ThreadLocal is enough. It is always cleared in a finally block -
 * the request threads are pooled and reused.
 */
final class TraceContext {

    private static final ThreadLocal<ConversationTrace> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    static ConversationTrace begin() {
        ConversationTrace trace = new ConversationTrace(UUID.randomUUID().toString().substring(0, 6));
        CURRENT.set(trace);
        return trace;
    }

    static Optional<ConversationTrace> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void end() {
        CURRENT.remove();
    }
}
