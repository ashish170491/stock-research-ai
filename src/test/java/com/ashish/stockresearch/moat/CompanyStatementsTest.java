package com.ashish.stockresearch.moat;

import com.ashish.stockresearch.tool.DocumentSearchTools;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sentences about the company's position, quoted word for word with their citation; nothing is reworded. */
class CompanyStatementsTest {

    private static final String PASSAGE = "Revenue rose in the year. We are the market leader in decorative paints in "
            + "India with a share of over half the organised market. Our distribution network reaches more than "
            + "one lakh sixty thousand retail touchpoints across the country. Analysts recommend investors buy our "
            + "shares at the current price because of our strong brand. Brands matter.";

    private final DocumentSearchTools documents = mock(DocumentSearchTools.class);
    private final CompanyStatements statements = new CompanyStatements(documents);

    @Test
    void keepsOnlySentencesAboutAnEdgeVerbatimAndNeverOneThatRatesTheShares() {
        List<String> sentences = CompanyStatements.sentences(PASSAGE);

        assertThat(sentences).containsExactly(
                "We are the market leader in decorative paints in India with a share of over half the organised market.",
                "Our distribution network reaches more than one lakh sixty thousand retail touchpoints across the "
                        + "country.");
    }

    @Test
    void cutsALongSentenceAtAWordAndMarksIt() {
        String longOne = "Our brand " + "is trusted by households across many towns and villages ".repeat(10) + "today.";

        String quote = CompanyStatements.sentences(longOne).get(0);

        assertThat(quote).hasSizeLessThanOrEqualTo(322).endsWith(" …");
        assertThat(longOne).startsWith(quote.substring(0, quote.length() - 2));
    }

    @Test
    void quotesAtMostTheNumberAskedForWithoutRepeatingASentenceAcrossSearches() {
        when(documents.passages(eq("ASIANPAINT"), anyString(), anyInt())).thenReturn(List.of(
                new DocumentSearchTools.Passage("ASIANPAINT — ANNUAL_REPORT FY2026, p. 14", PASSAGE)));

        CompanyStatements.Found found = statements.about("ASIANPAINT", 3);

        assertThat(found.searched()).isTrue();
        assertThat(found.documents()).isTrue();
        assertThat(found.statements()).hasSize(2).allSatisfy(statement ->
                assertThat(statement.citation()).isEqualTo("ASIANPAINT — ANNUAL_REPORT FY2026, p. 14"));
        assertThat(statements.about("ASIANPAINT", 1).statements()).hasSize(1);
    }

    @Test
    void saysWhenNothingHasBeenAddedForTheCompany() {
        when(documents.passages(anyString(), anyString(), anyInt())).thenReturn(List.of());

        CompanyStatements.Found found = statements.about("SKYGOLD", 3);

        assertThat(found.statements()).isEmpty();
        assertThat(found.searched()).isTrue();
        assertThat(found.documents()).isFalse();
    }

    @Test
    void aSearchThatFailsIsReportedNotThrown() {
        when(documents.passages(anyString(), anyString(), anyInt())).thenThrow(new IllegalStateException("embedding "
                + "model unreachable"));

        CompanyStatements.Found found = statements.about("TCS", 3);

        assertThat(found.searched()).isFalse();
        assertThat(found.statements()).isEmpty();
    }
}
