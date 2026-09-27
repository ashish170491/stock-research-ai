package com.ashish.stockresearch.trace;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * State for one user question: a short id to tie the log lines together,
 * plus counters for how many times the model was consulted and how many
 * tools it ended up calling.
 *
 * One instance lives per in-flight request. Tool calls run on the request
 * thread, so the counters need no synchronisation, but they are atomic
 * anyway to stay correct if tool execution is ever parallelised.
 */
final class ConversationTrace {

    private final String id;
    private final long startedAtNanos;
    private final AtomicInteger modelRounds = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();

    ConversationTrace(String id) {
        this.id = id;
        this.startedAtNanos = System.nanoTime();
    }

    String id() {
        return id;
    }

    int nextModelRound() {
        return modelRounds.incrementAndGet();
    }

    int modelRounds() {
        // The model is consulted once more than it requests tools: the last
        // round is the one that produces the answer instead of a tool call.
        return modelRounds.get() + 1;
    }

    void countToolCalls(int count) {
        toolCalls.addAndGet(count);
    }

    int toolCalls() {
        return toolCalls.get();
    }

    double elapsedSeconds() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
    }
}
