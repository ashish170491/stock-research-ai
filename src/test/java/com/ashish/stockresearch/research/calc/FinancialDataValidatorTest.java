package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.ProviderUnit;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialDataValidatorTest {

    /** 26 September 2026: Q1 FY27 is reported, Q2 FY27 (ends 30 Sep) has not even ended. */
    static final Clock SEPT_2026 = Clock.fixed(Instant.parse("2026-09-26T06:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private final FinancialDataValidator validator = new FinancialDataValidator(SEPT_2026);
    private final FiscalCalendar calendar = FiscalCalendar.indian();

    @Test
    void acceptsTheLatestQuarterThatHasActuallyBeenReported() {
        assertThat(validator.periodProblem(calendar.quarter(LocalDate.of(2026, 6, 30)), LocalDate.of(2026, 7, 18)))
                .isEmpty();
    }

    @Test
    void rejectsAQuarterThatHasNotEndedYet() {
        assertThat(validator.periodProblem(calendar.quarter(LocalDate.of(2026, 9, 30)), null))
                .hasValueSatisfying(problem -> assertThat(problem)
                        .contains("Q2 FY27").contains("after today").contains("cannot have been reported"));
    }

    @Test
    void rejectsResultsPublishedBeforeTheirPeriodEnded() {
        assertThat(validator.periodProblem(calendar.quarter(LocalDate.of(2026, 6, 30)), LocalDate.of(2026, 6, 1)))
                .hasValueSatisfying(problem -> assertThat(problem).contains("inconsistent"));
    }

    @Test
    void rejectsAPublicationDateInTheFuture() {
        assertThat(validator.periodProblem(calendar.quarter(LocalDate.of(2026, 6, 30)), LocalDate.of(2026, 10, 17)))
                .isPresent();
    }

    @Test
    void downgradesAFuturePeriodDataPointToUnavailableRatherThanPresentingIt() {
        FinancialDataPoint future = FinancialDataPoint.reported("revenue", new BigDecimal("1000000000"),
                ProviderUnit.WHOLE_CURRENCY_UNITS, new BigDecimal("100"), Unit.INR_CRORE,
                calendar.quarter(LocalDate.of(2026, 12, 31)),
                new SourceInfo("Test", SourceType.OTHER, null, null, null));

        FinancialDataPoint guarded = validator.guardPeriod(future);

        assertThat(guarded.available()).isFalse();
        assertThat(guarded.value()).isNull();
        assertThat(guarded.unavailableReason()).contains("Q3 FY27");
    }

    @Test
    void acceptsAMarketCapThatMatchesPriceTimesShares() {
        // HDFC Bank: 735.60 x 15,418,477,012 shares = 11,34,183 crore.
        assertThat(validator.marketCapInconsistency(new BigDecimal("1134183.11"), new BigDecimal("735.6"),
                new BigDecimal("15418477012"))).isEmpty();
    }

    @Test
    void flagsAMarketCapThatIsTenTimesTooLarge() {
        assertThat(validator.marketCapInconsistency(new BigDecimal("11341831.08"), new BigDecimal("735.6"),
                new BigDecimal("15418477012")))
                .hasValueSatisfying(issue -> {
                    assertThat(issue.type()).isEqualTo(DataQualityIssue.Type.DATA_CONFLICT);
                    assertThat(issue.message()).contains("wrong unit").containsPattern("by (899\\.9\\d|900\\.00)%");
                    assertThat(issue.affectedFields()).contains("price.marketCap");
                });
    }

    @Test
    void knowsTodayFromTheInjectedClock() {
        assertThat(validator.today()).isEqualTo(LocalDate.of(2026, 9, 26));
    }
}
