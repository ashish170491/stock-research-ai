package com.ashish.stockresearch.research.provider;

import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.ShareholdingProvider;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.Shareholding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder implementation that always reports the shareholding
 * capability as unavailable.
 *
 * <p><b>Why there is no real implementation.</b> The only holder data our
 * current provider (Yahoo Finance) exposes is its {@code
 * majorHoldersBreakdown} module, which reports US-market concepts -
 * "insiders" and "institutions". That is not the SEBI shareholding pattern
 * an Indian investor means by "promoter holding", and mapping one onto the
 * other would be fabrication:
 *
 * <ul>
 *   <li>The taxonomy does not line up. SEBI requires a promoter / FII / DII
 *       / public split; Yahoo publishes neither the promoter concept nor the
 *       FII-versus-DII distinction, so three of the four categories a user
 *       asks about simply do not exist in the source.</li>
 *   <li>The numbers do not line up either. Spot-checking large-cap NSE
 *       names, Yahoo's "insiders" figure lands close to the true promoter
 *       stake for some companies and is off by several percentage points for
 *       others, and its "institutions" figure diverges from the actual
 *       FII+DII total by a wide margin. Accuracy that is right sometimes is
 *       worse than no data, because nothing marks the wrong cases.</li>
 *   <li>There is no as-of date. Shareholding is a quarterly filing, and a
 *       holding percentage without the quarter it belongs to invites exactly
 *       the "stale data presented as current" error this application is
 *       built to avoid.</li>
 * </ul>
 *
 * <p>Reporting the capability as unsupported is therefore the correct
 * behaviour, not a gap to paper over. The proper fix is a provider reading
 * NSE or BSE corporate-filing data; when one exists it implements
 * {@link ShareholdingProvider} and replaces this bean, with no change to the
 * tool, the service, or the ChatClient.
 */
@Component
public class UnsupportedShareholdingProvider implements ShareholdingProvider {

    private static final Logger log = LoggerFactory.getLogger(UnsupportedShareholdingProvider.class);

    @Override
    public Shareholding getShareholding(String symbolOrName) {
        log.info("Shareholding requested for '{}' but no shareholding data source is configured", symbolOrName);
        throw new ResearchDataUnavailableException(ResearchStatus.CAPABILITY_NOT_SUPPORTED,
                ("Shareholding data is not available. This application has no source for the SEBI quarterly "
                        + "shareholding pattern (promoter / FII / DII / public split), so the promoter and "
                        + "institutional holdings of '%s' are UNKNOWN. Do not report them as zero, do not "
                        + "estimate them, and do not answer from memory - say the data could not be retrieved "
                        + "and suggest the company's NSE/BSE filings or investor-relations page.")
                        .formatted(symbolOrName));
    }
}
