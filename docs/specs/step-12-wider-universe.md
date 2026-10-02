# Step 12: A wider universe

| ID | Type | Criterion |
| --- | --- | --- |
| S12-1 | AUTO | `Universe.NIFTY500` exists, with the NSE Indices constituents CSV kept as downloaded, whose header comment names its source and as-of date. It has 500 members. A placeholder row (NSE Indices lists a "Dummy" company, ISIN starting `DUM`, while a demerger completes) is kept in the file but is not a member. |
| S12-2 | CODE | A Nifty 500 snapshot runs through the same `SnapshotJob`, within the Yahoo and NSE throttles. A filing already in `NseDocumentStore` is not downloaded again (test). A failure for one symbol is recorded and does not stop the run. A member is captured only from its own NSE listing: figures that resolve to another symbol or exchange are a recorded failure, never stored under the member (test). |
| S12-3 | CODE | `CriteriaValidator` accepts "Nifty 500" as a universe and no longer reports it as not screenable. A request that names no universe is screened on the Nifty 50. |
| S12-4 | CODE | Test: screening a 500-stock in-memory snapshot finishes in under 1 s. |
| S12-L1 | LIVE | A full Nifty 500 snapshot completes. The run records stored and failed counts, and the share of companies with six filed fiscal years is reported. |
