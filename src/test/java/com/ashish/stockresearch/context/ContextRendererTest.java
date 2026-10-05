package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.ScreeningMetric;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.ashish.stockresearch.context.OwnHistoryTest.roe;
import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static org.assertj.core.api.Assertions.assertThat;

class ContextRendererTest {

    private static final Pattern JUDGEMENT = Pattern.compile(
            "\\b(good|bad|strong|weak|cheap|expensive|attractive|healthy|better|worse)\\b", Pattern.CASE_INSENSITIVE);

    /** Every kind of line the context renders: a range and a rank, no range, too few peers, not ranked, notes. */
    private static ReportContext everyCase() {
        List<FundamentalsSnapshot> snapshot = new ArrayList<>(PeerContextTest.snapshot());
        PeerContext ranked = PeerContext.of(ScreeningMetric.ROE_PERCENT, IndustryGroup.BANK, "HDFCBANK",
                PeerContextTest.RUN, snapshot, 5).orElseThrow();
        PeerContext tooFew = PeerContext.of(ScreeningMetric.ROA_PERCENT, IndustryGroup.BANK, "HDFCBANK",
                PeerContextTest.RUN, snapshot, 5).orElseThrow();
        snapshot.add(stock("NOTRANKED", IndustryGroup.BANK).with(com.ashish.stockresearch.screening.ScreeningFixtures
                .unchecked(ScreeningMetric.ROE_PERCENT, "9.00")).build());
        PeerContext notRanked = PeerContext.of(ScreeningMetric.ROE_PERCENT, IndustryGroup.BANK, "NOTRANKED",
                PeerContextTest.RUN, snapshot, 5).orElseThrow();
        PeerContext absent = PeerContext.of(ScreeningMetric.ROE_PERCENT, IndustryGroup.BANK, "ABSENT",
                PeerContextTest.RUN, snapshot, 5).orElseThrow();
        Optional<OwnHistory> range = OwnHistory.of("returnOnEquityPercent", List.of(roe(2024, "13.90"),
                roe(2025, "14.03"), roe(2026, "17.00")), true, point -> false);
        Optional<OwnHistory> noRange = OwnHistory.of("returnOnAssetsPercent", List.of(roe(2025, "1.80"),
                roe(2026, DataStatus.DATA_CONFLICT)), false, point -> false);
        return new ReportContext("HDFCBANK", IndustryGroup.BANK, PeerContextTest.RUN, List.of(
                new ReportContext.Ratio("returnOnEquityPercent", range, Optional.of(ranked)),
                new ReportContext.Ratio("returnOnAssetsPercent", noRange, Optional.of(tooFew)),
                new ReportContext.Ratio("returnOnEquityPercent", Optional.empty(), Optional.of(notRanked)),
                new ReportContext.Ratio("returnOnEquityPercent", Optional.empty(), Optional.of(absent))),
                List.of("returnOnCapitalEmployedPercent", "debtToEquityMultiple"), null);
    }

    // S13-5
    @Test
    void rendersTheContextInNeutralWordsOnly() {
        String rendered = ContextRenderer.render(everyCase(), key -> "Label of " + key);

        assertThat(JUDGEMENT.matcher(rendered).find()).as(rendered).isFalse();
        assertThat(JUDGEMENT.matcher(ContextRenderer.render(ReportContext.none("X", IndustryGroup.OTHER,
                "OTHER is not one industry"), null)).find()).isFalse();
        assertThat(rendered).startsWith("## Context beside each ratio")
                .contains("**Label of returnOnEquityPercent** (returnOnEquityPercent)")
                .contains("No context is given for Label of returnOnCapitalEmployedPercent, Label of "
                        + "debtToEquityMultiple: not primary measures for BANK companies.");
    }

    // S13-6: the snapshot date and the peer count beside every peer figure
    @Test
    void statesTheSnapshotDateAndPeerCountBesideEveryPeerFigure() {
        ReportContext context = everyCase();
        String rendered = ContextRenderer.render(context, null);

        assertThat(rendered).contains("- BANK peers in the Nifty 500 snapshot of 2026-10-02 (6 peers with a usable "
                + "value): median 14.30%, range 8.60% to 18.10%. HDFCBANK in that snapshot: 14.03% (FY26), the 4th "
                + "highest of 7 companies (HDFCBANK and its 6 peers). Periods: FY26 for 6. Peers: AXISBANK, FEDERALBNK, ICICIBANK, "
                + "INDUSINDBK, KOTAKBANK, SBIN.");
        assertThat(rendered).contains("- BANK peers in the Nifty 500 snapshot of 2026-10-02: no peer statistic - 0 BANK "
                + "companies with a usable ROA in the snapshot of 2026-10-02 besides HDFCBANK; at least 5 are needed");
        assertThat(rendered).contains("NOTRANKED in that snapshot: not cross-checked, so it is not ranked.")
                .contains("ABSENT is not in that snapshot, so it is not ranked.");
        // every line carrying a peer statistic names the snapshot date and the count it was calculated from
        assertThat(rendered.lines().filter(line -> line.contains("median "))).isNotEmpty()
                .allSatisfy(line -> assertThat(line).containsPattern("snapshot of \\d{4}-\\d{2}-\\d{2} \\(\\d+ peers "
                        + "with a usable value\\): median"));
    }
}
