package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.Unit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** A place counted from the highest, as a calculated and verified data point like any other figure. */
final class Ranks {

    private Ranks() {
    }

    /**
     * @param ranked the input for the value being placed
     * @param among  every value it is placed among, itself included
     */
    static FinancialDataPoint of(String metric, CalculationInput ranked, List<CalculationInput> among,
                                 ReportingPeriod period, SourceInfo source, String calculation,
                                 CalculationVerifier.SourceValues sources) {
        List<CalculationInput> inputs = new ArrayList<>();
        inputs.add(ranked);
        inputs.addAll(among);
        return CalculationVerifier.verify(FinancialCalculator.rankFromHighest(
                        among.stream().map(CalculationInput::value).toList(), ranked.value())
                .map(place -> FinancialDataPoint.calculated(metric, BigDecimal.valueOf(place), Unit.POSITION, period,
                        source, Formula.RANK_FROM_HIGHEST, calculation, inputs))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, Unit.POSITION, period, source,
                        "the value is not among those it is ranked with")), sources);
    }

    /** The verified place, or null when there is none. */
    static Integer place(FinancialDataPoint rank) {
        return rank != null && rank.status() == DataStatus.VALID ? rank.value().intValueExact() : null;
    }
}
