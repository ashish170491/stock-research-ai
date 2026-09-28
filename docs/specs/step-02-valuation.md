# Step 2: Valuation and Indian-market quality metrics

| ID | Type | Criterion |
| --- | --- | --- |
| S2-1 | CODE | The Yahoo client requests the `summaryDetail` and `defaultKeyStatistics` modules. Fixture JSON for at least one company includes them. |
| S2-2 | CODE | A `ValuationSnapshot` holds trailing P/E, P/B, dividend yield and earnings yield as `FinancialDataPoint`s with as-of dates and units (MULTIPLE, PERCENT). |
| S2-3 | CODE | Earnings yield is calculated in `FinancialCalculator` with its formula and inputs recorded, and reproduced by `CalculationVerifier` (test). |
| S2-4 | CODE | P/E is cross-checked against price ÷ EPS. A mismatch over the configured tolerance marks P/E `DATA_CONFLICT` (test with a conflicting fixture). |
| S2-5 | CODE | `getValuation` `@Tool` exists. Its description says the values are point-in-time and that it gives no fair value, target or recommendation. It records `ToolUsage` and returns compact rendered text. |
| S2-6 | CODE | OCF/PAT (3y and 5y) and ROCE are calculated in `FinancialCalculator` with verifier tests, including a case where missing years make the result UNAVAILABLE, not partial. |
| S2-7 | CODE | `SectorClassifier` marks OCF/PAT and ROCE as not primary for BANK, NBFC and INSURANCE. Test proves no observation is generated from them for a bank. |
| S2-8 | CODE | The research report includes valuation facts, and valuation topics appear in data gaps when unavailable. |
| S2-L1 | LIVE | "What is the P/E of Infosys?" answers with a figure present in the tool evidence, and no "Removed N statement(s)" note. |
