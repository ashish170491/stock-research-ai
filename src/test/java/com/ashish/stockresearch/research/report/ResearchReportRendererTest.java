package com.ashish.stockresearch.research.report;

import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchReportRendererTest {

    private final ResearchReportRenderer renderer = new ResearchReportRenderer();
    private final StockResearchReport report = ResearchReportServiceTest.hdfcReport(new FinancialDataValidator(
            Clock.fixed(Instant.parse("2026-09-26T06:00:00Z"), ZoneId.of("Asia/Kolkata"))));

    @Test
    void separatesFactsObservationsGapsInterpretationAndSourcesInThatOrder() {
        String md = renderer.render(report, "This may indicate steady growth.");

        int facts = md.indexOf("## 1. FACTS");
        int observations = md.indexOf("## 2. OBSERVATIONS");
        int gaps = md.indexOf("## 3. RISKS / DATA GAPS");
        int interpretation = md.indexOf("## 4. INTERPRETATION");
        int sources = md.indexOf("## 5. SOURCES");
        assertThat(facts).isPositive();
        assertThat(observations).isGreaterThan(facts);
        assertThat(gaps).isGreaterThan(observations);
        assertThat(interpretation).isGreaterThan(gaps);
        assertThat(sources).isGreaterThan(interpretation);
        assertThat(md.substring(interpretation, sources))
                .contains("interpretation, not fact")
                .contains("This may indicate steady growth.");
    }

    @Test
    void rendersNormalisedValuesWithPeriodsAndLatestQuarter() {
        String md = renderer.render(report, "x");

        assertThat(md).contains("Latest reported quarter: **Q1 FY27** (period ended 2026-06-30)");
        assertThat(md).contains("| revenue | ₹2.95 lakh crore (₹2,94,964 crore) [DATA_CONFLICT] | TTM to Q1 FY27 (ending 2026-06-30) "
                + "| DATA_CONFLICT | 2949644300000.00 (WHOLE_CURRENCY_UNITS) | financialData.totalRevenue |");
        assertThat(md).contains("| netProfit | ₹79,012.77 crore | TTM to Q1 FY27 (ending 2026-06-30) | REPORTED |");
        assertThat(md).contains("| Q1 FY27 | 2026-06-30 |");
        assertThat(md).contains("2026 (YTD, partial)");
    }

    @Test
    void statesMissingDataAsUnavailableRatherThanOmittingIt() {
        String md = renderer.render(report, "x");

        assertThat(md).contains("Shareholding data was not available from the configured provider.");
        assertThat(md).contains("| ebitda | UNAVAILABLE - Yahoo Finance did not publish financialData.ebitda");
    }

    @Test
    void givesTheModelEvidenceWithoutAnInterpretationSection() {
        String evidence = renderer.renderEvidence(report);

        assertThat(evidence).contains("OBSERVATIONS").contains("DATA GAPS AND DATA-QUALITY ISSUES")
                .doesNotContain("INTERPRETATION");
    }

    @Test
    void reportsConflictsProminentlyBeforeTheFactsAndListsWithheldObservations() {
        String md = renderer.render(report, "x");

        assertThat(md.indexOf("DATA CONFLICT - read before the figures below")).isPositive()
                .isLessThan(md.indexOf("## 1. FACTS"));
        assertThat(md).contains("Not generated - the metric is in a DATA_CONFLICT")
                .contains("- Revenue (FY26 vs the prior fiscal year): **DATA_CONFLICT** - YoY revenue growth not calculated")
                .doesNotContain("Revenue rose 4.73%");
        // The calculated table shows only why there is no value - never a value computed from disputed inputs.
        assertThat(md).contains("| FY26 | DATA_CONFLICT | 4.65% | DATA_CONFLICT | n/a | 8.90% | 1.40% | 0.81x |");
        // The interpretation section says which conclusions were withheld, before any model text.
        String interpretation = md.substring(md.indexOf("## 4. INTERPRETATION"), md.indexOf("## 5. SOURCES"));
        assertThat(interpretation).contains("**Conclusions withheld.**").contains("revenue growth")
                .contains("net profit margin");
    }

    @Test
    void attributesDataToYahooFinanceAndNeverToFilings() {
        String md = renderer.render(report, "x");

        assertThat(md).contains("Yahoo Finance [MARKET_DATA_PROVIDER]").doesNotContainIgnoringCase("filing");
    }
}
