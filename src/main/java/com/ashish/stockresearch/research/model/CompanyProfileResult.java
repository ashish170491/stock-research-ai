package com.ashish.stockresearch.research.model;

/**
 * Structured tool result: the model receives either a populated
 * {@link CompanyProfile} or an explicit status explaining why not - never a
 * fabricated or zero-filled object.
 */
public record CompanyProfileResult(
        boolean success,
        ResearchStatus status,
        CompanyProfile companyProfile,
        String message
) {

    public static CompanyProfileResult success(CompanyProfile companyProfile) {
        return new CompanyProfileResult(true, ResearchStatus.OK, companyProfile, null);
    }

    public static CompanyProfileResult failure(ResearchStatus status, String message) {
        return new CompanyProfileResult(false, status, null, message);
    }
}
