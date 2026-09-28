package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportedValuation;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.model.ValuationSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks a provider's valuation multiples against the figures they are quoted against, then
 * calculates earnings yield from a P/E that passed.
 *
 * <ul>
 *   <li>Trailing P/E must agree with price / trailing EPS, price-to-book with price / book value per
 *       share, and dividend yield with dividend per share / price. A multiple further than the
 *       configured tolerance from its check is DATA_CONFLICT: the application does not choose between
 *       the provider's figure and its own.</li>
 *   <li>Earnings yield (100 / P/E) is calculated only from a VALID, positive P/E and is reproduced by
 *       {@link CalculationVerifier} before it is used.</li>
 * </ul>
 * A multiple whose check cannot run (a per-share input missing) keeps its status, and the gap in the
 * checking is reported as a data-quality warning rather than hidden.
 */
@Service
public class ValuationService {

    private final BigDecimal tolerancePercent;

    public ValuationService(@Value("${app.research.valuation.cross-check-tolerance-percent:5}")
                            BigDecimal tolerancePercent) {
        this.tolerancePercent = tolerancePercent;
    }

    public ValuationSnapshot assess(ReportedValuation reported) {
        List<DataQualityIssue> issues = new ArrayList<>();
        FinancialDataPoint pe = crossChecked(reported.trailingPe(), "trailing P/E", reported.price(),
                reported.trailingEps(), "share price / trailing EPS", issues,
                (price, eps) -> FinancialCalculator.multiple(price, eps));
        FinancialDataPoint pb = crossChecked(reported.priceToBook(), "price-to-book", reported.price(),
                reported.bookValuePerShare(), "share price / book value per share", issues,
                (price, book) -> FinancialCalculator.multiple(price, book));
        FinancialDataPoint dividendYield = crossChecked(reported.dividendYieldPercent(), "dividend yield",
                reported.dividendPerShare(), reported.price(), "dividend per share / share price x 100", issues,
                (dividend, price) -> FinancialCalculator.ratioPercent(dividend, price));

        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        reported.points().forEach(sources::add);
        FinancialDataPoint earningsYield = CalculationVerifier.verify(earningsYield(pe), sources);
        if (earningsYield.status() == DataStatus.INVALID) {
            issues.add(DataQualityIssue.invalidCalculation("earningsYieldPercent failed verification and is withheld. "
                    + earningsYield.statusReason(), earningsYield.dependsOnFields()));
        }

        List<DataGap> gaps = new ArrayList<>();
        for (FinancialDataPoint metric : List.of(pe, pb, dividendYield, earningsYield)) {
            if (metric.status() == DataStatus.UNAVAILABLE) {
                gaps.add(new DataGap("Valuation", metric.metric(), metric.statusReason()));
            }
        }
        return new ValuationSnapshot(reported, pe, pb, dividendYield, earningsYield, List.copyOf(issues),
                List.copyOf(gaps));
    }

    @FunctionalInterface
    private interface Check {
        Optional<BigDecimal> expected(BigDecimal numerator, BigDecimal denominator);
    }

    /**
     * The reported figure unchanged when it agrees with {@code check} of its two inputs, or marked
     * DATA_CONFLICT when it does not. Inputs must be VALID and in the same unit (never mixed currencies).
     */
    private FinancialDataPoint crossChecked(FinancialDataPoint reported, String name, FinancialDataPoint numerator,
                                            FinancialDataPoint denominator, String checkDescription,
                                            List<DataQualityIssue> issues, Check check) {
        if (reported == null || !reported.available()) {
            return reported;
        }
        Optional<String> unusable = unusable(numerator).or(() -> unusable(denominator))
                .or(() -> numerator.unit() == denominator.unit() ? Optional.empty()
                        : Optional.of("its inputs are in different units (%s and %s), which are never combined"
                                .formatted(numerator.unit(), denominator.unit())));
        Optional<BigDecimal> expected = unusable.isPresent() ? Optional.empty()
                : check.expected(numerator.value(), denominator.value());
        if (expected.isEmpty()) {
            issues.add(DataQualityIssue.warning("The provider's %s (%s) could not be checked against %s: %s."
                    .formatted(name, reported.display(), checkDescription,
                            unusable.orElse("the check's denominator is not positive")), fields(reported)));
            return reported;
        }
        Optional<BigDecimal> difference = FinancialCalculator.growthPercent(expected.get(), reported.value())
                .map(BigDecimal::abs);
        if (difference.isEmpty() || difference.get().compareTo(tolerancePercent) <= 0) {
            return reported;
        }
        // The implied figure is left out: it is not a verified data point, and a model shown it could
        // quote it as the multiple.
        String reason = ("The provider's %s (%s) is more than %s%% away from %s (%s and %s). The application does "
                + "not choose between them and states no replacement figure.").formatted(name, reported.display(),
                tolerancePercent.toPlainString(), checkDescription, numerator.display(), denominator.display());
        // Only the multiple is disputed: the price and per-share figure it was checked against stay usable.
        issues.add(DataQualityIssue.conflict(reason, fields(reported)));
        return reported.inConflict(reason);
    }

    private static Optional<String> unusable(FinancialDataPoint point) {
        if (point == null) {
            return Optional.of("an input is missing");
        }
        return point.available() ? Optional.empty()
                : Optional.of("%s is %s (%s)".formatted(point.metric(), point.status(), point.statusReason()));
    }

    /** 100 / trailing P/E, from a VALID P/E only: a disputed or missing P/E yields no earnings yield. */
    private static FinancialDataPoint earningsYield(FinancialDataPoint pe) {
        String metric = "earningsYieldPercent";
        String calculation = "100 / trailing P/E (%s)".formatted(pe.period().label());
        if (!pe.available()) {
            return FinancialDataPoint.notCalculated(metric, Unit.PERCENT, pe.period(), pe.source(), pe.status(),
                    Formula.RECIPROCAL_PERCENT, calculation, pe.dependsOnFields(),
                    "Earnings yield not calculated: trailing P/E is %s".formatted(pe.status()));
        }
        return FinancialCalculator.earningsYieldPercent(pe.value())
                .map(value -> FinancialDataPoint.calculated(metric, value, Unit.PERCENT, pe.period(), pe.source(),
                        Formula.RECIPROCAL_PERCENT, calculation, List.of(CalculationInput.of("trailing P/E", pe))))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.PERCENT, pe.period(), pe.source(),
                        "Earnings yield not calculated: trailing P/E is not positive"));
    }

    private static List<String> fields(FinancialDataPoint... points) {
        List<String> fields = new ArrayList<>();
        for (FinancialDataPoint point : points) {
            point.dependsOnFields().stream().filter(field -> !fields.contains(field)).forEach(fields::add);
        }
        return List.copyOf(fields);
    }
}
