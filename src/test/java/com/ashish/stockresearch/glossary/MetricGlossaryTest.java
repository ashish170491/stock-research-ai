package com.ashish.stockresearch.glossary;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.Operator;
import com.ashish.stockresearch.screening.Rule;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.ScreeningPresets;
import com.ashish.stockresearch.screening.ScreeningProperties;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MetricGlossaryTest {

    private final MetricGlossary glossary = TestGlossary.glossary();

    private GlossaryEntry entry(String key) {
        return glossary.byKey(key).orElseThrow();
    }

    // S11-1: every metric a screen can test is explained.
    @Test
    void explainsEveryScreeningMetric() {
        assertThat(Arrays.stream(ScreeningMetric.values()))
                .allSatisfy(metric -> assertThat(glossary.forScreeningMetric(metric)).as(metric.name()).isPresent());
        assertThat(glossary.forScreeningMetric(ScreeningMetric.ROE_5Y_AVG_PERCENT)).contains(entry("roe"));
        assertThat(glossary.forMetricKey("peOnFiledEps")).contains(entry("pe-filed"));
        assertThat(glossary.forMetricKey("trailingEps")).contains(entry("eps"));
    }

    // S11-3: no benchmark, ideal value or judgement in any entry's own text.
    @Test
    void noEntryGivesABenchmarkOrAJudgement() {
        Pattern judgement = Pattern.compile("\\b(?:good|bad|ideal|healthy|strong|weak|cheap|expensive|attractive|"
                + "better|worse)\\b|\\bshould be\\b", Pattern.CASE_INSENSITIVE);
        Pattern number = Pattern.compile("\\d+(?:\\.\\d+)?");
        for (GlossaryEntry entry : glossary.entries()) {
            for (String text : entry.fixedText()) {
                assertThat(judgement.matcher(text).find()).as("%s: %s", entry.key(), text).isFalse();
                Matcher numbers = number.matcher(text);
                while (numbers.find()) {
                    assertThat(numbers.group()).as("%s: a number in “%s”", entry.key(), text).isEqualTo("100");
                }
            }
        }
    }

    // S11-2: each entry states its meaning, calculation and how to read it, and what to read it with.
    @Test
    void everyEntryIsComplete() {
        assertThat(glossary.entries()).allSatisfy(entry -> {
            assertThat(entry.meaning()).as(entry.key()).isNotBlank();
            assertThat(entry.calculation()).as(entry.key()).isNotBlank();
            assertThat(entry.howToRead()).as(entry.key()).isNotBlank();
            assertThat(entry.readWith()).as(entry.key()).isNotEmpty();
        });
        assertThat(glossary.entries()).filteredOn(entry -> entry.question() != MetricQuestion.GENERAL)
                .extracting(GlossaryEntry::question).contains(MetricQuestion.values()[0], MetricQuestion.PROFITABLE,
                        MetricQuestion.GROWING, MetricQuestion.CASH, MetricQuestion.STRETCHED, MetricQuestion.PRICE);
    }

    // S11-2: what an entry says about an industry comes from IndustryGroup only, never from the entry's own text.
    @Test
    void noEntryWritesItsOwnIndustryNote() {
        Pattern industry = Pattern.compile("\\b(?:banks?|bank's|NBFCs?|NBFC's|insurers?|lenders?|lender's|"
                + "financial compan(?:y|ies)|IT services|pharma\\w*)\\b", Pattern.CASE_INSENSITIVE);
        for (GlossaryEntry entry : glossary.entries()) {
            for (String text : List.of(entry.meaning(), entry.howToRead())) {
                assertThat(industry.matcher(text).find()).as("%s: %s", entry.key(), text).isFalse();
            }
        }
    }

    // S11-2: the industry notes are IndustryGroup's own, not a second copy.
    @Test
    void takesTheIndustryNotesFromTheIndustryGroups() {
        String roce = glossary.render(entry("roce"));

        assertThat(glossary.industryNotes(entry("roce")))
                .contains("Not a primary measure for banks: " + IndustryGroup.BANK.note())
                .contains("Not a primary measure for insurers: " + IndustryGroup.INSURANCE.note())
                .contains("Named as a primary measure for IT services.");
        assertThat(roce).contains(IndustryGroup.BANK.note());
        // ROA is among a bank's primary measures
        assertThat(glossary.industryNotes(entry("roa")))
                .containsExactly("Named as a primary measure for banks and NBFCs (non-bank lenders).");
    }

    // S11-4: a preset's thresholds are read from the presets, as the screen's setting.
    @Test
    void showsThePresetsThresholdsAsTheirSettingsReadFromThePresets() {
        // two presets with the same settings are named together
        assertThat(glossary.screenSettings(entry("roe"))).containsExactly("The quality-compounder and reasonable-value "
                + "screens use ROE 3y avg ≥ 15.00% for non-financial companies, ROE ≥ 14.00% for banks and ROE ≥ 15.00% "
                + "for NBFCs (non-bank lenders).");
        // companies with the same threshold are named together
        assertThat(glossary.screenSettings(entry("cagr"))).contains("The dividend screen uses Profit CAGR 5y > 0.00% "
                + "for non-financial companies, banks and NBFCs (non-bank lenders).");
        assertThat(glossary.render(entry("roe"))).contains(MetricGlossary.SETTINGS_NOTE);

        // change the preset and the glossary says so
        ScreeningProperties configured = ScreeningPresetsTest.properties();
        Map<String, ScreeningProperties.Preset> presets = new LinkedHashMap<>(configured.presets());
        ScreeningProperties.Preset quality = presets.get("quality-compounder");
        Map<String, List<Rule>> rules = new LinkedHashMap<>(quality.rules());
        List<Rule> nonFinancial = new ArrayList<>(rules.get("default"));
        nonFinancial.replaceAll(rule -> rule.metric() == ScreeningMetric.ROE_3Y_AVG_PERCENT
                ? new Rule(rule.metric(), Operator.AT_LEAST, new BigDecimal("18")) : rule);
        rules.put("default", nonFinancial);
        presets.put("quality-compounder", new ScreeningProperties.Preset(quality.description(), quality.tieBreak(),
                quality.tieBreakLowerFirst(), rules));
        MetricGlossary changed = new MetricGlossary(new ScreeningPresets(new ScreeningProperties(
                configured.snapshot(), presets, configured.vagueTerms(), configured.judgementTerms())));

        assertThat(changed.screenSettings(changed.byKey("roe").orElseThrow()))
                .anyMatch(setting -> setting.startsWith("The quality-compounder screen uses ROE 3y avg ≥ 18.00% for "
                        + "non-financial companies"));
        // a metric no screen tests has no settings
        assertThat(glossary.screenSettings(entry("ebitda"))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "What is ROCE?                                | roce            | false",
            "what does P/E mean                           | pe              | false",
            "Explain CAGR                                 | cagr            | false",
            "How do I read debt to equity?                | debt-to-equity  | false",
            "how is return on equity calculated?          | roe             | false",
            "What is a good ROE for banks?                | roe             | true",
            "What is an ideal PE ratio?                   | pe              | true",
            "What is price-to-book?                       | pb              | false",
            "what is OCF/PAT                              | ocf-to-pat      | false",
    })
    void readsTheTermAQuestionAsksAbout(String question, String key, boolean judgement) {
        MetricGlossary.Asked asked = glossary.asked(question).orElseThrow();

        assertThat(asked.entry()).contains(entry(key));
        assertThat(asked.asksForAJudgement()).isEqualTo(judgement);
    }

    @Test
    void aQuestionAboutACompanysFigureIsNotAQuestionAboutATerm() {
        assertThat(glossary.asked("What is TCS's ROE?").flatMap(MetricGlossary.Asked::entry)).isEmpty();
        assertThat(glossary.asked("Show me Infosys's results")).isEmpty();
    }

    @Test
    void answersWithTheEntryAndNeverAnIdealValue() {
        String answer = glossary.answer("What is a good ROE?");

        assertThat(answer).startsWith("There is no single good or ideal ROE: what is usual depends on the industry")
                .contains("**Return on equity (ROE)**: The profit a company made for each ₹100")
                .contains("- *How it is calculated here:* Net profit attributable to the parent's owners")
                .contains("- *Read it with:* Debt to equity (D/E); Return on assets (ROA); Return on capital employed")
                .contains(MetricGlossary.SOURCE_NOTE);
        assertThat(glossary.answer("What is ROCE?")).doesNotContain("There is no single")
                .contains(IndustryGroup.BANK.note());
    }

    @Test
    void saysWhenATermIsNotInTheGlossary() {
        assertThat(glossary.answer("What is beta?")).startsWith("“beta” is not in the application's glossary")
                .contains("never from the language model").contains("Return on equity (ROE), Return on assets (ROA)");
    }

    @Test
    void findsTheTermsAnAnswerUsesInQuestionOrder() {
        List<GlossaryEntry> found = glossary.termsIn("TCS meets ROE 3y avg ≥ 15.00% and D/E ≤ 0.50x; its P/E is "
                + "28.4x and its Profit CAGR 5y is 9.1% (FY21 to FY26).");

        assertThat(found).extracting(GlossaryEntry::key)
                .containsExactly("roe", "cagr", "debt-to-equity", "pe", "fiscal-year");
        // capitals only for an abbreviation, and not inside a longer one
        assertThat(glossary.termsIn("the pe of a company")).isEmpty();
        assertThat(glossary.termsIn("EBITDA rose")).extracting(GlossaryEntry::key).containsExactly("ebitda");
        assertThat(glossary.termsIn("| returnOnCapitalEmployedPercent | 20.72% |")).extracting(GlossaryEntry::key)
                .containsExactly("roce");
    }

    // S11-5: the section groups the entries under the questions.
    @Test
    void groupsTheSectionByQuestion() {
        String section = glossary.section(List.of(entry("pe"), entry("roe"), entry("debt-to-equity"), entry("cagr")));

        assertThat(section).startsWith("## Explain the terms\n\n" + MetricGlossary.SOURCE_NOTE);
        assertThat(section.indexOf("### Is it profitable?")).isLessThan(section.indexOf("**Return on equity (ROE)**"));
        assertThat(section.indexOf("**Return on equity (ROE)**")).isLessThan(section.indexOf("### Is it growing?"));
        assertThat(section.indexOf("### Is it growing?")).isLessThan(section.indexOf("### Is it financially stretched?"));
        assertThat(section.indexOf("### Is it financially stretched?"))
                .isLessThan(section.indexOf("### How much does the market charge for it?"));
        assertThat(glossary.section(List.of())).isEmpty();

        // an industry's reason is given once, at the end, however many entries it applies to
        String withNotes = glossary.section(List.of(entry("roce"), entry("operating-margin"), entry("debt-to-equity")));
        assertThat(withNotes).contains("- *By industry:* Not a primary measure for banks, NBFCs (non-bank lenders), "
                + "insurers and other financial companies");
        assertThat(withNotes.split(Pattern.quote(IndustryGroup.BANK.note()), -1)).hasSize(2);
        assertThat(withNotes.indexOf("### Industry notes")).isGreaterThan(withNotes.indexOf("**Debt to equity (D/E)**"));
    }

    // S11-5: a screen's criteria, arranged by question, one column per set of companies.
    @Test
    void arrangesAScreensCriteriaByQuestion() {
        String table = glossary.criteriaByQuestion(ScreeningPresetsTest.presets().find("quality-compounder").orElseThrow());

        assertThat(table).startsWith("**The criteria, by question**\n\n| Question | Non-financial companies | Banks | "
                + "NBFCs (non-bank lenders) |\n|---|---|---|---|\n");
        assertThat(table.lines().toList()).containsSubsequence(
                "| Is it profitable? | ROE 3y avg ≥ 15.00%, ROCE ≥ 15.00% | ROA ≥ 1.00%, ROE ≥ 14.00% | ROA ≥ 2.00%, "
                        + "ROE ≥ 15.00% |",
                "| Is it growing? | Revenue CAGR 5y ≥ 10.00%, Profit CAGR 5y ≥ 10.00% | - | - |",
                "| Are the profits real cash? | OCF/PAT 3y ≥ 0.80x | - | - |",
                "| Is it financially stretched? | D/E ≤ 0.50x | - | - |");
        assertThat(table).doesNotContain("How much does the market charge for it?");
    }
}
