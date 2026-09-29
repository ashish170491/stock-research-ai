package com.ashish.stockresearch.screening;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures a fundamentals snapshot of a universe, so that screening never calls a data provider.
 *
 * <ul>
 *   <li>Runs on weekdays after the market closes and the day's data has settled (18:30 India time by
 *       default, {@code app.screening.snapshot.cron}), and on demand through the admin endpoint.</li>
 *   <li>Throttled: every Yahoo request already goes through {@code YahooRequestThrottle}, and the job
 *       also pauses between companies ({@code pause-between-symbols}).</li>
 *   <li>A company that cannot be captured is recorded as a failure with its reason, and the run goes on.
 *       Only one run happens at a time.</li>
 * </ul>
 */
@Component
public class SnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotJob.class);
    static final String ZONE = "Asia/Kolkata";

    /** Waits between companies; replaced in tests. */
    @FunctionalInterface
    interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Universes universes;
    private final SnapshotBuilder builder;
    private final FundamentalsSnapshotRepository repository;
    private final Clock clock;
    private final Duration pauseBetweenSymbols;
    private final Pause pause;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public SnapshotJob(Universes universes, SnapshotBuilder builder, FundamentalsSnapshotRepository repository,
                       Clock clock, ScreeningProperties properties) {
        this(universes, builder, repository, clock, properties.snapshot().pauseBetweenSymbols(),
                duration -> Thread.sleep(duration));
    }

    SnapshotJob(Universes universes, SnapshotBuilder builder, FundamentalsSnapshotRepository repository, Clock clock,
                Duration pauseBetweenSymbols, Pause pause) {
        this.universes = universes;
        this.builder = builder;
        this.repository = repository;
        this.clock = clock;
        this.pauseBetweenSymbols = pauseBetweenSymbols;
        this.pause = pause;
    }

    @Scheduled(cron = "${app.screening.snapshot.cron:0 30 18 * * MON-FRI}", zone = ZONE)
    public void nightly() {
        for (Universe universe : Universe.values()) {
            begin(universe).ifPresentOrElse(run -> capture(run, universes.members(universe)),
                    () -> log.warn("Nightly snapshot of {} skipped: a snapshot run is already in progress", universe));
        }
    }

    /** Starts a run on a virtual thread and returns it at once; empty if a run is already in progress. */
    public Optional<SnapshotRun> startInBackground(Universe universe) {
        Optional<SnapshotRun> run = begin(universe);
        run.ifPresent(started -> Thread.ofVirtual().name("snapshot-" + started.id())
                .start(() -> capture(started, universes.members(universe))));
        return run;
    }

    /** Runs to completion on the calling thread; empty if a run is already in progress. */
    public Optional<SnapshotRun> runNow(Universe universe) {
        return begin(universe).map(run -> capture(run, universes.members(universe)));
    }

    public boolean running() {
        return running.get();
    }

    private Optional<SnapshotRun> begin(Universe universe) {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            LocalDate date = LocalDate.now(clock.withZone(ZoneId.of(ZONE)));
            return Optional.of(repository.startRun(universe, date, clock.instant(), universes.members(universe).size()));
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    private SnapshotRun capture(SnapshotRun run, List<Universes.Member> members) {
        int stored = 0;
        int failed = 0;
        SnapshotRun.Status status = SnapshotRun.Status.FAILED;
        long started = System.nanoTime();
        log.info("Snapshot run {} of {} started: {} companies", run.id(), run.universe(), members.size());
        try {
            for (int i = 0; i < members.size(); i++) {
                Universes.Member member = members.get(i);
                if (i > 0 && !pauseBetweenSymbols.isZero()) {
                    pause.sleep(pauseBetweenSymbols);
                }
                try {
                    repository.save(run.id(), builder.build(member, run.snapshotDate()));
                    stored++;
                } catch (RuntimeException ex) {
                    failed++;
                    String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    log.warn("Snapshot run {}: {} not captured: {}", run.id(), member.symbol(), reason);
                    repository.recordFailure(run.id(), member.symbol(), reason);
                }
            }
            status = stored > 0 ? SnapshotRun.Status.COMPLETED : SnapshotRun.Status.FAILED;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Snapshot run {} interrupted after {} companies", run.id(), stored + failed);
        } catch (RuntimeException ex) {
            log.error("Snapshot run {} stopped by an error after {} companies", run.id(), stored + failed, ex);
        } finally {
            try {
                repository.finishRun(run.id(), status, stored, failed, clock.instant());
            } finally {
                running.set(false);
            }
        }
        log.info("Snapshot run {} of {} {}: {} stored, {} failed, in {}s", run.id(), run.universe(), status, stored,
                failed, (System.nanoTime() - started) / 1_000_000_000);
        return new SnapshotRun(run.id(), run.universe(), run.snapshotDate(), run.startedAt(), clock.instant(), status,
                run.requested(), stored, failed);
    }
}
