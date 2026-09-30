package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.research.FiledFinancialsProvider;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.FiledFinancials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A company's annual results exactly as it filed them with NSE, each fiscal year with the consistency
 * checks run on it - so any filed figure the application uses can be inspected and traced to its document.
 * Read-only; no model is involved.
 */
@RestController
public class FilingsController {

    /** Six fiscal years: enough for a five-year growth rate. */
    static final int DEFAULT_YEARS = 6;
    static final int MAX_YEARS = 10;

    private final ObjectProvider<FiledFinancialsProvider> provider;

    public FilingsController(ObjectProvider<FiledFinancialsProvider> provider) {
        this.provider = provider;
    }

    @GetMapping("/api/filings/data")
    public FiledFinancials filings(@RequestParam String symbol, @RequestParam(required = false) Integer years) {
        FiledFinancialsProvider filings = provider.getIfAvailable();
        if (filings == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Filed results are not available in "
                    + "this profile (the mock profile has no filings provider).");
        }
        int count = years == null ? DEFAULT_YEARS : years;
        if (count < 1 || count > MAX_YEARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "years must be between 1 and " + MAX_YEARS);
        }
        try {
            return filings.getFiledFinancials(symbol, count);
        } catch (ResearchDataUnavailableException ex) {
            throw new ResponseStatusException(ex.status() == ResearchStatus.PROVIDER_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }
}
