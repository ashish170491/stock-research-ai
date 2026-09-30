package com.ashish.stockresearch.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Traces a chain workflow - several model calls with Java steps between them - as one conversation trace.
 * The chain opens the trace; each stage logs a line under its id; the model calls inside it are logged by
 * {@link ConversationTraceAdvisor} as calls within that trace rather than as traces of their own. So the
 * whole flow reads top to bottom:
 *
 * <pre>
 * [a1b2c3] USER > Find profitable IT companies with low debt and ROE above 18%
 * [a1b2c3] CHAIN screener: stage 1 extract criteria > ...
 * [a1b2c3]   model call 1 > ... / model call 1 < in 3.10s ...
 * [a1b2c3] CHAIN screener: stage 2 validate > ...
 * ...
 * [a1b2c3] DONE in 41.20s | 2 model call(s)
 * </pre>
 */
@Component
public class ChainTracer {

    private static final Logger log = LoggerFactory.getLogger("com.ashish.stockresearch.trace.Trace");
    private static final String RULE = "=".repeat(78);

    private final TraceProperties properties;

    public ChainTracer(TraceProperties properties) {
        this.properties = properties;
    }

    /** Opens a trace for a chain on this thread; close it (try-with-resources) when the chain ends. */
    public Chain start(String flow, String userText) {
        return new Chain(flow, userText);
    }

    public final class Chain implements AutoCloseable {

        private final String flow;
        private final ConversationTrace trace;
        private final boolean owner;

        private Chain(String flow, String userText) {
            this.flow = flow;
            ConversationTrace current = TraceContext.current().orElse(null);
            this.owner = current == null;
            this.trace = owner ? TraceContext.begin() : current;
            if (properties.enabled() && owner) {
                log.info(RULE);
                log.info("[{}] USER > {}", trace.id(), Payloads.oneLine(userText, properties));
            }
        }

        public String id() {
            return trace.id();
        }

        /** One stage of the chain and what it produced. */
        public void stage(int number, String name, String outcome) {
            if (properties.enabled()) {
                log.info("[{}] CHAIN {}: stage {} {} > {}", trace.id(), flow, number, name,
                        Payloads.oneLine(outcome, properties));
            }
        }

        @Override
        public void close() {
            if (!owner) {
                return;
            }
            try {
                if (properties.enabled()) {
                    log.info("[{}] DONE in {}s | {} model call(s)", trace.id(),
                            String.format("%.2f", trace.elapsedSeconds()), trace.modelCalls());
                    log.info(RULE);
                }
            } finally {
                TraceContext.end();
            }
        }
    }
}
