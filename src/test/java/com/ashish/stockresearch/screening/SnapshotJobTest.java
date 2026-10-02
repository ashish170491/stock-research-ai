package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// S3-3
class SnapshotJobTest {

    private static final Clock EVENING = Clock.fixed(Instant.parse("2026-09-28T13:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private final EmbeddedDatabase database = SnapshotDatabase.create();
    private final FundamentalsSnapshotRepository repository = new FundamentalsSnapshotRepository(database);
    private final SnapshotBuilder builder = mock(SnapshotBuilder.class);
    private final List<Duration> pauses = new ArrayList<>();

    private static final List<Universes.Member> MEMBERS = List.of(new Universes.Member("TCS", "TCS Ltd.", "IT"),
            new Universes.Member("BROKEN", "Broken Ltd.", "IT"), new Universes.Member("INFY", "Infosys Ltd.", "IT"));

    private final Universes universes = new Universes() {
        @Override
        public synchronized List<Universes.Member> members(Universe universe) {
            return MEMBERS;
        }
    };

    private SnapshotJob job(Duration pause) {
        return new SnapshotJob(universes, builder, repository, EVENING, pause, pauses::add);
    }

    @AfterEach
    void close() {
        database.shutdown();
    }

    private static FundamentalsSnapshot snapshot(String symbol) {
        return ScreeningFixtures.stock(symbol, IndustryGroup.IT_SERVICES).with(ScreeningMetric.ROE_PERCENT, "20").build();
    }

    @Test
    void runsAfterMarketCloseInIndiaTime() throws NoSuchMethodException {
        Scheduled scheduled = SnapshotJob.class.getMethod("nightly").getAnnotation(Scheduled.class);

        assertThat(scheduled.zone()).isEqualTo("Asia/Kolkata");
        assertThat(scheduled.cron()).isEqualTo("${app.screening.snapshot.cron:0 30 18 * * MON-FRI}");
    }

    // S12-2: the whole Nifty 500 through the same job, one failure recorded and the run carried on
    @Test
    void capturesTheNifty500ThroughTheSameJob() {
        Universes real = new Universes();
        when(builder.build(any(), any())).thenAnswer(call -> {
            Universes.Member member = call.getArgument(0);
            if (member.symbol().equals("EMBASSY")) {
                throw new SnapshotBuilder.NothingRetrievedException("the profile resolved to EMBASSY (BSE), not "
                        + "EMBASSY's own NSE listing, so nothing was stored");
            }
            return ScreeningFixtures.stock(member.symbol(), IndustryGroup.OTHER).filedFiscalYears(6).build();
        });

        SnapshotRun run = new SnapshotJob(real, builder, repository, EVENING, Duration.ofSeconds(1), pauses::add)
                .runNow(Universe.NIFTY500).orElseThrow();

        assertThat(run.universe()).isEqualTo(Universe.NIFTY500);
        assertThat(run.requested()).isEqualTo(500);
        assertThat(run.stored()).isEqualTo(499);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(repository.failures(run.id())).extracting(SnapshotRun.Failure::symbol).containsExactly("EMBASSY");
        assertThat(repository.filedYears(run.id())).isEqualTo(new SnapshotRun.FiledYears(499, 499, 499));
        // the pause between companies applies to every company after the first
        assertThat(pauses).hasSize(499);
        assertThat(repository.latestCompletedRun(Universe.NIFTY500)).contains(run);
        assertThat(repository.latestCompletedRun(Universe.NIFTY50)).isEmpty();
    }

    @Test
    void recordsACompanyThatFailsAndCarriesOnWithTheRest() {
        when(builder.build(argThat(m -> m != null && m.symbol().equals("TCS")), any())).thenReturn(snapshot("TCS"));
        when(builder.build(argThat(m -> m != null && m.symbol().equals("BROKEN")), any()))
                .thenThrow(new SnapshotBuilder.NothingRetrievedException("profile: not found"));
        when(builder.build(argThat(m -> m != null && m.symbol().equals("INFY")), any())).thenReturn(snapshot("INFY"));

        SnapshotRun run = job(Duration.ofMillis(500)).runNow(Universe.NIFTY50).orElseThrow();

        assertThat(run.status()).isEqualTo(SnapshotRun.Status.COMPLETED);
        assertThat(run.stored()).isEqualTo(2);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.snapshotDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(repository.snapshots(run.id())).extracting(FundamentalsSnapshot::symbol).containsExactly("INFY", "TCS");
        assertThat(repository.failures(run.id())).containsExactly(new SnapshotRun.Failure("BROKEN", "profile: not found"));
        assertThat(repository.latestCompletedRun(Universe.NIFTY50)).map(SnapshotRun::id).contains(run.id());
    }

    @Test
    void pausesBetweenCompanies() {
        when(builder.build(any(), any())).thenAnswer(call -> snapshot(call.<Universes.Member>getArgument(0).symbol()));

        job(Duration.ofMillis(500)).runNow(Universe.NIFTY50);

        assertThat(pauses).containsExactly(Duration.ofMillis(500), Duration.ofMillis(500));
    }

    @Test
    void aRunThatCapturesNothingIsFailedAndNeverScreened() {
        when(builder.build(any(), any())).thenThrow(new SnapshotBuilder.NothingRetrievedException("Yahoo down"));

        SnapshotRun run = job(Duration.ZERO).runNow(Universe.NIFTY50).orElseThrow();

        assertThat(run.status()).isEqualTo(SnapshotRun.Status.FAILED);
        assertThat(repository.failures(run.id())).hasSize(3);
        assertThat(repository.latestCompletedRun(Universe.NIFTY50)).isEmpty();
    }

    @Test
    void runsOneSnapshotAtATime() throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(builder.build(any(), any())).thenAnswer(call -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return snapshot(call.<Universes.Member>getArgument(0).symbol());
        });
        SnapshotJob job = job(Duration.ZERO);

        assertThat(job.startInBackground(Universe.NIFTY50)).isPresent();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(job.running()).isTrue();
        assertThat(job.startInBackground(Universe.NIFTY50)).isEmpty();
        assertThat(job.runNow(Universe.NIFTY50)).isEmpty();
        release.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (job.running() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(job.running()).isFalse();
        assertThat(repository.latestCompletedRun(Universe.NIFTY50)).hasValueSatisfying(run ->
                assertThat(run.stored()).isEqualTo(3));
    }
}
