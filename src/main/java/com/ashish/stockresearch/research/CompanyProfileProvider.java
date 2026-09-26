package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.CompanyProfile;

/**
 * Supplies descriptive company information. Implementations own the
 * translation from a logical symbol or company name into whatever identifier
 * their upstream source needs.
 */
public interface CompanyProfileProvider {

    /**
     * @param symbolOrName an NSE/BSE trading symbol (e.g. "TCS") or company name
     *                     (e.g. "Tata Consultancy Services")
     * @throws ResearchDataUnavailableException if the profile cannot be retrieved
     */
    CompanyProfile getCompanyProfile(String symbolOrName);
}
