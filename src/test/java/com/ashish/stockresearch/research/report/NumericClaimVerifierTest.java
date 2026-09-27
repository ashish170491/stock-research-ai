package com.ashish.stockresearch.research.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NumericClaimVerifierTest {

    private static final String EVIDENCE = """
            | revenue | ₹59,176.10 crore | TTM to Q1 FY27 (ending 2026-06-30) | REPORTED |
            | FY26 | 7.22% | 13.16% | 8.47% | 13.00% | 16.89% | 10.25% | 0.07x |
            | marketCap | ₹11.34 lakh crore (₹11,34,183 crore) |
            | revenue | US$20299.00 million |
            | maxDrawdownPercent | -31.04% |
            """;

    private final NumericClaimVerifier verifier = new NumericClaimVerifier();

    @Test
    void keepsFiguresTakenFromTheEvidence() {
        String answer = "TTM revenue was ₹59,176.10 crore. FY26 ROE was 16.89% and debt/equity 0.07x. "
                + "Market cap was ₹11.34 lakh crore. Revenue was US$20299.00 million. The worst fall was 31.04%.";

        NumericClaimVerifier.Result result = verifier.verify(answer, EVIDENCE);

        assertThat(result.removedStatements()).isEmpty();
        assertThat(result.text()).isEqualTo(answer);
    }

    @Test
    void acceptsRoundingToFewerDecimalsButNotToAWholeNumber() {
        assertThat(verifier.verify("Operating margin was 13.0% and ROA 10.3%.", EVIDENCE).removedStatements()).isEmpty();
        assertThat(verifier.verify("ROE was about 17%.", EVIDENCE).ungroundedFigures()).containsExactly("17");
    }

    @Test
    void removesStatementsWithInventedRecalculatedOrConvertedFigures() {
        String answer = """
                - **FY23**: ₹38,615.40 crore
                - **FY26**: ₹56,815.40 crore is not in the data either.
                FY26 shows a 50.7% increase. Net profit margin was 8.47%.
                Revenue was about $20.3 billion.""";

        NumericClaimVerifier.Result result = verifier.verify(answer, EVIDENCE);

        assertThat(result.ungroundedFigures()).containsExactly("38,615.40", "56,815.40", "50.7", "20.3");
        assertThat(result.text()).isEqualTo("Net profit margin was 8.47%.");
    }

    @Test
    void ignoresYearsDatesPeriodLabelsAndListNumbering() {
        String answer = "1. In FY26 (to 2026-03-31) and Q1 FY27, over 3 years since 2023, margin was 8.47%.";

        assertThat(verifier.verify(answer, EVIDENCE).removedStatements()).isEmpty();
    }

    @Test
    void readsIndianDigitGrouping() {
        assertThat(verifier.verify("Market cap: ₹11,34,183 crore.", EVIDENCE).removedStatements()).isEmpty();
    }
}
