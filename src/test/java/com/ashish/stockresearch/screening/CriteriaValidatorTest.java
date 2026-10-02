package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage 2 of the screener chain: what the extraction model read, checked in Java against the request itself. */
class CriteriaValidatorTest {

    private static final String ROADMAP_REQUEST = "Find profitable IT companies with low debt and ROE above 18%";

    private final CriteriaValidator validator = new CriteriaValidator(ScreeningPresetsTest.presets(),
            ScreeningPresetsTest.properties());

    private static ExtractedCriteria.Criterion criterion(String phrase, String metric, String operator, String threshold) {
        return new ExtractedCriteria.Criterion(phrase, metric, operator, threshold);
    }

    private static ExtractedCriteria read(List<String> groups, List<ExtractedCriteria.Criterion> criteria,
                                          List<String> vague) {
        return new ExtractedCriteria(null, groups, criteria, vague);
    }

    private static List<String> labels(ScreeningCriteria criteria, IndustryGroup group) {
        return criteria.rulesFor(group).orElse(List.of()).stream().map(Rule::label).toList();
    }

    // S4-3 / S4-4: the roadmap's request - a stated criterion, two vague ones and an industry group.
    @Test
    void readsTheRoadmapRequestAndSaysHowEveryPartWasRead() {
        ScreenInterpretation interpretation = validator.interpret(ROADMAP_REQUEST, read(List.of("IT_SERVICES"),
                List.of(criterion("ROE above 18%", "ROE_PERCENT", "ABOVE", "18")), List.of("profitable", "low debt")),
                false);

        ScreeningCriteria criteria = interpretation.criteria().orElseThrow();
        assertThat(criteria.industryGroups()).containsExactly(IndustryGroup.IT_SERVICES);
        assertThat(labels(criteria, IndustryGroup.IT_SERVICES))
                .containsExactly("ROE > 18.00%", "ROE 3y avg ≥ 15.00%", "D/E ≤ 0.50x");
        assertThat(interpretation.readings()).contains(
                "“IT” → only companies in the IT_SERVICES industry group",
                "“ROE above 18%” → ROE > 18.00% (return on equity, latest fiscal year)",
                "“low debt” → D/E ≤ 0.50x (debt-to-equity, latest fiscal year end): the quality-compounder preset's "
                        + "threshold, as no number was given",
                "“profitable” → ROE 3y avg ≥ 15.00% (return on equity, average of the last 3 fiscal years): the "
                        + "quality-compounder preset's threshold, as no number was given");
        assertThat(interpretation.notApplied()).isEmpty();
    }

    // S4-3: vague words are found in the request by Java too, and take the preset's threshold, never the model's.
    @Test
    void readsAVagueWordAsThePresetsThresholdEvenWhenTheModelGaveItANumber() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with low debt", read(List.of(),
                List.of(criterion("low debt", "DEBT_TO_EQUITY_MULTIPLE", "AT_MOST", "1")), List.of()), false);

        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.MANUFACTURING))
                .containsExactly("D/E ≤ 0.50x");
    }

    @Test
    void takesAVagueWordFromThePresetTheConfigurationNames() {
        ScreenInterpretation interpretation = validator.interpret("Find dividend paying consumer companies",
                ExtractedCriteria.none(), false);

        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.CONSUMER))
                .containsExactly("Dividend yield ≥ 2.00%");
        assertThat(interpretation.readings()).anyMatch(r -> r.contains("the dividend preset's threshold"));
    }

    @Test
    void aVagueWordWithANumberAfterItIsTheUsersOwnCriterion() {
        ScreenInterpretation interpretation = validator.interpret("Find growing companies, revenue growing above 15%",
                read(List.of(), List.of(criterion("revenue growing above 15%", "REVENUE_CAGR_3Y_PERCENT", "ABOVE", "15")),
                        List.of("growing")), false);

        // "growing" alone is vague (revenue and profit CAGR); the stated revenue criterion replaces its revenue half.
        // The request names no span, so growth is over the default 5 years, whatever span the model picked.
        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.OTHER))
                .containsExactly("Revenue CAGR 5y > 15.00%", "Profit CAGR 5y ≥ 10.00%");
        assertThat(interpretation.readings()).contains("“revenue growing above 15%” → Revenue CAGR 5y > 15.00% (revenue "
                        + "CAGR over the last 5 fiscal years) - over 5 fiscal years, as no span was given",
                "“growing”: covered by your own Revenue CAGR 5y criterion");
    }

    // S4-3: an unknown metric is rejected with a message naming what can be screened.
    @Test
    void rejectsAMetricNotOnTheWhitelistWithAMessage() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with promoter holding above 50%",
                read(List.of(), List.of(criterion("promoter holding above 50%", "PROMOTER_HOLDING", "ABOVE", "50")),
                        List.of()), false);

        assertThat(interpretation.criteria()).isEmpty();
        assertThat(interpretation.notApplied()).singleElement().asString()
                .contains("“promoter holding above 50%”: ‘PROMOTER_HOLDING’ is not a metric the screen can test")
                .contains("It can test: ROE 3y avg, ROE 5y avg, ROE, ROA, ROCE");
        assertThat(interpretation.refusal()).startsWith("I found no criteria in your request that the screen can apply");
    }

    @Test
    void screensTheRestWhenOnlySomeCriteriaAreUnknown() {
        ScreenInterpretation interpretation = validator.interpret(
                "Find stocks with promoter holding above 50% and ROCE above 20%", read(List.of(), List.of(
                        criterion("promoter holding above 50%", "PROMOTER_HOLDING", "ABOVE", "50"),
                        criterion("ROCE above 20%", "ROCE_PERCENT", "ABOVE", "20")), List.of()), false);

        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.OTHER)).containsExactly("ROCE > 20.00%");
        assertThat(interpretation.notApplied()).singleElement().asString().contains("PROMOTER_HOLDING");
    }

    // S4-3: out of bounds is rejected, never clamped or converted.
    @Test
    void rejectsAThresholdOutsideTheMetricsBounds() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with D/E below 50", read(List.of(),
                List.of(criterion("D/E below 50", "DEBT_TO_EQUITY_MULTIPLE", "BELOW", "50")), List.of()), false);

        assertThat(interpretation.criteria()).isEmpty();
        assertThat(interpretation.notApplied()).singleElement().asString()
                .contains("50 is outside the range the screen accepts for D/E (0.00x to 10.00x)")
                .contains("a multiple, 0.5 for 0.5x");
    }

    @Test
    void neverUsesAThresholdTheModelConverted() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with ROE above 18%", read(List.of(),
                List.of(criterion("ROE above 18%", "ROE_PERCENT", "ABOVE", "0.18")), List.of()), false);

        assertThat(interpretation.criteria()).isEmpty();
        assertThat(interpretation.notApplied()).singleElement().asString()
                .contains("the threshold was read as 0.18, which is not the number written");
        // and the 18 the user wrote is not silently dropped
        assertThat(interpretation.refusal()).contains("18 was not matched to a criterion");
    }

    @Test
    void refusesToScreenWhenACriterionTheUserStatedWasMissed() {
        ScreenInterpretation interpretation = validator.interpret(ROADMAP_REQUEST, read(List.of("IT_SERVICES"),
                List.of(), List.of("profitable", "low debt")), false);

        assertThat(interpretation.criteria()).isEmpty();
        assertThat(interpretation.refusal()).startsWith("I could not read every criterion in your request (18 was not "
                + "matched to a criterion)");
    }

    @Test
    void followsTheUsersComparisonWords() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with ROE of at least 18%", read(List.of(),
                List.of(criterion("ROE of at least 18%", "ROE_PERCENT", "ABOVE", "18")), List.of()), false);

        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.OTHER)).containsExactly("ROE ≥ 18.00%");
    }

    @Test
    void neverReadsAJudgementAsAThreshold() {
        ScreenInterpretation interpretation = validator.interpret("Find cheap IT stocks", read(List.of("IT_SERVICES"),
                List.of(criterion("cheap", "TRAILING_PE_MULTIPLE", "BELOW", "20")), List.of("cheap")), false);

        assertThat(interpretation.criteria()).isEmpty();
        assertThat(interpretation.notApplied()).singleElement().asString()
                .startsWith("“cheap” is a judgement, and this application does not judge stocks");
    }

    @Test
    void saysWhenAVagueWordHasNoDefinedReading() {
        ScreenInterpretation interpretation = validator.interpret("Find stable companies with ROE above 20%",
                read(List.of(), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of("stable")),
                false);

        assertThat(interpretation.criteria()).isPresent();
        assertThat(interpretation.notApplied()).singleElement().asString()
                .startsWith("“stable”: the application has no defined reading for this");
    }

    // A group is screened only by criteria that are primary for it.
    @Test
    void doesNotScreenABankOnDebtWhenTheRequestIsNotLimitedToBanks() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with low debt and ROE above 18%",
                read(List.of(), List.of(criterion("ROE above 18%", "ROE_PERCENT", "ABOVE", "18")), List.of("low debt")),
                false);

        ScreeningCriteria criteria = interpretation.criteria().orElseThrow();
        assertThat(criteria.rulesFor(IndustryGroup.BANK)).isEmpty();
        assertThat(criteria.whyNotScreened(IndustryGroup.BANK)).contains("D/E is not a primary metric for BANK");
        // debt-to-equity is primary for an NBFC, so it is screened on both
        assertThat(labels(criteria, IndustryGroup.NBFC)).containsExactly("ROE > 18.00%", "D/E ≤ 0.50x");
        assertThat(interpretation.readings()).contains(
                "Not screened: BANK, INSURANCE, FINANCIAL_OTHER companies, as D/E is not a primary metric for them");
    }

    @Test
    void dropsACriterionThatDoesNotApplyToTheGroupAskedFor() {
        ScreenInterpretation interpretation = validator.interpret("Find banks with low debt and ROE above 15%",
                read(List.of("BANK"), List.of(criterion("ROE above 15%", "ROE_PERCENT", "ABOVE", "15")),
                        List.of("low debt")), false);

        ScreeningCriteria criteria = interpretation.criteria().orElseThrow();
        assertThat(labels(criteria, IndustryGroup.BANK)).containsExactly("ROE > 15.00%");
        assertThat(interpretation.readings()).contains("D/E: not a primary metric for BANK companies, so not applied to them");
    }

    @Test
    void refusesWhenNoCriterionAppliesToTheGroupAskedFor() {
        ScreenInterpretation interpretation = validator.interpret("Find banks with low debt", ExtractedCriteria.none(),
                false);

        assertThat(interpretation.criteria()).isEmpty();
        assertThat(interpretation.refusal()).contains("none of them applies to BANK companies");
    }

    @Test
    void appliesANamedPresetForTheGroupAskedFor() {
        ScreenInterpretation interpretation = validator.interpret("Which banks pass the quality compounder screen?",
                new ExtractedCriteria("quality-compounder", List.of("BANK"), List.of(), List.of()), false);

        ScreeningCriteria criteria = interpretation.criteria().orElseThrow();
        assertThat(criteria.name()).isEqualTo("quality-compounder");
        assertThat(criteria.industryGroups()).containsExactly(IndustryGroup.BANK);
        assertThat(labels(criteria, IndustryGroup.BANK)).containsExactly("ROA ≥ 1.00%", "ROE ≥ 14.00%");
        assertThat(interpretation.readings()).first().asString().startsWith("“quality compounder” → the quality-compounder preset");
    }

    @Test
    void addsTheRequestsOwnCriteriaToAPresetReplacingItsRuleOnTheSameMetric() {
        ScreenInterpretation interpretation = validator.interpret("Quality compounders with D/E below 0.2",
                read(List.of(), List.of(criterion("D/E below 0.2", "DEBT_TO_EQUITY_MULTIPLE", "BELOW", "0.2")),
                        List.of()), false);

        ScreeningCriteria criteria = interpretation.criteria().orElseThrow();
        assertThat(labels(criteria, IndustryGroup.IT_SERVICES)).contains("D/E < 0.20x").doesNotContain("D/E ≤ 0.50x");
        assertThat(interpretation.readings()).contains("Your D/E criterion replaces the quality-compounder preset's D/E ≤ 0.50x");
        // banks keep the preset's own rules, and are not screened on the D/E they have no rule for
        assertThat(labels(criteria, IndustryGroup.BANK)).isEmpty();
        assertThat(criteria.whyNotScreened(IndustryGroup.BANK)).contains("D/E is not a primary metric for BANK");
    }

    @Test
    void usesAPresetOrGroupOnlyWhenTheRequestNamesIt() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with ROCE above 20%",
                new ExtractedCriteria("quality-compounder", List.of("AUTOMOBILES"),
                        List.of(criterion("ROCE above 20%", "ROCE_PERCENT", "ABOVE", "20")), List.of()), false);

        ScreeningCriteria criteria = interpretation.criteria().orElseThrow();
        assertThat(criteria.name()).isEqualTo(CriteriaValidator.CUSTOM);
        assertThat(criteria.industryGroups()).isEmpty();
        assertThat(interpretation.notApplied()).singleElement().asString()
                .startsWith("“AUTOMOBILES” is not an industry group the screen knows");
    }

    @Test
    void theRequestsOwnWordsDecideTheGroupOverTheModels() {
        ScreenInterpretation interpretation = validator.interpret("Find pharma companies with ROE above 20%",
                read(List.of("CONSUMER"), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()),
                false);

        assertThat(interpretation.criteria().orElseThrow().industryGroups()).containsExactly(IndustryGroup.PHARMA);
    }

    // The questions the Screener page's "Ask the assistant" button writes, one per company type it offers.
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "Which Nifty 50 banks meet the quality-compounder criteria?                    | BANK",
            "Which Nifty 50 nbfcs (non-bank lenders) meet the quality-compounder criteria? | NBFC",
            "Which Nifty 50 insurers meet the dividend criteria?                           | INSURANCE",
            "Which Nifty 50 other financial companies meet the dividend criteria?          | FINANCIAL_OTHER",
            "Which Nifty 50 it services meet the reasonable-value criteria?                | IT_SERVICES",
            "Which Nifty 50 pharma meet the quality-compounder criteria?                   | PHARMA",
            "Which Nifty 50 manufacturing and industrials meet the dividend criteria?      | MANUFACTURING",
            "Which Nifty 50 consumer meet the dividend criteria?                           | CONSUMER",
            "Which Nifty 50 other industries meet the quality-compounder criteria?         | OTHER"})
    void readsEveryCompanyTypeTheScreenerPageOffers(String question, IndustryGroup group) {
        ScreenInterpretation interpretation = validator.interpret(question, ExtractedCriteria.none(), true);

        assertThat(interpretation.criteria().orElseThrow().industryGroups()).containsExactly(group);
    }

    @Test
    void readsThePagesQuestionWithoutACompanyTypeAsTheWholePreset() {
        ScreenInterpretation interpretation = validator.interpret(
                "Which Nifty 50 stocks meet the dividend criteria?", ExtractedCriteria.none(), true);

        ScreeningCriteria criteria = interpretation.criteria().orElseThrow();
        assertThat(criteria.name()).isEqualTo("dividend");
        assertThat(criteria.industryGroups()).isEmpty();
    }

    @Test
    void neverReadsItAsItServices() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with ROE above 20% if it is possible",
                read(List.of(), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()), false);

        assertThat(interpretation.criteria().orElseThrow().industryGroups()).isEmpty();
    }

    // S4-6: with no answer from the extraction model, the request's own words are read, or nothing is run.
    @Test
    void readsTheRequestsOwnWordsWhenExtractionFails() {
        ScreenInterpretation interpretation = validator.interpret("Find profitable IT companies with low debt",
                ExtractedCriteria.none(), true);

        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.IT_SERVICES))
                .containsExactly("ROE 3y avg ≥ 15.00%", "D/E ≤ 0.50x");
    }

    @Test
    void refusesRatherThanGuessWhenExtractionFailsOnARequestWithNumbers() {
        ScreenInterpretation interpretation = validator.interpret(ROADMAP_REQUEST, ExtractedCriteria.none(), true);

        assertThat(interpretation.criteria()).isEmpty();
        assertThat(interpretation.refusal()).startsWith("I could not read the criteria in your request, so I did not run a "
                + "screen rather than run one without them.");
    }

    @Test
    void explainsSpansTopNAndOtherUniversesWithoutTakingThemForCriteria() {
        ScreenInterpretation interpretation = validator.interpret(
                "Screen the Nifty Next 50 for the top 10 stocks with 5-year revenue CAGR above 12%", read(List.of(),
                        List.of(criterion("5-year revenue CAGR above 12%", "REVENUE_CAGR_3Y_PERCENT", "ABOVE", "12")),
                        List.of()), false);

        // the 5-year span is the user's: the model's 3-year metric is replaced, not used
        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.OTHER))
                .containsExactly("Revenue CAGR 5y > 12.00%");
        assertThat(interpretation.notApplied()).hasSize(2)
                .anyMatch(n -> n.equals("“Nifty Next 50”: only the Nifty 50 and the Nifty 500 can be screened so far, so "
                        + "the screen covers the Nifty 50."))
                .anyMatch(n -> n.contains("“top 10”: the screen ranks every stock"));
    }

    @Test
    void measuresACriterionOverTheSpanItsOwnWordsName() {
        ScreenInterpretation interpretation = validator.interpret(
                "Companies with 3-year profit growth above 15% and 5y average ROE of at least 18%", read(List.of(),
                        List.of(criterion("3-year profit growth above 15%", "NET_PROFIT_CAGR_5Y_PERCENT", "ABOVE", "15"),
                                criterion("5y average ROE of at least 18%", "ROE_3Y_AVG_PERCENT", "AT_LEAST", "18")),
                        List.of()), false);

        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.OTHER))
                .containsExactly("ROE 5y avg ≥ 18.00%", "Profit CAGR 3y > 15.00%");
        assertThat(interpretation.notApplied()).isEmpty();
    }

    @Test
    void rejectsASpanTheApplicationDoesNotMeasure() {
        ScreenInterpretation interpretation = validator.interpret("Stocks with 10-year revenue CAGR above 12%",
                read(List.of(), List.of(criterion("10-year revenue CAGR above 12%", "REVENUE_CAGR_5Y_PERCENT", "ABOVE",
                        "12")), List.of()), false);

        assertThat(interpretation.criteria()).isEmpty();
        assertThat(interpretation.notApplied()).containsExactly("“10-year revenue CAGR above 12%”: revenue growth (CAGR) "
                + "is measured over 3 or 5 fiscal years, so a 10-year span cannot be screened and the criterion was not "
                + "applied.");
    }

    @Test
    void readsAVagueQualityOverTheSpanTheRequestNames() {
        ScreenInterpretation interpretation = validator.interpret("Find companies growing over the last 3 years",
                ExtractedCriteria.none(), false);

        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.OTHER))
                .containsExactly("Revenue CAGR 3y ≥ 10.00%", "Profit CAGR 3y ≥ 10.00%");
        assertThat(interpretation.readings()).contains("“growing” → Revenue CAGR 3y ≥ 10.00% (revenue CAGR over the "
                + "last 3 fiscal years): the quality-compounder preset's threshold for its 5-year rule, as no number was "
                + "given, measured over the 3 years your request names");
    }

    @Test
    void saysWhenASpanAppliesToNoCriterion() {
        ScreenInterpretation interpretation = validator.interpret("Stocks with ROE above 15% over 5 years",
                read(List.of(), List.of(criterion("ROE above 15%", "ROE_PERCENT", "ABOVE", "15")), List.of()), false);

        // a single year's ROE: the span is not quietly read as an average
        assertThat(labels(interpretation.criteria().orElseThrow(), IndustryGroup.OTHER)).containsExactly("ROE > 15.00%");
        assertThat(interpretation.notApplied()).singleElement().asString()
                .startsWith("“5 years”: no criterion of this screen is measured over 5 fiscal years");
    }

    // S12-3
    @Test
    void screensTheNifty500WhenTheRequestNamesIt() {
        ScreenInterpretation interpretation = validator.interpret("Which Nifty 500 stocks have ROE above 20%?",
                read(List.of(), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()), false);

        assertThat(interpretation.universe()).isEqualTo(Universe.NIFTY500);
        assertThat(interpretation.criteria()).isPresent();
        // 500 is the index's name, not a number the screen left unread
        assertThat(interpretation.notApplied()).isEmpty();
        assertThat(interpretation.readings()).contains("Universe: the Nifty 500, as your request names it.");
    }

    // S12-3
    @Test
    void screensTheNifty50WhenTheRequestNamesNoIndexAndSaysSo() {
        ScreenInterpretation unnamed = validator.interpret("Find companies with ROE above 20%",
                read(List.of(), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()), false);
        ScreenInterpretation named = validator.interpret("Which NIFTY50 stocks have ROE above 20%?",
                read(List.of(), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()), false);

        assertThat(unnamed.universe()).isEqualTo(Universe.NIFTY50);
        assertThat(unnamed.readings()).contains("Universe: the Nifty 50, the default when a request names no index that "
                + "can be screened (name the Nifty 500 to screen it instead).");
        assertThat(named.universe()).isEqualTo(Universe.NIFTY50);
        assertThat(named.readings()).contains("Universe: the Nifty 50, as your request names it.");
        // a refused request still says which index it would have covered
        assertThat(validator.interpret("Screen the Nifty 500", ExtractedCriteria.none(), true).universe())
                .isEqualTo(Universe.NIFTY500);
    }

    // "Nifty-500" is the index's name, hyphen or not, never a number left unread
    @Test
    void readsAHyphenatedIndexNameAsTheIndex() {
        ScreenInterpretation interpretation = validator.interpret("Which Nifty-500 stocks have ROE above 20%?",
                read(List.of(), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()), false);

        assertThat(interpretation.universe()).isEqualTo(Universe.NIFTY500);
        assertThat(interpretation.criteria()).isPresent();
        assertThat(interpretation.refusal()).isNull();

        // an index that cannot be screened is said, hyphen or not
        assertThat(validator.interpret("Which Nifty-100 stocks have ROE above 20%?", read(List.of(),
                List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()), false).notApplied())
                .containsExactly("“Nifty-100”: only the Nifty 50 and the Nifty 500 can be screened so far, so the "
                        + "screen covers the Nifty 50.");
    }

    @Test
    void screensOneIndexWhenARequestNamesTwo() {
        ScreenInterpretation interpretation = validator.interpret(
                "Which Nifty 500 stocks outside the Nifty 50 have ROE above 20%?",
                read(List.of(), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()), false);

        assertThat(interpretation.universe()).isEqualTo(Universe.NIFTY500);
        assertThat(interpretation.notApplied()).containsExactly(
                "“Nifty 50”: one screen covers one index, so it covers the Nifty 500, the first your request names.");
    }

    @Test
    void theNiftyFiftysNumberIsNotACriterion() {
        ScreenInterpretation interpretation = validator.interpret("Which Nifty 50 stocks have ROE above 20%?",
                read(List.of(), List.of(criterion("ROE above 20%", "ROE_PERCENT", "ABOVE", "20")), List.of()), false);

        assertThat(interpretation.criteria()).isPresent();
        assertThat(interpretation.refusal()).isNull();
    }

    @Test
    void theTieBreakFollowsTheFirstCriterionsDirection() {
        ScreenInterpretation interpretation = validator.interpret("Find stocks with P/E below 20", read(List.of(),
                List.of(criterion("P/E below 20", "TRAILING_PE_MULTIPLE", "BELOW", "20")), List.of()), false);

        ScreeningCriteria.TieBreak tieBreak = interpretation.criteria().orElseThrow().tieBreak();
        assertThat(tieBreak.metric()).isEqualTo(ScreeningMetric.TRAILING_PE_MULTIPLE);
        assertThat(tieBreak.higherFirst()).isFalse();
        assertThat(Set.of(Operator.values())).hasSize(4);
    }
}
