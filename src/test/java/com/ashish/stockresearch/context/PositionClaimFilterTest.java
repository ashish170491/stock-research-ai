package com.ashish.stockresearch.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// S13-6: where a value sits among years or peers reaches the reader only in the application's own words
class PositionClaimFilterTest {

    /** Every way the verifier and the live runs found of restating the context, right or wrong. */
    private static final List<String> RESTATED = List.of(
            "HDFCBANK's P/E places it 11th among its bank peers.",
            "By P/E, HDFCBANK is 11th of the 26 banks.",
            "HDFCBANK's P/E is 11th from the top among its peers.",
            "HDFCBANK's price-to-book places it 7th among the banks in the Nifty 500 snapshot.",
            "HDFCBANK is in 11th place among its peers by P/E.",
            "HDFCBANK's P/E places it 7th among 26 banks in the Nifty 500 snapshot of 2026-10-02.",
            "HDFCBANK's P/E is 7th of 26 companies (HDFCBANK and its 25 peers) in the snapshot of 2026-10-02.",
            "HDFCBANK ranks first among its 25 peers by P/E in the snapshot of 2026-10-02.",
            "The P/E of 15.77x is the highest among the 25 peers in the snapshot of 2026-10-02, whose range runs from "
                    + "4.79x to 52.26x.",
            "Its P/E is in the top three of its 25 peers.",
            "Its dividend yield of 1.80% sits below the median.",
            "These figures place HDFCBANK's ROE at the 16th highest of 26 companies in the Nifty 500 snapshot as of "
                    + "2026-10-02.",
            "The trailing P/E is higher than most banks in the index.",
            "Its P/E is 15.77x, above that of other banks.",
            "FY26 ROE of 15.31% is the lowest of the three valid years.",
            "FY25 was the year with the highest return on equity.",
            "Its P/E is the 11th highest of 26 companies (HDFCBANK and its 25 peers) in the Nifty 500 snapshot of "
                    + "2026-10-02.",
            // the fourth probe: a place or comparison in words, with no figure to check
            "Its P/E is the eleventh highest in its industry group.",
            "By P/E it is second only to a handful of lenders.",
            "Most banks in the index have a lower P/E than HDFC Bank.",
            // the live ICICI Bank run: a level against a norm the data does not supply
            "These figures indicate a moderate return on equity and a relatively low return on assets.",
            "Its return on assets is relatively high for its sector.",
            "The bank shows a high ROE.",
            // the sixth run: a peer statistic rounded to a whole number, with no comparison word of its own -
            // NumericClaimVerifier grounds a bare integer by exact match anywhere in the facts, unrelated to what
            // it is about, so the figure alone cannot be trusted to catch these; any unit-bearing number beside a
            // plain mention of other companies is removed regardless
            "Dividend yields for banks in this group often approach 5%.",
            "Lenders in the Nifty 500 trade at about 1 times book.",
            "Nifty 500 lenders earn about 13% on equity.",
            // the same pattern for a different metric and group noun
            "Companies in this group often trade near 11 times earnings.",
            "Firms in the index earn about 2% on assets.",
            "Stocks in the sector yield close to 1%.");

    @Test
    void removesEverySentenceThatRestatesAPlaceOrAComparison() {
        PositionClaimFilter.Result result = PositionClaimFilter.filter(String.join(" ", RESTATED));

        assertThat(result.removedStatements()).containsExactlyElementsOf(RESTATED);
        assertThat(result.text()).isBlank();
    }

    @Test
    void keepsSentencesThatPlaceNothing() {
        String answer = "FY25 return on equity was 14.03%. Net profit for FY23, FY24 and FY26 is in a DATA_CONFLICT, "
                + "so no trend is drawn from it. The share price returned -2.80% from 2021-10-04 to 2026-10-01, and the "
                + "largest fall was -31.04%. The first quarter of FY27 is the latest reported. The report shows the "
                + "context of each ratio beside it.";

        PositionClaimFilter.Result result = PositionClaimFilter.filter(answer);

        assertThat(result.removedStatements()).isEmpty();
        assertThat(result.text()).isEqualTo(answer);
    }
}
