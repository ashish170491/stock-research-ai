package com.ashish.stockresearch.moat;

import com.ashish.stockresearch.context.RatioContextService;
import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import com.ashish.stockresearch.screening.SnapshotMetric;
import com.ashish.stockresearch.verdict.VerdictRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningMetric.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The lasting-edge answer: counted signs with their figures, the price, the company's own words, and the limits. */
class MoatRendererTest {

    private final MoatAssessor assessor = new MoatAssessor(ScreeningPresetsTest.presets(), MoatProperties.defaults());
    private final MoatRenderer renderer = new MoatRenderer(TestGlossary.glossary(), mock(RatioContextService.class));

    private static final CompanyStatements.Found NO_DOCUMENTS = new CompanyStatements.Found(List.of(), true, false);

    private MoatSigns asianPaints() {
        return assessor.assess(stock("ASIANPAINT", IndustryGroup.CONSUMER)
                .with(ROCE_5Y_MIN_PERCENT, "24").with(OPERATING_MARGIN_5Y_RANGE, "3.4")
                .with(REVENUE_CAGR_5Y_PERCENT, "8").with(DEBT_TO_EQUITY_MULTIPLE, "0.1")
                .with(SnapshotMetric.unavailable(OCF_TO_PAT_5Y_MULTIPLE, "the cash-flow statement does not report it"))
                .with(TRAILING_PE_MULTIPLE, "52.4").with(PRICE_TO_BOOK_MULTIPLE, "12.1").build());
    }

    @Test
    void countsTheSignsAndShowsEachWithTheFigureItWasDecidedOn() {
        String md = renderer.render(asianPaints(), null, NO_DOCUMENTS);

        assertThat(md).startsWith("### " + MoatRenderer.HEADING)
                .contains("**3 of 5 signs found**").contains("(1 could not be checked)")
                .contains("- Found - High return on capital in every year: return on capital employed, lowest of the "
                        + "last 5 fiscal years ≥ 15.00% - **24.00%** (FY26)")
                .contains("- Found - An operating margin that held steady: operating margin, highest minus lowest of "
                        + "the last 5 fiscal years ≤ 5.00 percentage points - **3.40 percentage points** (FY26)")
                .contains("- Not found - Sales that grew:")
                .contains("Could not be checked:\n- Profit that came in as cash: the company's filing or the data "
                        + "source does not report it");
    }

    @Test
    void showsWhatTheMarketChargesAndWhyInvestorsMayPayMoreFromTheGlossary() {
        String md = renderer.render(asianPaints(), null, NO_DOCUMENTS);

        assertThat(md).contains("#### What the market charges for it")
                .contains("- Trailing P/E at the snapshot's share price: **52.40x**")
                .contains("- Price-to-book at the snapshot's share price: **12.10x**")
                .contains("_Why investors may pay more: investors often pay more for each rupee of today's earnings");
    }

    @Test
    void quotesTheCompanyWithItsCitationAndCreditsTheClaimToIt() {
        CompanyStatements.Found found = new CompanyStatements.Found(List.of(new CompanyStatements.Statement(
                "We are the market leader in decorative paints in India.", "ASIANPAINT — ANNUAL_REPORT FY2026, p. 14")),
                true, true);

        String md = renderer.render(asianPaints(), null, found);

        assertThat(md).contains("> “We are the market leader in decorative paints in India.”  \n> — ASIANPAINT — "
                        + "ANNUAL_REPORT FY2026, p. 14")
                .contains("_Quoted word for word from ASIANPAINT Limited's own documents: these are the company's "
                        + "claims, not facts the application has checked._");
    }

    @Test
    void saysPlainlyWhenThereIsNothingOfTheCompanysOwnToQuote() {
        assertThat(renderer.render(asianPaints(), null, NO_DOCUMENTS))
                .contains("No annual report, concall transcript or investor presentation has been added for ASIANPAINT");
        assertThat(renderer.render(asianPaints(), null, new CompanyStatements.Found(List.of(), true, true)))
                .contains("Nothing in the documents added for ASIANPAINT describes its competitive position");
        assertThat(renderer.render(asianPaints(), null, new CompanyStatements.Found(List.of(), false, false)))
                .contains("could not be searched right now");
    }

    @Test
    void namesWhatTheFiguresCannotShowForTheCompanysIndustry() {
        MoatSigns bank = assessor.assess(stock("HDFCBANK", IndustryGroup.BANK).with(ROE_5Y_MIN_PERCENT, "15")
                .with(NET_PROFIT_CAGR_5Y_PERCENT, "18").with(PRICE_TO_BOOK_MULTIPLE, "2.6").build());

        String md = renderer.render(bank, null, NO_DOCUMENTS);

        assertThat(md).contains("**2 of 2 signs found**")
                .contains("- For bank companies an edge also shows in net interest margin, gross and net NPA");
    }

    @Test
    void anIndustryWithNoSignsSaysWhy() {
        MoatSigns insurer = assessor.assess(stock("HDFCLIFE", IndustryGroup.INSURANCE).build());

        String md = renderer.render(insurer, assessor.whyNoSigns(insurer), NO_DOCUMENTS);

        assertThat(md).contains("No signs are defined for insurance companies (the moat-signs criteria define no "
                + "rules for INSURANCE companies).").doesNotContain("signs found");
    }

    @Test
    void neverSaysTheCompanyHasAMoatOrRatesItsShares() {
        String md = renderer.render(asianPaints(), null, NO_DOCUMENTS);

        assertThat(Pattern.compile("\\b(?:has|have|enjoys|with) a (?:wide |narrow |strong )?moat\\b|\\b(?:buy|sell|"
                + "hold|target price|fair value|undervalued|overvalued)\\b", Pattern.CASE_INSENSITIVE).matcher(md)
                .find()).isFalse();
        assertThat(md).contains(VerdictRenderer.NOT_ADVICE);
    }
}
