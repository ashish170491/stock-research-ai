package com.ashish.stockresearch.research.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnsupportedClaimFilterTest {

    private final UnsupportedClaimFilter filter = new UnsupportedClaimFilter();
    private static final String EVIDENCE = "HDFC Bank Limited provides banking and financial services. Revenue rose 4.73%.";

    @Test
    void removesAMarketPositionClaimTheEvidenceDoesNotSupport() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "Revenue rose 4.73% in FY26. The bank dominates regional banking. Margins held steady.", EVIDENCE);

        assertThat(result.text()).isEqualTo("Revenue rose 4.73% in FY26. Margins held steady.");
        assertThat(result.removedSentences()).containsExactly("The bank dominates regional banking.");
    }

    @Test
    void removesSuperlativesSuppliedFromMemory() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "- HDFC Bank is India's largest private sector bank.\n- Growth slowed in FY26.", EVIDENCE);

        assertThat(result.text()).isEqualTo("- Growth slowed in FY26.");
        assertThat(result.removedSentences()).hasSize(1);
    }

    @Test
    void keepsAClaimWhoseKeyPhraseAppearsInTheEvidence() {
        UnsupportedClaimFilter.Result result = filter.filter("It describes itself as the largest lender.",
                "The company is the largest lender in its segment.");

        assertThat(result.removedSentences()).isEmpty();
    }

    @Test
    void leavesOrdinaryAnalyticalLanguageAlone() {
        String text = "This suggests growth is leading to higher profits. Data gaps limit confidence.";

        assertThat(filter.filter(text, EVIDENCE).text()).isEqualTo(text);
    }

    @Test
    void removesComparisonsWithBenchmarksTheEvidenceDoesNotContain() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "Return on assets was 1.40% in FY26. This appears low relative to industry benchmarks. "
                        + "Margins are higher than peers. Growth lagged the sector average.", EVIDENCE);

        assertThat(result.text()).isEqualTo("Return on assets was 1.40% in FY26.");
        assertThat(result.removedSentences()).hasSize(3);
    }

    @Test
    void removesAccelerationWordingBecauseTheApplicationNeverCalculatesAcceleration() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "Revenue grew at a compound annual rate of 3.00%. Earnings growth accelerated in FY26. "
                        + "Growth is decelerating. The stock has strong momentum.",
                // Even when the evidence happens to contain the word (a product name in a business summary).
                "It offers a software development lifecycle acceleration platform.");

        assertThat(result.text()).isEqualTo("Revenue grew at a compound annual rate of 3.00%.");
        assertThat(result.removedSentences()).hasSize(3);
    }

    @Test
    void removesLeverageLabelsThatHaveNoSupportingCriteria() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "Debt-to-equity was 7.27%. This indicates high leverage. The company is highly leveraged. "
                        + "Low leverage gives flexibility.", EVIDENCE);

        assertThat(result.text()).isEqualTo("Debt-to-equity was 7.27%.");
    }

    @Test
    void removesSourceUpgradesToFilingsOrExchangeData() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "According to company filings, revenue rose. Exchange data confirms this. Yahoo Finance reports "
                        + "revenue of ₹2.95 lakh crore.", "Source: Yahoo Finance");

        assertThat(result.text()).isEqualTo("Yahoo Finance reports revenue of ₹2.95 lakh crore.");
    }

    @Test
    void removesUnsupportedEvaluativeLabels() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "Return on equity was 16.89% in FY26, reflecting efficient capital use. "
                        + "Debt-to-equity of 0.07x indicates a conservative capital structure. "
                        + "These figures reflect modest profitability. The balance sheet looks healthy. "
                        + "Net profit rose 13.16% in FY26.", EVIDENCE);

        assertThat(result.text()).isEqualTo("Net profit rose 13.16% in FY26.");
        assertThat(result.removedSentences()).hasSize(4);
    }

    @Test
    void removesCausalExplanationsTheDataDoesNotEstablish() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "Operating margin increased to 13.00%, suggesting better cost control or pricing power. "
                        + "Margins fell due to rising costs. Growth was driven by digital deals. "
                        + "Operating margin increased. The available data does not establish the specific cause.",
                EVIDENCE);

        assertThat(result.text())
                .isEqualTo("Operating margin increased. The available data does not establish the specific cause.");
    }

    @Test
    void keepsConnectivesThatExplainADataProblemRatherThanABusinessCause() {
        String text = "The revenue figures differ due to different definitions used by the provider. "
                + "Confidence is limited because of missing cash-flow data.";

        assertThat(filter.filter(text, EVIDENCE).text()).isEqualTo(text);
    }

    @Test
    void keepsAdviceToCheckFilingsWhileRemovingAttributionToThem() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "Readers should verify these figures against the company's regulatory filings. "
                        + "Company filings show revenue of ₹2.95 lakh crore.", EVIDENCE);

        assertThat(result.text()).isEqualTo("Readers should verify these figures against the company's regulatory filings.");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "The company appears financially healthy.",
            "Revenue growth was strong.",
            "Margins look weak.",
            "The bank has high leverage.",
            "Low leverage reduces risk.",
            "Execution has been excellent.",
            "The valuation is attractive.",
            "Cost control was poor.",
            "The stock shows strong momentum.",
            "Total debt of 0.81x indicates a relatively stable capital structure.",
            "The bank is a major player in India.",
            "The market cap indicates significant scale.",
            "Revenue and net profit have grown steadily.",
            "Return on equity remained stable at 16.69% in FY26.",
            "These trends suggest resilience in the stock's performance."})
    void removesQualitativeLabelsForWhichNoCriteriaExist(String sentence) {
        UnsupportedClaimFilter.Result result = filter.filter("The reported ROE was 16.89% for FY26. " + sentence, EVIDENCE);

        assertThat(result.text()).isEqualTo("The reported ROE was 16.89% for FY26.");
    }

    @Test
    void removesConclusionsAboutWithheldTopicsButKeepsStatementsAboutTheConflict() {
        String text = "Revenue grew 4.73% in FY26. Revenue figures from two Yahoo Finance fields conflict, so no "
                + "revenue trend can be stated. Net profit margin remained stable. Share price fell 3.26% over the window.";

        UnsupportedClaimFilter.Result result = filter.filter(text, EVIDENCE,
                java.util.List.of("revenue growth", "net profit margin"));

        assertThat(result.text()).isEqualTo("Revenue figures from two Yahoo Finance fields conflict, so no revenue "
                + "trend can be stated. Share price fell 3.26% over the window.");
        assertThat(result.removedSentences()).containsExactly("Revenue grew 4.73% in FY26.",
                "Net profit margin remained stable.");
    }

    @Test
    void aCompanyNameEndingInLimitedDoesNotExemptAConclusion() {
        UnsupportedClaimFilter.Result result = filter.filter(
                "HDFC Bank Limited reported a 4.65% increase in net profit for FY26.", EVIDENCE,
                java.util.List.of("net profit growth"));

        assertThat(result.text()).isEmpty();
    }
}
