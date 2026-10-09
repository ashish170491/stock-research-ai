package com.ashish.stockresearch.verdict;

import com.ashish.stockresearch.marketdata.NseSymbolDirectory;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.SnapshotBuilder;
import com.ashish.stockresearch.screening.Universes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Rates one company on request. Its figures are captured now, with the same builder the stored snapshot uses,
 * so any NSE-listed company can be rated, not only those in a stored index. Everything it returns is rendered
 * in Java ({@link VerdictRenderer}).
 */
@Service
public class VerdictService {

    private static final Logger log = LoggerFactory.getLogger(VerdictService.class);

    private final NseSymbolDirectory directory;
    private final SnapshotBuilder snapshots;
    private final VerdictEngine engine;
    private final VerdictRenderer renderer;
    private final Clock clock;

    public VerdictService(NseSymbolDirectory directory, SnapshotBuilder snapshots, VerdictEngine engine,
                          VerdictRenderer renderer, Clock clock) {
        this.directory = directory;
        this.snapshots = snapshots;
        this.engine = engine;
        this.renderer = renderer;
        this.clock = clock;
    }

    /** The rated answer for a company's symbol or name, or a plain message when it could not be rated. */
    public String answer(String symbolOrName) {
        Optional<NseSymbolDirectory.Listing> listing = directory.resolve(symbolOrName);
        if (listing.isEmpty()) {
            return ("I could not find \"%s\" among the companies listed on the NSE, so I cannot rate it. Check the "
                    + "name or use its NSE symbol.").formatted(symbolOrName);
        }
        try {
            FundamentalsSnapshot snapshot = snapshots.build(
                    new Universes.Member(listing.get().symbol(), listing.get().name(), null),
                    LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata"))));
            return renderer.render(engine.assess(snapshot));
        } catch (SnapshotBuilder.NothingRetrievedException ex) {
            log.warn("No rating for {}: {}", listing.get().symbol(), ex.getMessage());
            return ("I could not retrieve the figures I need to rate %s right now, so there is no rating. Please try "
                    + "again in a few minutes.").formatted(listing.get().name());
        }
    }
}
