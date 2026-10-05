package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.ScreeningFixtures;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.Universe;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;

/** A stored snapshot for context tests: HDFC Bank, its peers, and companies that are not its peers. */
public final class ContextFixtures {

    private ContextFixtures() {
    }

    public static final SnapshotRun RUN = new SnapshotRun(13, Universe.NIFTY500, LocalDate.of(2026, 10, 2),
            Instant.parse("2026-10-02T02:57:44Z"), Instant.parse("2026-10-02T04:08:25Z"), SnapshotRun.Status.COMPLETED,
            500, 498, 2);

    private static FundamentalsSnapshot bank(String symbol, String roe) {
        return stock(symbol, IndustryGroup.BANK).with(ScreeningMetric.ROE_PERCENT, roe)
                .with(ScreeningMetric.ROCE_PERCENT, "9").build();
    }

    /** Six banks besides HDFCBANK, one disputed and one unchecked; an IT company and two insurers. */
    public static List<FundamentalsSnapshot> snapshot() {
        List<FundamentalsSnapshot> all = new ArrayList<>(List.of(bank("HDFCBANK", "14.03"), bank("ICICIBANK", "17.50"),
                bank("SBIN", "18.10"), bank("AXISBANK", "15.20"), bank("KOTAKBANK", "13.40"), bank("INDUSINDBK", "8.60"),
                bank("FEDERALBNK", "12.90"),
                stock("YESBANK", IndustryGroup.BANK).with(ScreeningFixtures.withStatus(ScreeningMetric.ROE_PERCENT,
                        DataStatus.DATA_CONFLICT, "30")).build(),
                stock("IDFCFIRSTB", IndustryGroup.BANK).with(ScreeningFixtures.unchecked(ScreeningMetric.ROE_PERCENT,
                        "2.00")).build(),
                stock("TCS", IndustryGroup.IT_SERVICES).with(ScreeningMetric.ROE_PERCENT, "52").build(),
                stock("HDFCLIFE", IndustryGroup.INSURANCE).with(ScreeningMetric.ROE_PERCENT, "11").build(),
                stock("SBILIFE", IndustryGroup.INSURANCE).with(ScreeningMetric.ROE_PERCENT, "13").build()));
        return all;
    }

}
