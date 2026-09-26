package com.ashish.stockresearch.research.provider;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.ResearchStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnsupportedShareholdingProviderTest {

    private final UnsupportedShareholdingProvider provider = new UnsupportedShareholdingProvider();

    @Test
    void reportsTheCapabilityAsUnsupportedRatherThanReturningEmptyData() {
        assertThatThrownBy(() -> provider.getShareholding("TCS"))
                .isInstanceOf(ResearchDataUnavailableException.class)
                .extracting(ex -> ((ResearchDataUnavailableException) ex).status())
                .isEqualTo(ResearchStatus.CAPABILITY_NOT_SUPPORTED);
    }

    @Test
    void tellsTheModelExplicitlyNotToSubstituteZeroOrRecall() {
        assertThatThrownBy(() -> provider.getShareholding("TCS"))
                .hasMessageContaining("UNKNOWN")
                .hasMessageContaining("not report them as zero")
                .hasMessageContaining("do not answer from memory");
    }

    @Test
    void namesTheCompanyAskedAboutSoAPartialAnswerCanSayWhatIsMissing() {
        assertThatThrownBy(() -> provider.getShareholding("RELIANCE"))
                .hasMessageContaining("RELIANCE");
    }
}
