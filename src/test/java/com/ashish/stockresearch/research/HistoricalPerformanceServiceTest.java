package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.CalendarYearReturn;
import com.ashish.stockresearch.research.model.DataFreshness;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.PriceHistory;
import com.ashish.stockresearch.research.model.PricePoint;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.SourceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoricalPerformanceServiceTest {

    private final HistoricalPerformanceService service = new HistoricalPerformanceService();

    private static PricePoint p(String date, String close) {
        return new PricePoint(LocalDate.parse(date), new BigDecimal(close));
    }

    private static PriceHistory history(String currency, List<PricePoint> closes) {
        return new PriceHistory("TCS", "NSE", currency, closes, "test series",
                new DataProvenance("Test provider", SourceType.MARKET_DATA_PROVIDER, DataFreshness.HISTORICAL,
                        Instant.now(), null, null, null, "note."));
    }

    /** Year-end closes 2021-2024 with an easy shape: 100 -> 200 -> 150 -> 400. */
    private static final List<PricePoint> FOUR_YEARS = List.of(
            p("2021-12-31", "100"), p("2022-12-30", "200"), p("2023-12-29", "150"), p("2024-12-31", "400"));

    @Test
    void selectsTheFirstAndLastClosesOfTheWindowAsStartAndEndPrices() {
        HistoricalPerformance performance = service.analyze(history("INR", FOUR_YEARS), 5);

        assertThat(performance.startPrice().value()).isEqualByComparingTo("100");
        assertThat(performance.startPrice().period().end()).isEqualTo(LocalDate.of(2021, 12, 31));
        assertThat(performance.endPrice().value()).isEqualByComparingTo("400");
        assertThat(performance.window().start()).isEqualTo(LocalDate.of(2021, 12, 31));
        assertThat(performance.window().end()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    @Test
    void calculatesTotalReturnAndCagrInJavaFromTheExactElapsedYears() {
        HistoricalPerformance performance = service.analyze(history("INR", FOUR_YEARS), 5);

        assertThat(performance.totalReturnPercent().value()).isEqualByComparingTo("300.00");
        // 1096 days = 3.0007 years -> 4^(1/3.0007) - 1
        assertThat(performance.actualYears().value()).isEqualByComparingTo("3.00");
        assertThat(performance.cagrPercent().value()).isEqualByComparingTo("58.72");
        assertThat(performance.cagrPercent().calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(performance.cagrPercent().metric()).isEqualTo("priceCagrPercent");
        assertThat(performance.cagrPercent().calculation()).contains("2021-12-31 (100) to 2024-12-31 (400)");
    }

    @Test
    void trimsExtraHistorySoAShorterWindowDoesNotUseOlderPrices() {
        // A 2-year request on a 3-year series must start from the first close on/after 2022-12-31.
        HistoricalPerformance performance = service.analyze(history("INR", FOUR_YEARS), 2);

        assertThat(performance.startPrice().value()).isEqualByComparingTo("150");
        assertThat(performance.startPrice().period().end()).isEqualTo(LocalDate.of(2023, 12, 29));
        assertThat(performance.requestedYears()).isEqualTo(2);
    }

    @Test
    void calculatesDrawdownWithItsPeakAndTroughDates() {
        HistoricalPerformance performance = service.analyze(history("INR", FOUR_YEARS), 5);

        assertThat(performance.maxDrawdownPercent().value()).isEqualByComparingTo("-25.00");
        assertThat(performance.drawdownPeakDate()).isEqualTo(LocalDate.of(2022, 12, 30));
        assertThat(performance.drawdownTroughDate()).isEqualTo(LocalDate.of(2023, 12, 29));
        assertThat(performance.periodHigh().value()).isEqualByComparingTo("400");
        assertThat(performance.periodLow().value()).isEqualByComparingTo("100");
    }

    @Test
    void flagsTheCurrentYearAsYearToDateRatherThanAFullYear() {
        HistoricalPerformance performance = service.analyze(history("INR", List.of(
                p("2024-12-31", "100"), p("2025-12-31", "110"), p("2026-09-25", "121"))), 5);

        List<CalendarYearReturn> years = performance.calendarYearReturns();
        assertThat(years.get(0).returnPercent()).isNull();
        assertThat(years.get(1).returnPercent()).isEqualByComparingTo("10.00");
        assertThat(years.get(1).yearToDate()).isFalse();
        assertThat(years.get(2).yearToDate()).isTrue();
        assertThat(years.get(2).toDate()).isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    void notesWhenTheHistoryIsShorterThanRequested() {
        HistoricalPerformance performance = service.analyze(history("INR", FOUR_YEARS), 10);

        assertThat(performance.provenance().note()).contains("Requested 10 years").contains("3.00 years");
    }

    @Test
    void refusesToLabelNonRupeePricesAsRupees() {
        assertThatThrownBy(() -> service.analyze(history("USD", FOUR_YEARS), 5))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.DATA_NOT_AVAILABLE);
    }

    @Test
    void reportsTooShortAHistoryAsUnavailableRatherThanGuessing() {
        assertThatThrownBy(() -> service.analyze(history("INR", List.of(p("2024-12-31", "100"))), 5))
                .isInstanceOf(ResearchDataUnavailableException.class);
    }
}
