package com.ashish.stockresearch.research.sector;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Maps a provider's sector / industry labels (Yahoo Finance's taxonomy) to an
 * {@link IndustryGroup}. Deterministic and deliberately conservative: an
 * unrecognised industry is OTHER, and a missing classification is UNKNOWN -
 * never guessed from the company name.
 */
@Component
public class SectorClassifier {

    private static final Set<String> NBFC_INDUSTRIES = Set.of("credit services", "mortgage finance");
    private static final Set<String> IT_INDUSTRIES = Set.of(
            "information technology services", "software - application", "software - infrastructure");

    public SectorContext classify(String sector, String industry) {
        if (blank(sector) && blank(industry)) {
            return SectorContext.of(IndustryGroup.UNKNOWN, "No sector or industry classification was available");
        }
        String basis = "Yahoo Finance classification: sector '%s', industry '%s'".formatted(
                blank(sector) ? "UNKNOWN" : sector, blank(industry) ? "UNKNOWN" : industry);
        return SectorContext.of(group(lower(sector), lower(industry)), basis);
    }

    private IndustryGroup group(String sector, String industry) {
        if (industry.startsWith("banks")) {
            return IndustryGroup.BANK;
        }
        if (industry.contains("insurance")) {
            return IndustryGroup.INSURANCE;
        }
        if (NBFC_INDUSTRIES.contains(industry)) {
            return IndustryGroup.NBFC;
        }
        if (IT_INDUSTRIES.contains(industry)) {
            return IndustryGroup.IT_SERVICES;
        }
        if (industry.startsWith("drug manufacturers") || industry.equals("biotechnology")) {
            return IndustryGroup.PHARMA;
        }
        if (industry.contains("manufactur") || sector.equals("industrials") || sector.equals("basic materials")) {
            return IndustryGroup.MANUFACTURING;
        }
        if (sector.equals("consumer defensive") || sector.equals("consumer cyclical")) {
            return IndustryGroup.CONSUMER;
        }
        if (industry.isEmpty()) {
            return IndustryGroup.UNKNOWN;
        }
        return IndustryGroup.OTHER;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String lower(String value) {
        return blank(value) ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
