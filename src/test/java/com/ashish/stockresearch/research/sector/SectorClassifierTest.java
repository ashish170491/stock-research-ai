package com.ashish.stockresearch.research.sector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SectorClassifierTest {

    private final SectorClassifier classifier = new SectorClassifier();

    @Test
    void distinguishesTheIndustryGroupsThatNeedDifferentMetrics() {
        assertThat(classifier.classify("Financial Services", "Banks - Regional").group()).isEqualTo(IndustryGroup.BANK);
        assertThat(classifier.classify("Financial Services", "Credit Services").group()).isEqualTo(IndustryGroup.NBFC);
        assertThat(classifier.classify("Financial Services", "Insurance - Life").group()).isEqualTo(IndustryGroup.INSURANCE);
        assertThat(classifier.classify("Technology", "Information Technology Services").group())
                .isEqualTo(IndustryGroup.IT_SERVICES);
        assertThat(classifier.classify("Healthcare", "Drug Manufacturers - Specialty & Generic").group())
                .isEqualTo(IndustryGroup.PHARMA);
        assertThat(classifier.classify("Industrials", "Specialty Industrial Machinery").group())
                .isEqualTo(IndustryGroup.MANUFACTURING);
        assertThat(classifier.classify("Consumer Cyclical", "Auto Manufacturers").group())
                .isEqualTo(IndustryGroup.MANUFACTURING);
        assertThat(classifier.classify("Consumer Defensive", "Household & Personal Products").group())
                .isEqualTo(IndustryGroup.CONSUMER);
        assertThat(classifier.classify("Utilities", "Utilities - Regulated Electric").group())
                .isEqualTo(IndustryGroup.OTHER);
    }

    @Test
    void appliesNoSectorContextWithoutAClassification() {
        SectorContext context = classifier.classify(null, " ");

        assertThat(context.group()).isEqualTo(IndustryGroup.UNKNOWN);
        assertThat(context.lessMeaningfulMetrics()).isEmpty();
        assertThat(context.sectorMetricsNotAvailable()).isEmpty();
        assertThat(context.note()).contains("no sector-specific interpretation may be applied");
    }

    @Test
    void marksGenericMetricsAsLessMeaningfulForBanksAndNamesTheMissingBankMetrics() {
        SectorContext bank = classifier.classify("Financial Services", "Banks - Regional");

        assertThat(bank.lessMeaningfulMetrics()).contains("EBITDA", "conventional debt-to-equity");
        assertThat(bank.sectorMetricsNotAvailable()).contains("net interest margin", "gross and net NPA");
        assertThat(bank.basis()).contains("Yahoo Finance").contains("Banks - Regional");
    }
}
