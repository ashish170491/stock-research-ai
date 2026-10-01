package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.FiledPerShare;
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
 *   <li>P/E on filed EPS is the share price over the basic EPS of the company's latest filed fiscal year,
 *       calculated here. That EPS is per share of the year's end; after a bonus issue, split or new issue it is
 *       not per share of today, so the number of shares the filing states (paid-up equity capital / face value)
 *       must match the provider's shares outstanding within {@link #SHARE_COUNT_TOLERANCE_PERCENT}%, or no P/E is
 *       given. Without the provider's count the P/E is shown but unchecked.</li>
 * </ul>
 * A failed check disputes the multiple and its per-share input alike; the share price stays usable.
 * A multiple whose check cannot run (a per-share input missing) keeps its status, the gap in the
 * checking is reported as a data-quality warning, and the snapshot names it as unchecked - screening
 * never passes a rule on an unchecked multiple.
 */
@Service
public class ValuationService {

    /** Share counts from two sources, a few months apart, agree this closely unless the share count changed. */
    static final BigDecimal SHARE_COUNT_TOLERANCE_PERCENT = BigDecimal.valueOf(2);

    private final BigDecimal tolerancePercent;

    public ValuationService(@Value("${app.research.valuation.cross-check-tolerance-percent:5}")
                            BigDecimal tolerancePercent) {
        this.tolerancePercent = tolerancePercent;
    }

    public ValuationSnapshot assess(ReportedValuation reported) {
        return assess(reported, FiledPerShare.unavailable("no filed results are available to this application"));
    }

    /** @param filed the latest filed fiscal year's per-share figures, for the P/E on filed EPS */
    public ValuationSnapshot assess(ReportedValuation reported, FiledPerShare filed) {
        List<DataQualityIssue> issues = new ArrayList<>();
        Checked pe = crossChecked(reported.trailingPe(), "trailing P/E", reported.price(),
                reported.trailingEps(), Side.DENOMINATOR, "share price / trailing EPS", issues,
                (price, eps) -> FinancialCalculator.multiple(price, eps));
        Checked pb = crossChecked(reported.priceToBook(), "price-to-book", reported.price(),
                reported.bookValuePerShare(), Side.DENOMINATOR, "share price / book value per share", issues,
                (price, book) -> FinancialCalculator.multiple(price, book));
        Checked dividendYield = crossChecked(reported.dividendYieldPercent(), "dividend yield",
                reported.dividendPerShare(), reported.price(), Side.NUMERATOR, "dividend per share / share price x 100",
                issues, (dividend, price) -> FinancialCalculator.ratioPercent(dividend, price));
        // A failed check disputes the per-share figure too: nothing says which of the two is wrong - it could
        // be the per-share figure (a stale EPS, or one not in rupees) - so neither is kept as usable.
        ReportedValuation checkedInputs = reported.withPerShare(pe.perShare(), pb.perShare(), dividendYield.perShare());

        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        reported.points().forEach(sources::add);
        FinancialDataPoint earningsYield = CalculationVerifier.verify(earningsYield(pe.multiple()), sources);
        if (earningsYield.status() == DataStatus.INVALID) {
            issues.add(DataQualityIssue.invalidCalculation("earningsYieldPercent failed verification and is withheld. "
                    + earningsYield.statusReason(), earningsYield.dependsOnFields()));
        }

        FinancialDataPoint shareCount = filedShareCount(filed);
        OnFiledEps onFiledEps = peOnFiledEps(reported, filed, shareCount, sources);
        if (onFiledEps.pe().status() == DataStatus.INVALID) {
            issues.add(DataQualityIssue.invalidCalculation("peOnFiledEps failed verification and is withheld. "
                    + onFiledEps.pe().statusReason(), onFiledEps.pe().dependsOnFields()));
        }

        List<DataGap> gaps = new ArrayList<>();
        for (FinancialDataPoint metric : List.of(pe.multiple(), pb.multiple(), dividendYield.multiple(), earningsYield,
                onFiledEps.pe())) {
            if (metric.status() == DataStatus.UNAVAILABLE) {
                gaps.add(new DataGap("Valuation", metric.metric(), metric.statusReason()));
            }
        }
        // Earnings yield is calculated from the P/E, so an unchecked P/E leaves it unchecked too.
        java.util.Map<String, String> unchecked = new java.util.LinkedHashMap<>();
        for (Checked checked : List.of(pe, pb, dividendYield)) {
            checked.uncheckedReason().ifPresent(reason -> unchecked.put(checked.multiple().metric(), reason));
        }
        pe.uncheckedReason().ifPresent(reason -> unchecked.put(earningsYield.metric(), "calculated from a trailing P/E "
                + "that was not cross-checked: " + reason));
        onFiledEps.uncheckedReason().ifPresent(reason -> {
            unchecked.put(onFiledEps.pe().metric(), reason);
            issues.add(DataQualityIssue.warning("The P/E on filed EPS (%s) is not checked: %s.".formatted(
                    onFiledEps.pe().display(), reason), onFiledEps.pe().dependsOnFields()));
        });
        return new ValuationSnapshot(checkedInputs, pe.multiple(), pb.multiple(), dividendYield.multiple(),
                earningsYield, onFiledEps.pe(), shareCount, List.copyOf(issues), List.copyOf(gaps),
                java.util.Map.copyOf(unchecked));
    }

    /** The P/E on filed EPS, and why it is unchecked if it is. */
    private record OnFiledEps(FinancialDataPoint pe, Optional<String> uncheckedReason) {
    }

    /** Shares at the fiscal year's end: paid-up equity capital / face value, both as filed. */
    private static FinancialDataPoint filedShareCount(FiledPerShare filed) {
        String metric = "filedShareCount";
        if (!filed.available()) {
            return FinancialDataPoint.unavailable(metric, Unit.SHARES, ReportingPeriod.unknown(), null,
                    filed.unavailableReason());
        }
        FinancialDataPoint capital = filed.paidUpEquityCapital();
        FinancialDataPoint face = filed.faceValue();
        String label = filed.fiscalYear().label();
        String calculation = "%s paid-up equity capital x 1,00,00,000 / %s face value per share".formatted(label, label);
        for (FinancialDataPoint input : List.of(capital, face)) {
            if (!input.available()) {
                return FinancialDataPoint.unavailable(metric, Unit.SHARES, capital.period(), capital.source(),
                        "not calculated: %s (%s) is %s: %s".formatted(input.metric(), label, input.status(),
                                input.statusReason()));
            }
        }
        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues().add(capital).add(face);
        return FinancialCalculator.shareCount(capital.value(), face.value())
                .map(count -> CalculationVerifier.verify(FinancialDataPoint.calculated(metric, count, Unit.SHARES,
                        capital.period(), capital.source(), Formula.SHARE_COUNT, calculation,
                        List.of(CalculationInput.of(label + " paid-up equity capital", capital),
                                CalculationInput.of(label + " face value per share", face))), sources))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.SHARES, capital.period(), capital.source(),
                        "not calculated: the face value per share is not positive"));
    }

    private OnFiledEps peOnFiledEps(ReportedValuation reported, FiledPerShare filed, FinancialDataPoint shareCount,
                                    CalculationVerifier.SourceValues sources) {
        String metric = "peOnFiledEps";
        FinancialDataPoint price = reported.price();
        ReportingPeriod at = price.period();
        if (!filed.available()) {
            return new OnFiledEps(FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, at, null,
                    "No P/E on filed EPS: " + filed.unavailableReason()), Optional.empty());
        }
        FinancialDataPoint eps = filed.basicEps();
        String label = filed.fiscalYear().label();
        String calculation = "share price (%s) / %s basic EPS as filed".formatted(at.label(), label);
        Optional<String> unusable = unusable(price).or(() -> unusable(eps));
        if (unusable.isPresent()) {
            return new OnFiledEps(FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, at, eps.source(),
                    "P/E on filed EPS not calculated: " + unusable.get()), Optional.empty());
        }
        if (eps.value().signum() <= 0) {
            return new OnFiledEps(FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, at, eps.source(),
                    "P/E on filed EPS not calculated: %s basic EPS is %s, and a P/E is not meaningful for a loss"
                            .formatted(label, eps.display())), Optional.empty());
        }
        FinancialDataPoint today = reported.sharesOutstanding();
        Optional<String> unchecked = Optional.empty();
        if (!shareCount.available() || today == null || !today.available()) {
            unchecked = Optional.of("whether %s basic EPS is on today's share count could not be checked: %s".formatted(
                    label, !shareCount.available() ? "the filed share count is " + shareCount.statusReason()
                            : "the provider's shares outstanding is " + (today == null ? "missing"
                            : today.status() + " (" + today.statusReason() + ")")));
        } else {
            BigDecimal apart = FinancialCalculator.growthPercent(today.value(), shareCount.value()).map(BigDecimal::abs)
                    .orElse(null);
            if (apart == null || apart.compareTo(SHARE_COUNT_TOLERANCE_PERCENT) > 0) {
                return new OnFiledEps(FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, at, eps.source(),
                        ("P/E on filed EPS not calculated: the filing states %s at the end of %s, the provider %s now%s. "
                                + "A bonus issue, split or share issue since then would make %s EPS a figure per share "
                                + "of a different count from today's price.").formatted(shareCount.display(), label,
                                today.display(), apart == null ? "" : " (%s%% apart)".formatted(apart.toPlainString()),
                                label)), Optional.empty());
            }
        }
        sources.add(eps);
        FinancialDataPoint pe = FinancialCalculator.multiple(price.value(), eps.value())
                .map(value -> CalculationVerifier.verify(FinancialDataPoint.calculated(metric, value, Unit.MULTIPLE, at,
                        eps.source(), Formula.MULTIPLE, calculation,
                        List.of(CalculationInput.of("share price", price), CalculationInput.of(label + " basic EPS", eps))),
                        sources))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.MULTIPLE, at, eps.source(),
                        "P/E on filed EPS could not be calculated"));
        return new OnFiledEps(pe, pe.available() ? unchecked : Optional.empty());
    }

    @FunctionalInterface
    private interface Check {
        Optional<BigDecimal> expected(BigDecimal numerator, BigDecimal denominator);
    }

    /** Which input of the check is the per-share figure; the other is the share price. */
    private enum Side { NUMERATOR, DENOMINATOR }

    /**
     * @param multiple        the provider's multiple, DATA_CONFLICT if the check failed
     * @param perShare        the per-share input, DATA_CONFLICT with the multiple if the check failed
     * @param uncheckedReason why the check could not run, for an available multiple that was not checked
     */
    private record Checked(FinancialDataPoint multiple, FinancialDataPoint perShare, Optional<String> uncheckedReason) {
    }

    /**
     * The reported figure unchanged when it agrees with {@code check} of its two inputs, or marked
     * DATA_CONFLICT - together with its per-share input - when it does not. Inputs must be VALID and in
     * the same unit (never mixed currencies).
     */
    private Checked crossChecked(FinancialDataPoint reported, String name, FinancialDataPoint numerator,
                                 FinancialDataPoint denominator, Side perShareSide, String checkDescription,
                                 List<DataQualityIssue> issues, Check check) {
        FinancialDataPoint perShare = perShareSide == Side.NUMERATOR ? numerator : denominator;
        if (reported == null || !reported.available()) {
            return new Checked(reported, perShare, Optional.empty());
        }
        Optional<String> unusable = unusable(numerator).or(() -> unusable(denominator))
                .or(() -> numerator.unit() == denominator.unit() ? Optional.empty()
                        : Optional.of("its inputs are in different units (%s and %s), which are never combined"
                                .formatted(numerator.unit(), denominator.unit())));
        Optional<BigDecimal> expected = unusable.isPresent() ? Optional.empty()
                : check.expected(numerator.value(), denominator.value());
        if (expected.isEmpty()) {
            String why = unusable.orElse("the check's denominator is not positive");
            issues.add(DataQualityIssue.warning("The provider's %s (%s) could not be checked against %s: %s."
                    .formatted(name, reported.display(), checkDescription, why), fields(reported)));
            return new Checked(reported, perShare, Optional.of("could not be checked against " + checkDescription
                    + ": " + why));
        }
        Optional<BigDecimal> difference = FinancialCalculator.growthPercent(expected.get(), reported.value())
                .map(BigDecimal::abs);
        if (difference.isEmpty() || difference.get().compareTo(tolerancePercent) <= 0) {
            return new Checked(reported, perShare, Optional.empty());
        }
        // The implied figure is left out: it is not a verified data point, and a model shown it could
        // quote it as the multiple.
        String reason = ("The provider's %s (%s) is more than %s%% away from %s (%s and %s). The application does "
                + "not choose between them and states no replacement figure.").formatted(name, reported.display(),
                tolerancePercent.toPlainString(), checkDescription, numerator.display(), denominator.display());
        // The share price is not disputed: its currency and date are confirmed by the quote itself.
        issues.add(DataQualityIssue.conflict(reason, fields(reported, perShare)));
        return new Checked(reported.inConflict(reason), perShare.inConflict(reason), Optional.empty());
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
