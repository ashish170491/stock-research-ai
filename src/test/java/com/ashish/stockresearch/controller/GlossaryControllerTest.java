package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningMetric;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlossaryControllerTest {

    @Test
    void servesTheGlossaryWithItsIndustryNotesAndScreenSettings() {
        GlossaryController.Glossary glossary = new GlossaryController(TestGlossary.glossary()).glossary();

        assertThat(glossary.note()).startsWith("From the application's glossary").doesNotContain("_");
        GlossaryController.Entry roce = glossary.entries().stream().filter(e -> e.key().equals("roce")).findFirst()
                .orElseThrow();
        assertThat(roce.question()).isEqualTo("Is it profitable?");
        assertThat(roce.screeningMetrics()).containsExactly(ScreeningMetric.ROCE_PERCENT);
        assertThat(roce.industryNotes()).anyMatch(note -> note.contains(IndustryGroup.BANK.note()));
        assertThat(roce.screenSettings()).isNotEmpty();
        assertThat(roce.readWith()).contains("Return on equity (ROE)");
    }
}
