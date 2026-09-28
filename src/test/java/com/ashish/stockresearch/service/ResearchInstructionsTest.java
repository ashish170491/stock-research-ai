package com.ashish.stockresearch.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchInstructionsTest {

    @Test
    void everyPromptThatWritesAboutACompanyCarriesTheSameEvidenceRules() {
        assertThat(AiService.SYSTEM_PROMPT).contains(ResearchInstructions.EVIDENCE_RULES);
        assertThat(ResearchReportWriter.INTERPRETATION_PROMPT).contains(ResearchInstructions.EVIDENCE_RULES);
    }

    @Test
    void coversEachRuleArea() {
        assertThat(ResearchInstructions.EVIDENCE_RULES).contains(
                "1. FACT, OBSERVATION, INTERPRETATION", "2. FACTS AND CALCULATIONS", "3. SOURCE ATTRIBUTION",
                "4. CAUSALITY", "5. QUALITATIVE LABELS", "6. GROWTH TERMINOLOGY", "7. DATA STATUS, CONFLICTS AND GAPS",
                "8. SECTOR CONTEXT", "9. FINAL INTERPRETATION", "10. VALUATION AND THE COMPANY NAMED", "If it says Yahoo Finance", "grew at a",
                "NOT SUPPORTED BY CURRENT DATA SOURCE", "does not establish the specific cause");
    }

    @Test
    void containsNoExampleNumbersAModelCouldQuoteAsData() {
        assertThat(ResearchInstructions.EVIDENCE_RULES.replaceAll("\\n\\s*\\d+\\. ", "\n"))
                .doesNotContainPattern("\\d");
    }
}
