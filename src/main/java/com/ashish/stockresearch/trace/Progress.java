package com.ashish.stockresearch.trace;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The steps a chat request goes through, reported as they happen so a person waiting for an answer can see
 * what the application is doing - "Fetching results filed with NSE", "Writing the interpretation (local
 * model)". A request opens a {@link Sink} for its thread ({@link #open}); code anywhere below it reports
 * steps with {@link #start}. With no sink open, every call does nothing.
 *
 * <p>A step names what is being done, for which company and from which source - never a figure. Figures
 * reach a reader only in the checked answer.
 *
 * <p>The sink belongs to the thread that opened it. Work handed to another thread carries it with
 * {@link #propagate}.
 */
public final class Progress {

    /** Where a request's steps go, e.g. a server-sent event stream. Must accept events from several threads. */
    @FunctionalInterface
    public interface Sink {
        void event(Event event);
    }

    public enum Status { STARTED, DONE, FAILED }

    /**
     * @param id            the step's number within the request; its STARTED and DONE events share it
     * @param label         what is being done, or (when DONE) what was done
     * @param elapsedMillis how long the step took; 0 when STARTED
     */
    public record Event(int id, Status status, String label, long elapsedMillis) {
    }

    private record Context(Sink sink, AtomicInteger ids) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private Progress() {
    }

    /** Sends this thread's steps to {@code sink} until the returned scope is closed. */
    public static Scope open(Sink sink) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(sink, new AtomicInteger()));
        return () -> restore(previous);
    }

    /** Starts a step; close it, or call {@link Step#done(String)} or {@link Step#failed(String)}, when it ends. */
    public static Step start(String label) {
        Context context = CURRENT.get();
        if (context == null) {
            return Step.NONE;
        }
        Step step = new Step(context, context.ids().incrementAndGet(), label);
        step.send(Status.STARTED, label, 0);
        return step;
    }

    /** Runs {@code work} as one step: done when it returns, failed when it throws. */
    public static <T> T step(String label, Supplier<T> work) {
        Step step = start(label);
        try {
            T result = work.get();
            step.close();
            return result;
        } catch (RuntimeException ex) {
            step.failed(ex instanceof org.springframework.web.server.ResponseStatusException status
                    && status.getReason() != null ? status.getReason() : "it did not complete");
            throw ex;
        }
    }

    /** {@code work}, carrying this thread's sink to whichever thread runs it. */
    public static <T> Supplier<T> propagate(Supplier<T> work) {
        Context context = CURRENT.get();
        if (context == null) {
            return work;
        }
        return () -> {
            Context previous = CURRENT.get();
            CURRENT.set(context);
            try {
                return work.get();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(Context previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** Closes an open sink. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** A step in progress. Ending it more than once sends nothing more. */
    public static final class Step implements AutoCloseable {

        static final Step NONE = new Step(null, 0, null);

        private final Context context;
        private final int id;
        private final String label;
        private final long started = System.nanoTime();
        private boolean ended;

        private Step(Context context, int id, String label) {
            this.context = context;
            this.id = id;
            this.label = label;
            this.ended = context == null;
        }

        /** Ends the step, saying what was done ("Understood: a research report on HDFCBANK"). */
        public void done(String doneLabel) {
            end(Status.DONE, doneLabel == null ? label : doneLabel);
        }

        /** Ends the step as failed; a long reason is cut to its first sentence, the answer gives it in full. */
        public void failed(String reason) {
            end(Status.FAILED, reason == null || reason.isBlank() ? label : label + ": " + firstSentence(reason));
        }

        private static String firstSentence(String reason) {
            String text = reason.strip();
            int end = text.indexOf(". ");
            text = end > 0 ? text.substring(0, end + 1) : text;
            return text.length() <= 140 ? text : text.substring(0, 139) + "…";
        }

        /** Ends the step as done, under its own label, unless it has already ended. */
        @Override
        public void close() {
            end(Status.DONE, label);
        }

        private void end(Status status, String text) {
            if (ended) {
                return;
            }
            ended = true;
            send(status, text, (System.nanoTime() - started) / 1_000_000);
        }

        private void send(Status status, String text, long elapsedMillis) {
            try {
                context.sink().event(new Event(id, status, text, elapsedMillis));
            } catch (RuntimeException ignored) {
                // a reader that has gone away never stops the request
            }
        }
    }
}
