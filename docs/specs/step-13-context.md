# Step 13: Context beside each ratio

| ID | Type | Criterion |
| --- | --- | --- |
| S13-1 | CODE | `PeerContext` gives, for a metric and an industry group in the latest snapshot, the count of peers with a usable value, their median, minimum and maximum, and the company's rank. It is calculated in `FinancialCalculator`, verified by `CalculationVerifier`, and carries the snapshot date and the peer symbols. Only usable (VALID, checked) values count. |
| S13-2 | CODE | Fewer usable peers than the configured minimum (default 5) gives UNAVAILABLE with the count. Test: an industry group of two insurers has no peer statistic. |
| S13-3 | CODE | No peer context is calculated for a metric that is not primary for the group. Test: a bank's ROCE has none. |
| S13-4 | CODE | Own history gives the company's value for each filed fiscal year, their range and the latest year's place in it. A year that is not VALID is shown with its status and left out of the range. |
| S13-5 | CODE | Context is rendered in neutral words only. Test: rendered context is free of "good", "bad", "strong", "weak", "cheap", "expensive", "attractive", "healthy", "better", "worse". |
| S13-6 | CODE | Every peer or history figure in a model's answer passes `NumericClaimVerifier` against the context, and the snapshot date and peer count are stated beside every peer figure. |
| S13-L1 | LIVE | The HDFC Bank research report shows its ROE with its own filed years' range and its bank peers' median and range, with the snapshot date and the peer count. |
