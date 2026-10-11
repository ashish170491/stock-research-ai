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

    /** A company's verdict, or why there is none: exactly one of the two is set. */
    public record Lookup(Verdict verdict, String failure) {
    }

    /** The rated answer for a company's symbol or name, or a plain message when it could not be rated. */
    public String answer(String symbolOrName) {
        Lookup lookup = lookup(symbolOrName);
        return lookup.verdict() == null ? lookup.failure() : renderer.render(lookup.verdict());
    }

    public Lookup lookup(String symbolOrName) {
        Optional<NseSymbolDirectory.Listing> listing = directory.resolve(symbolOrName);
        if (listing.isEmpty()) {
            return new Lookup(null, ("I could not find \"%s\" among the companies listed on the NSE. Check the "
                    + "name or use its NSE symbol.").formatted(symbolOrName));
        }
        try {
            FundamentalsSnapshot snapshot = snapshots.build(
                    new Universes.Member(listing.get().symbol(), listing.get().name(), null),
                    LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata"))));
            return new Lookup(engine.assess(snapshot), null);
        } catch (SnapshotBuilder.NothingRetrievedException ex) {
            log.warn("No figures for {}: {}", listing.get().symbol(), ex.getMessage());
            return new Lookup(null, ("I could not retrieve %s's figures right now. Please try again in a few "
                    + "minutes.").formatted(listing.get().name()));
        }
    }
}
