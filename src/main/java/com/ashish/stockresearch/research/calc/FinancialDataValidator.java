package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.Unit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Data-quality rules applied to normalised data before it reaches the
 * model. Each rule either downgrades a value to UNAVAILABLE with a reason,
 * or returns a warning to surface in the report - it never repairs a value
 * by guessing.
 */
@Component
public class FinancialDataValidator {

    /** How far market cap may drift from price x shares (timing, share-count lag) before it looks like a unit error. */
    static final BigDecimal MARKET_CAP_TOLERANCE_PERCENT = BigDecimal.TEN;

    private final Clock clock;

    public FinancialDataValidator(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Why a period cannot be presented as reported, if anything: a period
     * ending after today has not happened yet, and results cannot be
     * published before the period they describe has ended.
     */
    public Optional<String> periodProblem(ReportingPeriod period, LocalDate publishedAt) {
        LocalDate today = today();
        if (period.known() && period.end().isAfter(today)) {
            return Optional.of("Period '%s' ends %s, after today (%s); it cannot have been reported yet"
                    .formatted(period.label(), period.end(), today));
        }
        if (publishedAt != null && publishedAt.isAfter(today)) {
            return Optional.of("Publication date %s is after today (%s)".formatted(publishedAt, today));
        }
        if (publishedAt != null && period.known() && publishedAt.isBefore(period.end())) {
            return Optional.of("Publication date %s is before the period end %s; the dates are inconsistent"
                    .formatted(publishedAt, period.end()));
        }
        return Optional.empty();
    }

    /** Downgrades an available point to UNAVAILABLE if its period fails {@link #periodProblem}. */
    public FinancialDataPoint guardPeriod(FinancialDataPoint point) {
        if (!point.available()) {
            return point;
        }
        return periodProblem(point.period(), point.source().publishedAt())
                .map(problem -> FinancialDataPoint.unavailable(point.metric(), point.unit(), point.period(),
                        point.source(), problem))
                .orElse(point);
    }

    /**
     * Cross-checks a normalised market cap against price x shares
     * outstanding. A tenfold unit error shows up here as a ~900% gap, so this
     * catches the class of bug that previously reached the report.
     */
    public Optional<DataQualityIssue> marketCapInconsistency(BigDecimal marketCapCrore, BigDecimal pricePerShare,
                                                             BigDecimal sharesOutstanding) {
        if (marketCapCrore == null || pricePerShare == null || sharesOutstanding == null
                || marketCapCrore.signum() <= 0 || pricePerShare.signum() <= 0 || sharesOutstanding.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal impliedCrore = pricePerShare.multiply(sharesOutstanding)
                .divide(FinancialUnits.RUPEES_PER_CRORE, 2, RoundingMode.HALF_UP);
        BigDecimal gapPercent = divergencePercent(marketCapCrore, impliedCrore);
        if (gapPercent.compareTo(MARKET_CAP_TOLERANCE_PERCENT) <= 0) {
            return Optional.empty();
        }
        return Optional.of(DataQualityIssue.conflict(("Reported market cap %s differs from share price x shares "
                + "outstanding (%s) by %s%%. One of the inputs may be in the wrong unit; neither value is chosen and "
                + "the market cap must be treated as unverified.")
                .formatted(FinancialUnits.display(marketCapCrore, Unit.INR_CRORE),
                        FinancialUnits.display(impliedCrore, Unit.INR_CRORE),
                        gapPercent.toPlainString()),
                List.of("price.marketCap", "price.regularMarketPrice", "defaultKeyStatistics.sharesOutstanding")));
    }

    /** |a - b| / |b| x 100, to 2 decimal places. */
    public static BigDecimal divergencePercent(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().divide(b.abs(), MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }
}
