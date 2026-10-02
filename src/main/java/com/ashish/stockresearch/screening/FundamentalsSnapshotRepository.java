package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores snapshot runs, their per-company metrics, and the companies a run could not capture. */
@Repository
public class FundamentalsSnapshotRepository {

    private final JdbcClient jdbc;

    public FundamentalsSnapshotRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public SnapshotRun startRun(Universe universe, LocalDate snapshotDate, Instant startedAt, int requested) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO snapshot_run (universe, snapshot_date, started_at, status, symbols_requested)
                        VALUES (:universe, :date, :started, 'RUNNING', :requested)""")
                .param("universe", universe.name()).param("date", snapshotDate).param("started", timestamp(startedAt))
                .param("requested", requested)
                .update(key, "id");
        long id = key.getKey().longValue();
        return new SnapshotRun(id, universe, snapshotDate, startedAt, null, SnapshotRun.Status.RUNNING, requested, 0, 0);
    }

    public void finishRun(long runId, SnapshotRun.Status status, int stored, int failed, Instant finishedAt) {
        jdbc.sql("""
                        UPDATE snapshot_run SET status = :status, symbols_stored = :stored, symbols_failed = :failed,
                               finished_at = :finished WHERE id = :id""")
                .param("status", status.name()).param("stored", stored).param("failed", failed)
                .param("finished", timestamp(finishedAt)).param("id", runId)
                .update();
    }

    /** One company's snapshot and all its metrics, stored together or not at all. */
    @Transactional
    public void save(long runId, FundamentalsSnapshot snapshot) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO fundamentals_snapshot (run_id, symbol, company_name, industry_group,
                               classification_basis, snapshot_date, captured_at, filed_fiscal_years)
                        VALUES (:run, :symbol, :name, :group, :basis, :date, :captured, :filed)""")
                .param("run", runId).param("symbol", snapshot.symbol()).param("name", snapshot.companyName())
                .param("group", snapshot.industryGroup().name()).param("basis", snapshot.classificationBasis())
                .param("date", snapshot.snapshotDate()).param("captured", timestamp(snapshot.capturedAt()))
                .param("filed", snapshot.filedFiscalYears())
                .update(key, "id");
        long snapshotId = key.getKey().longValue();
        for (SnapshotMetric metric : snapshot.metrics().values()) {
            jdbc.sql("""
                            INSERT INTO snapshot_metric (snapshot_id, metric, metric_value, metric_unit, display,
                                   data_status, status_reason, period_label, source, calculation, unchecked_reason)
                            VALUES (:snapshot, :metric, :value, :unit, :display, :status, :reason, :period, :source,
                                    :calculation, :unchecked)""")
                    .param("snapshot", snapshotId).param("metric", metric.metric().name())
                    .param("value", metric.value()).param("unit", metric.unit() == null ? null : metric.unit().name())
                    .param("display", metric.display()).param("status", metric.status().name())
                    .param("reason", metric.statusReason()).param("period", metric.periodLabel())
                    .param("source", metric.source()).param("calculation", metric.calculation())
                    .param("unchecked", metric.uncheckedReason())
                    .update();
        }
    }

    public void recordFailure(long runId, String symbol, String reason) {
        jdbc.sql("INSERT INTO snapshot_failure (run_id, symbol, reason) VALUES (:run, :symbol, :reason)")
                .param("run", runId).param("symbol", symbol).param("reason", reason)
                .update();
    }

    /** The newest run of the universe that completed; a RUNNING or FAILED run is never screened. */
    public Optional<SnapshotRun> latestCompletedRun(Universe universe) {
        return jdbc.sql("""
                        SELECT * FROM snapshot_run WHERE universe = :universe AND status = 'COMPLETED'
                        ORDER BY finished_at DESC, id DESC FETCH FIRST 1 ROWS ONLY""")
                .param("universe", universe.name())
                .query(FundamentalsSnapshotRepository::run)
                .optional();
    }

    /** The newest run of the universe in any state, to report progress. */
    public Optional<SnapshotRun> latestRun(Universe universe) {
        return jdbc.sql("SELECT * FROM snapshot_run WHERE universe = :universe ORDER BY id DESC FETCH FIRST 1 ROWS ONLY")
                .param("universe", universe.name())
                .query(FundamentalsSnapshotRepository::run)
                .optional();
    }

    public List<FundamentalsSnapshot> snapshots(long runId) {
        Map<Long, FundamentalsSnapshot> byId = new LinkedHashMap<>();
        Map<Long, Map<ScreeningMetric, SnapshotMetric>> metricsById = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM fundamentals_snapshot WHERE run_id = :run ORDER BY symbol")
                .param("run", runId)
                .query(rs -> {
                    long id = rs.getLong("id");
                    metricsById.put(id, new EnumMap<>(ScreeningMetric.class));
                    byId.put(id, new FundamentalsSnapshot(rs.getString("symbol"), rs.getString("company_name"),
                            IndustryGroup.valueOf(rs.getString("industry_group")), rs.getString("classification_basis"),
                            rs.getObject("snapshot_date", LocalDate.class), instant(rs, "captured_at"), Map.of(),
                            rs.getObject("filed_fiscal_years", Integer.class)));
                });
        jdbc.sql("""
                        SELECT m.* FROM snapshot_metric m JOIN fundamentals_snapshot s ON s.id = m.snapshot_id
                        WHERE s.run_id = :run""")
                .param("run", runId)
                .query(rs -> {
                    Map<ScreeningMetric, SnapshotMetric> metrics = metricsById.get(rs.getLong("snapshot_id"));
                    SnapshotMetric metric = metric(rs);
                    if (metrics != null && metric != null) {
                        metrics.put(metric.metric(), metric);
                    }
                });
        List<FundamentalsSnapshot> snapshots = new ArrayList<>();
        byId.forEach((id, s) -> snapshots.add(new FundamentalsSnapshot(s.symbol(), s.companyName(), s.industryGroup(),
                s.classificationBasis(), s.snapshotDate(), s.capturedAt(), metricsById.get(id), s.filedFiscalYears())));
        return List.copyOf(snapshots);
    }

    /** How many of a run's stored companies have six fiscal years read from their NSE filings. */
    public SnapshotRun.FiledYears filedYears(long runId) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS stored,
                               COUNT(filed_fiscal_years) AS recorded,
                               COALESCE(SUM(CASE WHEN filed_fiscal_years >= 6 THEN 1 ELSE 0 END), 0) AS six
                        FROM fundamentals_snapshot WHERE run_id = :run""")
                .param("run", runId)
                .query((rs, row) -> new SnapshotRun.FiledYears(rs.getInt("stored"), rs.getInt("recorded"),
                        rs.getInt("six")))
                .single();
    }

    public List<SnapshotRun.Failure> failures(long runId) {
        return jdbc.sql("SELECT symbol, reason FROM snapshot_failure WHERE run_id = :run ORDER BY symbol")
                .param("run", runId)
                .query((rs, row) -> new SnapshotRun.Failure(rs.getString("symbol"), rs.getString("reason")))
                .list();
    }

    /** A metric this version no longer defines is skipped rather than guessed at. */
    private static SnapshotMetric metric(ResultSet rs) throws SQLException {
        ScreeningMetric metric;
        try {
            metric = ScreeningMetric.valueOf(rs.getString("metric"));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String unit = rs.getString("metric_unit");
        String unchecked = rs.getString("unchecked_reason");
        return new SnapshotMetric(metric, rs.getBigDecimal("metric_value"), unit == null ? null : Unit.valueOf(unit),
                rs.getString("display"), DataStatus.valueOf(rs.getString("data_status")), rs.getString("status_reason"),
                rs.getString("period_label"), rs.getString("source"), rs.getString("calculation"), unchecked);
    }

    private static SnapshotRun run(ResultSet rs, int row) throws SQLException {
        return new SnapshotRun(rs.getLong("id"), Universe.valueOf(rs.getString("universe")),
                rs.getObject("snapshot_date", LocalDate.class), instant(rs, "started_at"), instant(rs, "finished_at"),
                SnapshotRun.Status.valueOf(rs.getString("status")), rs.getInt("symbols_requested"),
                rs.getInt("symbols_stored"), rs.getInt("symbols_failed"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
