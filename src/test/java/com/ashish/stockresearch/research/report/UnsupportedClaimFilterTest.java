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
            "These trends suggest resilience in the stock's performance.",
            // Seen in real model output (INFY, HDFCBANK, TCS reports, 2026-09-27):
            "The sector context highlights that IT services prioritize revenue growth, EBIT margins, and returns, "
                    + "which TCS appears to meet.",
            "Its margins meet industry expectations.",
            "The stock underperformed the market.",
            "The available evidence suggests that Tata Consultancy Services Limited (TCS) has maintained relatively "
                    + "stable profitability and margin levels over the past few years.",
            "The available evidence suggests TCS has maintained stable revenue growth, with a 5.80% CAGR from FY23 "
                    + "to FY26.",
            "These price movements suggest volatility in investor sentiment and potential challenges in the "
                    + "company's stock performance.",
            "The share price, however, fell 37.54% over five years, with a peak-to-trough decline of 53.39%, "
                    + "suggesting market pressures or sector-specific challenges.",
            "The quarter-on-quarter revenue growth of 2.23% in Q1 FY27 contrasts with the slower net profit growth, "
                    + "raising questions about cost management or pricing pressures.",
            // Reliance Industries answer seen in the UI test, 2026-09-27:
            "The debt-to-equity ratio is 36.65%, indicating a moderate level of leverage.",
            "These figures suggest the company maintains a balanced capital structure, though the presence of "
                    + "DATA_CONFLICTs in other financial metrics highlights potential inconsistencies in the data sources."})
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

    /** Topics withheld in the Infosys report of 2026-09-27, whose financials conflict. */
    private static final java.util.List<String> INFY_WITHHELD = java.util.List.of("revenue growth",
            "net profit growth", "net profit margin", "operating margin", "return on equity", "return on assets");
    /** Topics withheld in the HDFC Bank report of 2026-09-27. */
    private static final java.util.List<String> HDFC_WITHHELD = java.util.List.of("revenue growth",
            "net profit growth", "net profit margin", "return on equity", "return on assets", "debt-to-equity");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            // Each of these is true, and each was removed from a real report before this was fixed.
            "As a result, no conclusions can be drawn about revenue growth, net profit growth, net profit margin, "
                    + "operating margin, return on equity, or return on assets.",
            "The return on capital employed (ROCE) is unavailable due to the lack of a capital-employed figure from "
                    + "Yahoo Finance.",
            "The data gaps indicate that the return on capital employed (ROCE) is unavailable due to the lack of a "
                    + "capital-employed figure from Yahoo Finance, which limits the ability to assess TCS's efficiency "
                    + "in generating returns from its capital.",
            "The market capitalisation of ₹4.05 lakh crore (₹4,05,115 crore) is in INR, while financials are in USD, "
                    + "creating a currency mismatch that prevents ratio analysis like P/E or price-to-sales.",
            "The share price has declined by -33.28% over five years, with a maximum drawdown of -48.17% (peak "
                    + "2024-12-13 to trough 2026-07-01), but these movements cannot be linked to underlying financial "
                    + "performance due to the lack of trustworthy revenue and profit data.",
            "These price movements are not tied to any specific financial metrics due to the lack of reliable "
                    + "underlying data."})
    void keepsTrueStatementsAboutTheDataGaps(String sentence) {
        UnsupportedClaimFilter.Result result = filter.filter(sentence, EVIDENCE, INFY_WITHHELD);

        assertThat(result.removedSentences()).isEmpty();
        assertThat(result.text()).isEqualTo(sentence);
    }

    @Test
    void stillRemovesABusinessCauseGivenAsALackOfSomething() {
        UnsupportedClaimFilter.Result result = filter.filter("Revenue fell due to the lack of new deals.", EVIDENCE);

        assertThat(result.text()).isEmpty();
    }

    @Test
    void removesAWithheldValueCalledValidEvenWhenTheSentenceMentionsTheConflict() {
        String text = "The reported ROE of 13.84% and ROA of 1.75% are valid as of Q1 FY27 but are not contextualized "
                + "by conflicting revenue data. The reported total debt of ₹5.66 lakh crore and total cash of ₹2.38 "
                + "lakh crore as of 2026-06-30 are valid but lack context from conflicting financial data. "
                + "TTM revenue figures differ by 59.95% between sources.";

        UnsupportedClaimFilter.Result result = filter.filter(text, EVIDENCE, HDFC_WITHHELD);

        assertThat(result.text()).isEqualTo("TTM revenue figures differ by 59.95% between sources.");
        assertThat(result.removedSentences()).hasSize(2);
    }

    @Test
    void keepsAWithheldValueQuotedOnlyToSayItCannotBeInterpreted() {
        String text = "The reported return on equity (32.00%) and return on assets (15.30%) are provided, but these "
                + "metrics cannot be interpreted without validated underlying data on revenue and net profit.";

        assertThat(filter.filter(text, EVIDENCE, INFY_WITHHELD).text()).isEqualTo(text);
    }

    @Test
    void keepsAdviceToFindOutWhatAMoveReflects() {
        String text = "Readers should check whether the decline reflects investor sentiment or sector-specific "
                + "headwinds.";

        assertThat(filter.filter(text, EVIDENCE).text()).isEqualTo(text);
    }

    @Test
    void dropsTheConnectiveThatTiedAKeptSentenceToARemovedOne() {
        String text = "The available evidence suggests that HDFC Bank's reported net profit for the TTM to Q1 FY27 "
                + "period is ₹79,012.77 crore, with a quarterly earnings growth of 18.10% in Q1 FY27. However, key "
                + "metrics like revenue and return on assets (ROA) are withheld due to DATA_CONFLICTs between "
                + "conflicting data sources.";

        UnsupportedClaimFilter.Result result = filter.filter(text, EVIDENCE, HDFC_WITHHELD);

        assertThat(result.text()).isEqualTo("Key metrics like revenue and return on assets (ROA) are withheld due to "
                + "DATA_CONFLICTs between conflicting data sources.");
        assertThat(result.removedSentences()).hasSize(1);
    }

    @Test
    void removesASentenceAboutTheFiguresOfARemovedSentence() {
        String text = "The reported net profit for TTM to Q1 FY27 is ₹79,012.77 crore, with a return on equity (ROE) "
                + "of 13.84%. However, these figures depend on unresolved data conflicts, so their reliability is "
                + "uncertain. Share-price performance shows a total return of -3.26% over five years.";

        UnsupportedClaimFilter.Result result = filter.filter(text, EVIDENCE, HDFC_WITHHELD);

        assertThat(result.text()).isEqualTo("Share-price performance shows a total return of -3.26% over five years.");
        assertThat(result.removedSentences()).hasSize(2);
    }

    @Test
    void leavesConnectivesAloneWhenNothingWasRemoved() {
        String text = "Revenue data conflicts. However, share-price data is complete. These figures cover five years.";

        assertThat(filter.filter(text, EVIDENCE).text()).isEqualTo(text);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "At 26.74x, the stock looks cheap.",
            "TCS appears undervalued at current levels.",
            "The shares are overvalued relative to earnings.",
            "It trades at a premium to peers.",
            "The low P/E suggests value.",
            "A high price-to-book multiple limits upside.",
            "The fair value is ₹1,800 per share.",
            "The dividend yield offers a margin of safety."})
    void removesValuationJudgementsForWhichNoBenchmarkFairValueOrTargetExists(String sentence) {
        UnsupportedClaimFilter.Result result = filter.filter("The trailing P/E was 26.74x as of 2026-09-25. " + sentence,
                EVIDENCE);

        assertThat(result.text()).isEqualTo("The trailing P/E was 26.74x as of 2026-09-25.");
    }

    @Test
    void keepsValuationFactsStatedWithTheirDates() {
        String text = "The trailing P/E was 26.74x and price-to-book 4.63x as of 2026-09-25. "
                + "Earnings yield, calculated as 100 / P/E, was 3.74%.";

        assertThat(filter.filter(text, EVIDENCE).text()).isEqualTo(text);
    }

    @Test
    void removesConclusionsAboutWithheldCashConversionAndRoce() {
        UnsupportedClaimFilter.Result result = filter.filter("Operating cash flow rose to 1.61x net profit. "
                        + "ROCE improved to 18.20% in FY26. Cash conversion was withheld for this bank.", EVIDENCE,
                java.util.List.of("cash conversion", "return on capital employed"));

        assertThat(result.text()).isEqualTo("Cash conversion was withheld for this bank.");
    }

    // Sentences from real answers to "What is PE of TCS?" and "Is HDFC cheap at current valuation?", which
    // declined the judgement and were wrongly removed, leaving a dangling "Without such benchmarks, ...".
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "This ratio alone does not indicate whether the stock is overvalued or undervalued without comparative "
                    + "benchmarks.",
            "- No industry, sector, or historical benchmark is provided to contextualize whether the P/E or "
                    + "price-to-book ratios are \"cheap\" or \"expensive.\"",
            "- The tool does not supply data on peer comparisons, fair value estimates, or long-term valuation trends.",
            "The available data does not include benchmarks to determine if HDFC’s current valuation is \"cheap.\"",
            "The trailing P/E and price-to-book ratios are factual metrics as of 2026-09-25 but require external "
                    + "context (e.g., sector averages, historical ranges, or peer comparisons) to assess relative value.",
            "No fair value or price target is supplied.",
            "The data cannot show whether TCS is undervalued.",
            // from "Is ICICIBANK cheap at current valuation?"
            "- UNAVAILABLE: Historical P/E and industry benchmarks are not provided in the data.",
            "A fair value or price target is not supplied."})
    void keepsSentencesThatDeclineAValuationJudgement(String sentence) {
        UnsupportedClaimFilter.Result result = filter.filter(sentence, EVIDENCE);

        assertThat(result.removedSentences()).isEmpty();
        assertThat(result.text()).isEqualTo(sentence);
    }

    @Test
    void splitsSentencesAfterAClosingQuote() {
        String text = "The data does not include benchmarks to determine if it is \"cheap.\" The P/E is 16.08x.";

        assertThat(filter.filter(text, EVIDENCE).text()).isEqualTo(text);
    }

    // A refusal's wording wrapped round a judgement that is still made.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Without benchmarks, the stock looks cheap.",
            "There is no fair value estimate; the stock is undervalued.",
            "The data does not include a price target, but the shares are expensive.",
            "The stock is by no means cheap.",
            "It is not cheap.",
            "Even if there is no benchmark, the stock is undervalued.",
            "The data lacks benchmarks and the P/E of 15.13x is low.",
            "The data does not show whether it is undervalued, though it probably is.",
            "Without a price target, TCS still trades at a discount to peers.",
            "No benchmark is supplied, but the P/E is reasonable.",
            "The earnings yield of 7.75% suggests a balance between growth potential and income generation.",
            "The P/E is not supplied with a benchmark, yet the stock is attractive.",
            "The stock is cheap; industry benchmarks are not provided.",
            "The fair value is ₹1,800, though a price target is not supplied.",
            "Sector averages show the stock is expensive, and peer data is not provided."})
    void stillRemovesJudgementsWrappedInARefusal(String sentence) {
        UnsupportedClaimFilter.Result result = filter.filter("The trailing P/E was 15.13x as of 2026-09-25. " + sentence,
                EVIDENCE);

        assertThat(result.text()).isEqualTo("The trailing P/E was 15.13x as of 2026-09-25.");
    }
}
