package com.ashish.stockresearch.focus;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.agent.RoutedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}
