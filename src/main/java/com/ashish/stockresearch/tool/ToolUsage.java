package com.ashish.stockresearch.tool;

import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the tools returned during one chat request: which tools were
 * called, with what outcome, and the exact text each handed to the model.
 * The chat service uses it to check the model considered the tools a
 * question needs, and to check every figure in the answer against what the
 * tools actually supplied.
 *
 * One instance per request, passed to the tools through Spring AI's
 * {@link ToolContext} - no thread-local state.
 */
public final class ToolUsage {

    public static final String CONTEXT_KEY = "toolUsage";

    /**
     * @param status   the tool's outcome, e.g. OK or CAPABILITY_NOT_SUPPORTED
     * @param evidence the text the tool returned to the model
     */
    public record Call(String tool, String symbol, String status, String evidence) {
    }

    /**
     * One passage a document-search tool actually returned, independent of whether the model's
     * answer repeats it in words - the application shows it regardless (S7-5), the same way a
     * report's sources are Java-rendered rather than left to the model to remember to mention.
     */
    public record Citation(String label) {
    }

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<Citation> citations = Collections.synchronizedList(new ArrayList<>());

    public Map<String, Object> asToolContext() {
        return Map.of(CONTEXT_KEY, this);
    }

    /** Records a call on the request's usage, if the caller supplied one (direct calls, e.g. in tests, may not). */
    public static void record(ToolContext context, Call call) {
        if (context != null && context.getContext().get(CONTEXT_KEY) instanceof ToolUsage usage) {
            usage.calls.add(call);
        }
    }

    /** Records a citation a document-search tool returned, if the caller supplied a usage to record it on. */
    public static void recordCitation(ToolContext context, Citation citation) {
        if (context != null && context.getContext().get(CONTEXT_KEY) instanceof ToolUsage usage) {
            usage.citations.add(citation);
        }
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** Every document citation returned this request, in the order first seen, without repeats. */
    public List<Citation> citations() {
        synchronized (citations) {
            return citations.stream().distinct().toList();
        }
    }

    public Set<String> toolsCalled() {
        Set<String> tools = new LinkedHashSet<>();
        calls().forEach(call -> tools.add(call.tool()));
        return tools;
    }

    public boolean called(String tool) {
        return calls().stream().anyMatch(call -> call.tool().equals(tool));
    }

    /** Everything the tools returned in this request, concatenated. */
    public String evidence() {
        StringBuilder text = new StringBuilder();
        calls().forEach(call -> text.append(call.evidence()).append('\n'));
        return text.toString();
    }
}
