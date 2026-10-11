package com.ashish.stockresearch.focus;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.agent.RoutedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarifierTest {

    private static RoutedRequest routed(Intent intent, String... companies) {
        return new RoutedRequest(intent, List.of(companies));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Marksans Pharma|FULL_RESEARCH",
            "Tell me about TCS|FULL_RESEARCH",
            "Is Infosys a good company to invest in?|FULL_RESEARCH",
            "How is Marksans doing?|SPECIFIC_QUESTION",
            "What do you think of Wipro?|SPECIFIC_QUESTION",
            "Should I buy ITC?|SPECIFIC_QUESTION",
    })
    void aBroadQuestionAboutOneCompanyIsAskedAbout(String message, Intent intent) {
        assertThat(Clarifier.needsFocus(message, routed(intent, "X"))).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Research Infosys|FULL_RESEARCH",
            "Give me a fundamental overview of HDFC Bank|FULL_RESEARCH",
            "Full analysis of TCS|FULL_RESEARCH",
            "What is the debt of TCS?|SPECIFIC_QUESTION",
            "How has it performed over 5 years?|SPECIFIC_QUESTION",
            "Price of TCS|QUOTE",
            "What is ROE?|EXPLAIN_TERM",
    })
    void aPreciseQuestionOrAnAskForTheReportIsAnsweredStraightAway(String message, Intent intent) {
        assertThat(Clarifier.needsFocus(message, routed(intent, "X"))).isFalse();
    }

    @Test
    void aQuestionAboutNoCompanyOrSeveralIsNeverAskedAbout() {
        assertThat(Clarifier.needsFocus("Tell me about them", routed(Intent.FULL_RESEARCH))).isFalse();
        assertThat(Clarifier.needsFocus("Tell me about TCS and Infosys", routed(Intent.FULL_RESEARCH, "TCS", "INFY")))
                .isFalse();
    }

    @Test
    void offersEveryTopicAsCheckboxesWithProfitAndGrowthTickedByDefault() {
        Clarification c = Clarifier.clarification("Tell me about TCS", routed(Intent.FULL_RESEARCH, "TCS"), List.of())
                .orElseThrow();

        assertThat(c.multiSelect()).isTrue();
        assertThat(c.options()).extracting(Clarification.Option::id).containsExactly("rating", "profitable", "growing",
                "cash", "stretched", "price", "performance", "full-report");
        assertThat(c.options()).filteredOn(Clarification.Option::selected).extracting(Clarification.Option::id)
                .containsExactly("profitable", "growing");
        assertThat(Clarifier.asText(c)).startsWith("What would you like to know about TCS? Pick one or more:")
                .contains("- Overall rating: Buy, Hold or Sell").endsWith("Or type your own question.");
    }

    // A typed reply to the choices: the labels as shown, or plain words.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Overall rating: Buy, Hold or Sell|rating",
            "Is it growing?; Does it carry a lot of debt?|growing,stretched",
            "rating and debt|rating,stretched",
            "how profitable is it, and is it growing|profitable,growing",
            "Are the profits real cash?|cash",
            "show me everything|full-report",
    })
    void readsATypedChoice(String reply, String ids) {
        assertThat(Topic.matching(reply)).extracting(Topic::id).containsExactly(ids.split(","));
    }

    @Test
    void aReplyThatNamesNoTopicPicksNothing() {
        assertThat(Topic.matching("ok thanks")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Are valuations expensive?|price",
            "Is the stock overvalued?|price",
            "Is the debt high?|stretched",
            "Is it profitable?|profitable",
            "Is the company growing?|growing",
            "How has the share price performed?|performance",
    })
    void readsAGeneralQuestionOnATopic(String question, String ids) {
        assertThat(Topic.askedAbout(question)).extracting(Topic::id).containsExactly(ids.split(","));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"What was the debt in FY24?", "Compare its debt with TCS", "What is the share price of TCS?",
            "What was the revenue last quarter?", "Who is the CEO?"})
    void aNarrowerQuestionIsLeftToTheResearchTools(String question) {
        assertThat(Topic.askedAbout(question)).isEmpty();
    }

    @Test
    void asksWhichCompanyWithRadioButtonsOfTheCompaniesDiscussed() {
        Clarification c = Clarifier.whichCompany(List.of("TCS", "INFY"));

        assertThat(c.kind()).isEqualTo(Clarification.COMPANY);
        assertThat(c.multiSelect()).isFalse();
        assertThat(Clarifier.asText(c)).isEqualTo("Which company is this about? Pick one, or type another company's "
                + "name:\n\n- TCS\n- INFY");
    }
}
