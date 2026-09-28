# Step 3: Deterministic screening engine

| ID | Type | Criterion |
| --- | --- | --- |
| S3-1 | AUTO | Package `com.ashish.stockresearch.screening` exists. A Nifty 50 universe CSV exists with 50 data rows. |
| S3-2 | CODE | A fundamentals snapshot is persisted (H2 file DB or Postgres) with the snapshot date, and each metric keeps its `DataStatus`. |
| S3-3 | CODE | `SnapshotJob` is `@Scheduled` with zone `Asia/Kolkata` (after market close), can be triggered by an admin endpoint, and is throttled. A failure for one symbol doesn't abort the run and is recorded. |
| S3-4 | CODE | `ScreeningCriteria` / `Rule(metric, operator, threshold)` records exist. Metrics are an enum or whitelist, not free strings. |
| S3-5 | CODE | `StockScreener` returns PASS / FAIL / INSUFFICIENT_DATA for every rule of every stock. Tests: `UNAVAILABLE`, `DATA_CONFLICT` and `INVALID` inputs each give INSUFFICIENT_DATA, never PASS. |
| S3-6 | CODE | `StockScreener` has no dependency on any Yahoo or market-data provider. It reads only the snapshot (check the constructor and imports). |
| S3-7 | CODE | Presets (`quality-compounder`, `reasonable-value`, `dividend`) are defined in `application.yml` and are industry-group aware. Test: bank presets never evaluate D/E or operating margin. |
| S3-8 | CODE | `screenStocks` `@Tool` returns a ranked table with per-rule results, and states the snapshot date and that it isn't a recommendation. |
| S3-9 | CODE | `EVIDENCE_RULES` allows "meets / does not meet the supplied criterion X" and still forbids unqualified labels. |
| S3-10 | CODE | Test: screening a 50-stock in-memory snapshot finishes in < 1 s. |
| S3-L1 | LIVE | After a snapshot run, `screenStocks(quality-compounder, NIFTY50)` returns in < 1 s, and its log shows no Yahoo calls. |
