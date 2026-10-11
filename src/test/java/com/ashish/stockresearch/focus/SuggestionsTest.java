package com.ashish.stockresearch.focus;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.TestGlossary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionsTest {

    private final MetricGlossary glossary = TestGlossary.glossary();

    @Test
    void afterAQuestionAboutACompanyOffersTheRatingGrowthAndPriceHistoryAsFocusedRequests() {
        List<Suggestion> out = Suggestions.after(Intent.SPECIFIC_QUESTION, List.of("TCS"), "TCS has no debt.", glossary);

        assertThat(out).extracting(Suggestion::text).containsExactly("Is TCS a good company to invest in?",
                "Is TCS growing?", "How has TCS's share price done?");
        assertThat(out.get(0).company()).isEqualTo("TCS");
        assertThat(out.get(0).topics()).containsExactly("rating");
    }

    @Test
    void offersTheTermsAnAnswerUsesAsPlainQuestions() {
        List<Suggestion> out = Suggestions.after(Intent.SCREEN, List.of(), "5 stocks have ROE and ROCE above 15%.",
                glossary);

        assertThat(out).extracting(Suggestion::text).contains("What is ROE?", "What is ROCE?");
        assertThat(out).allSatisfy(s -> assertThat(s.topics()).isEmpty());
    }

    @Test
    void afterAFocusedAnswerOffersWhatWasNotChosenAndNeverMoreThanFour() {
        List<Suggestion> out = Suggestions.afterFocus("INFY", List.of(Topic.RATING, Topic.PROFITABLE),
                "ROE 31% and ROCE 38%, P/E 13.6x", glossary);

        assertThat(out).hasSizeLessThanOrEqualTo(Suggestions.MAX);
        assertThat(out).extracting(Suggestion::text).startsWith("Is INFY growing?", "Are INFY's profits real cash?")
                .contains("Show me the full report on INFY")
                .doesNotContain("Is INFY a good company to invest in?", "Is INFY profitable?");
    }
}
