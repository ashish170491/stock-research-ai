package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import java.time.Instant;
import java.util.List;

import static com.ashish.stockresearch.screening.ScreeningFixtures.DATE;
import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningFixtures.withStatus;
import static com.ashish.stockresearch.screening.ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.NET_PROFIT_CAGR_3Y_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.REVENUE_CAGR_3Y_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROA_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROCE_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_3Y_AVG_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_PERCENT;
import static org.assertj.core.api.Assertions.assertThat;

/** The service over a real (in-memory) snapshot database, with the configured presets, and the rendered table. */
class ScreeningServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T13:00:00Z");

    private final EmbeddedDatabase database = SnapshotDatabase.create();
    private final FundamentalsSnapshotRepository repository = new FundamentalsSnapshotRepository(database);
    private final Universes universes = new Universes();
    private final ScreeningService service = new ScreeningService(repository, new StockScreener(),
            ScreeningPresetsTest.presets(), universes);
    private final ScreeningRenderer renderer = new ScreeningRenderer();

    @AfterEach
    void close() {
        database.shutdown();
    }

    private static FundamentalsSnapshot quality(String symbol, String roe) {
        return stock(symbol, IndustryGroup.IT_SERVICES).with(ROE_3Y_AVG_PERCENT, roe).with(ROE_PERCENT, roe)
                .with(ROCE_PERCENT, "25").with(REVENUE_CAGR_3Y_PERCENT, "12").with(NET_PROFIT_CAGR_3Y_PERCENT, "11")
                .with(OCF_TO_PAT_3Y_MULTIPLE, "1.1").with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build();
    }

    /** A completed run of the whole Nifty 50: every member stored, as the snapshot job would store it. */
    private SnapshotRun completedRun() {
        List<Universes.Member> members = universes.members(Universe.NIFTY50);
        SnapshotRun run = repository.startRun(Universe.NIFTY50, DATE, NOW, members.size());
        for (int i = 0; i < members.size(); i++) {
            String symbol = members.get(i).symbol();
            FundamentalsSnapshot snapshot = switch (symbol) {
                case "HDFCBANK" -> stock(symbol, IndustryGroup.BANK).with(ROA_PERCENT, "1.8")
                        .with(withStatus(ROE_PERCENT, DataStatus.DATA_CONFLICT, "16.0")).build();
                case "HDFCLIFE" -> stock(symbol, IndustryGroup.INSURANCE).with(ROE_PERCENT, "12").build();
                default -> quality(symbol, String.valueOf(10 + i % 20));
            };
            repository.save(run.id(), snapshot);
        }
        repository.finishRun(run.id(), SnapshotRun.Status.COMPLETED, members.size(), 0, NOW.plusSeconds(600));
        return run;
    }

    // S3-10 / S3-L1: read the snapshot and screen the Nifty 50 without any provider, well inside a second.
    @Test
    void screensTheNifty50FromTheSnapshotInUnderASecond() {
        completedRun();

        long started = System.nanoTime();
        ScreeningOutcome outcome = service.screen("quality-compounder", "NIFTY50", null);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.result().ranked()).hasSize(49);
        assertThat(outcome.result().notScreened()).extracting(ScreeningResult.NotScreened::symbol)
                .containsExactly("HDFCLIFE");
        assertThat(millis).isLessThan(1000);
    }

    // S3-8
    @Test
    void rendersARankedTableWithPerRuleResultsTheSnapshotDateAndThatItIsNotARecommendation() {
        completedRun();

        String table = renderer.render(service.screen("quality-compounder", null, null));

        assertThat(table).startsWith("TOOL RESULT: screenStocks - status OK")
                .contains("Snapshot of 2026-09-28")
                .contains("not live")
                .contains("It is not a recommendation, rating or judgement of any stock")
                .contains("| Rank | Symbol | Company | Group | Met | ROE 3y avg ≥ 15.00% [FY26] |")
                .contains("#### BANK (1 stocks)")
                .contains("| HDFCBANK | HDFCBANK Limited | BANK | 1 of 2 | PASS 1.80% | INSUFFICIENT_DATA | none | "
                        + "ROE (DATA_CONFLICT) |")
                .contains("- 1 of the 49 have at least one criterion that could not be assessed (INSUFFICIENT_DATA).")
                .contains("- 1 stocks were not screened")
                .contains("- ROE: test: DATA_CONFLICT: HDFCBANK")
                .contains("HDFCLIFE (INSURANCE): the quality-compounder criteria define no rules for INSURANCE companies");
        // Bank rows never show a debt-to-equity or operating-margin result.
        String bankSection = table.substring(table.indexOf("#### BANK"));
        assertThat(bankSection.substring(0, bankSection.indexOf("\n\n", bankSection.indexOf("| Rank"))))
                .doesNotContain("D/E").doesNotContain("Operating margin");
    }

    @Test
    void ranksTheStockMeetingEveryCriterionWithTheHighestTieBreakFirst() {
        completedRun();

        ScreeningResult result = service.screen("quality-compounder", "Nifty 50", "IT_SERVICES").result();

        assertThat(result.ranked().get(0).meetsAll()).isTrue();
        assertThat(result.ranked().get(0).tieBreak().value())
                .isEqualByComparingTo(result.ranked().stream().filter(ScreeningResult.StockScreening::meetsAll)
                        .map(s -> s.tieBreak().value()).max(java.math.BigDecimal::compareTo).orElseThrow());
        assertThat(result.ranked()).allSatisfy(s -> assertThat(s.industryGroup()).isEqualTo(IndustryGroup.IT_SERVICES));
    }

    @Test
    void saysSoWhenNoSnapshotExistsYet() {
        ScreeningOutcome outcome = service.screen("quality-compounder", "NIFTY50", null);

        assertThat(outcome.status()).isEqualTo(ScreeningOutcome.Status.NO_SNAPSHOT);
        assertThat(renderer.render(outcome)).contains("No completed snapshot of Nifty 50 exists yet");
    }

    @Test
    void namesTheAvailableChoicesForAnUnknownPresetUniverseOrGroup() {
        assertThat(service.screen("momentum", null, null).message())
                .contains("quality-compounder, reasonable-value, dividend");
        assertThat(service.screen("dividend", "SENSEX", null).status())
                .isEqualTo(ScreeningOutcome.Status.UNKNOWN_UNIVERSE);
        assertThat(service.screen("dividend", null, "banks").message()).contains("BANK, NBFC");
    }

    @Test
    void listsUniverseMembersTheSnapshotDoesNotHave() {
        SnapshotRun run = repository.startRun(Universe.NIFTY50, DATE, NOW, 50);
        repository.save(run.id(), quality("TCS", "40"));
        repository.recordFailure(run.id(), "INFY", "Yahoo Finance returned nothing");
        repository.finishRun(run.id(), SnapshotRun.Status.COMPLETED, 1, 1, NOW.plusSeconds(60));

        ScreeningOutcome outcome = service.screen("quality-compounder", null, null);

        assertThat(outcome.notInSnapshot()).hasSize(49)
                .contains(new SnapshotRun.Failure("INFY", "Yahoo Finance returned nothing"));
        assertThat(renderer.render(outcome)).contains("#### Not in the snapshot").contains("- INFY: Yahoo Finance returned nothing");
    }

    @Test
    void summarisesWhichStocksMeetEveryCriterionSoAnAnswerNeverTalliesTheTable() {
        SnapshotRun run = repository.startRun(Universe.NIFTY50, DATE, NOW, 3);
        repository.save(run.id(), quality("TCS", "40"));
        repository.save(run.id(), stock("INFY", IndustryGroup.IT_SERVICES).with(ROE_3Y_AVG_PERCENT, "30")
                .with(ROCE_PERCENT, "39").with(withStatus(REVENUE_CAGR_3Y_PERCENT, DataStatus.DATA_CONFLICT, null))
                .with(NET_PROFIT_CAGR_3Y_PERCENT, "5").with(OCF_TO_PAT_3Y_MULTIPLE, "1.0")
                .with(DEBT_TO_EQUITY_MULTIPLE, "0.1").with(ROE_PERCENT, "30").build());
        repository.save(run.id(), stock("SBIN", IndustryGroup.BANK).with(ROA_PERCENT, "1.07").with(ROE_PERCENT, "15.38")
                .build());
        repository.finishRun(run.id(), SnapshotRun.Status.COMPLETED, 3, 0, NOW.plusSeconds(60));

        String table = renderer.render(service.screen("quality-compounder", null, null));

        assertThat(table).contains("- 2 of the 3 screened stocks meet every criterion that applies to them: "
                        + "TCS (IT_SERVICES), SBIN (BANK).")
                .contains("- 1 of the 3 have at least one criterion that could not be assessed")
                // INFY's row names its failed and its unassessed criteria separately
                .contains("| Profit CAGR 3y | Revenue CAGR 3y (DATA_CONFLICT) |");
    }

    // The web page's JSON carries the same decisions as the tool's table.
    @Test
    void theWebViewCarriesTheSameCountsCellsAndReasons() {
        completedRun();

        ScreeningView view = ScreeningView.from(service.screen("quality-compounder", null, null));

        assertThat(view.status()).isEqualTo("OK");
        assertThat(view.snapshot().date()).isEqualTo(DATE);
        assertThat(view.summary().screened()).isEqualTo(49);
        assertThat(view.summary().notScreened()).isEqualTo(1);
        assertThat(view.disclaimer()).contains("not a recommendation");
        ScreeningView.Section banks = view.sections().stream().filter(s -> s.title().equals("BANK")).findFirst().orElseThrow();
        assertThat(banks.criteria()).extracting(ScreeningView.Criterion::label)
                .containsExactly("ROA ≥ 1.00%", "ROE ≥ 14.00%");
        ScreeningView.Row hdfc = banks.rows().get(0);
        assertThat(hdfc.symbol()).isEqualTo("HDFCBANK");
        assertThat(hdfc.cells()).extracting(ScreeningView.Cell::outcome)
                .containsExactly(RuleOutcome.PASS, RuleOutcome.INSUFFICIENT_DATA);
        assertThat(hdfc.cells().get(1).status()).isEqualTo("DATA_CONFLICT");
        assertThat(hdfc.cells().get(1).reason()).isEqualTo("test: DATA_CONFLICT");
        assertThat(hdfc.cells().get(0).reason()).isNull();
        // every stock meeting every criterion is in the summary, and only those
        assertThat(view.summary().meetEveryCriterion()).extracting(ScreeningView.Stock::symbol)
                .containsExactlyInAnyOrderElementsOf(view.sections().stream().flatMap(s -> s.rows().stream())
                        .filter(ScreeningView.Row::meetsAll).map(ScreeningView.Row::symbol).toList());
    }

    @Test
    void theWebViewOfAFailedScreenCarriesOnlyTheReason() {
        ScreeningView view = ScreeningView.from(service.screen("quality-compounder", null, null));

        assertThat(view.status()).isEqualTo("NO_SNAPSHOT");
        assertThat(view.message()).contains("No completed snapshot");
        assertThat(view.sections()).isEmpty();
    }

    @Test
    void describesEachPresetsCriteriaByKindOfCompanyAndWhoItSkips() {
        PresetView quality = PresetView.from(ScreeningPresetsTest.presets().find("quality-compounder").orElseThrow());

        assertThat(quality.ruleSets()).extracting(PresetView.RuleSet::title)
                .containsExactly("Non-financial companies", "BANK", "NBFC");
        assertThat(quality.ruleSets().get(0).groups()).contains(IndustryGroup.IT_SERVICES)
                .doesNotContain(IndustryGroup.BANK, IndustryGroup.NBFC, IndustryGroup.INSURANCE,
                        IndustryGroup.FINANCIAL_OTHER, IndustryGroup.UNKNOWN);
        assertThat(quality.notScreened()).containsExactlyInAnyOrder(IndustryGroup.INSURANCE,
                IndustryGroup.FINANCIAL_OTHER, IndustryGroup.UNKNOWN);
        assertThat(quality.tieBreak()).isEqualTo("return on equity, latest fiscal year, higher first");
    }
}
