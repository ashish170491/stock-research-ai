package com.ashish.stockresearch.trace;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProgressTest {

    private final List<Progress.Event> events = new CopyOnWriteArrayList<>();

    @Test
    void doesNothingWhenNoSinkIsOpen() {
        Progress.Step step = Progress.start("Fetching");
        step.done("Fetched");

        assertThat(Progress.step("Working", () -> 42)).isEqualTo(42);
        assertThat(events).isEmpty();
    }

    @Test
    void reportsEachStepWhenItStartsAndWhenItEnds() {
        try (Progress.Scope scope = Progress.open(events::add)) {
            Progress.Step understanding = Progress.start("Understanding your question");
            understanding.done("Understood: a research report on TCS");
            Progress.step("Writing the interpretation (local model)", () -> "text");
        }

        assertThat(events).extracting(Progress.Event::id, Progress.Event::status, Progress.Event::label).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, Progress.Status.STARTED, "Understanding your question"),
                org.assertj.core.groups.Tuple.tuple(1, Progress.Status.DONE, "Understood: a research report on TCS"),
                org.assertj.core.groups.Tuple.tuple(2, Progress.Status.STARTED, "Writing the interpretation (local model)"),
                org.assertj.core.groups.Tuple.tuple(2, Progress.Status.DONE, "Writing the interpretation (local model)"));
        assertThat(events.get(0).elapsedMillis()).isZero();
        // once the scope closes, nothing more is reported
        Progress.start("after").close();
        assertThat(events).hasSize(4);
    }

    @Test
    void aStepThatThrowsIsReportedAsFailedWithTheReason() {
        try (Progress.Scope scope = Progress.open(events::add)) {
            assertThatThrownBy(() -> Progress.step("Writing the summary (local model)", () -> {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not reach Ollama");
            })).isInstanceOf(ResponseStatusException.class);
        }

        assertThat(events).last().satisfies(event -> {
            assertThat(event.status()).isEqualTo(Progress.Status.FAILED);
            assertThat(event.label()).isEqualTo("Writing the summary (local model): Could not reach Ollama");
        });
    }

    @Test
    void endsAStepOnlyOnce() {
        try (Progress.Scope scope = Progress.open(events::add)) {
            Progress.Step step = Progress.start("Screening");
            step.done("Screened");
            step.close();
            step.failed("late");
        }

        assertThat(events).hasSize(2);
    }

    @Test
    void carriesTheSinkToWorkOnAnotherThread() throws Exception {
        try (Progress.Scope scope = Progress.open(events::add);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<String> a = CompletableFuture.supplyAsync(Progress.propagate(
                    () -> Progress.step("Fetching the company profile", () -> "a")), executor);
            CompletableFuture<String> b = CompletableFuture.supplyAsync(Progress.propagate(
                    () -> Progress.step("Fetching the valuation", () -> "b")), executor);
            assertThat(a.get() + b.get()).isEqualTo("ab");
        }

        assertThat(events).hasSize(4).extracting(Progress.Event::label)
                .contains("Fetching the company profile", "Fetching the valuation");
        // ids are unique across the threads of one request
        assertThat(events).extracting(Progress.Event::id).containsOnly(1, 2);
    }

    @Test
    void aSinkThatFailsNeverStopsTheWork() {
        try (Progress.Scope scope = Progress.open(event -> {
            throw new IllegalStateException("the reader has gone");
        })) {
            assertThat(Progress.step("Fetching", () -> "still done")).isEqualTo("still done");
        }
    }

    @Test
    void cutsALongFailureReasonToItsFirstSentence() {
        try (Progress.Scope scope = Progress.open(events::add)) {
            Progress.start("Fetching the valuation").failed("Yahoo Finance did not answer. It may be rate limiting; "
                    + "the application retries later.");
        }

        assertThat(events).last().extracting(Progress.Event::label)
                .isEqualTo("Fetching the valuation: Yahoo Finance did not answer.");
    }
}
