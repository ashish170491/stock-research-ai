package com.ashish.stockresearch.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a company name or trading symbol to its NSE symbol from the
 * exchange's own equity list, bundled at {@code nse/equity-list.csv}
 * (columns SYMBOL, NAME OF COMPANY). Deterministic and offline: no search
 * ranking and no model, so "Bharti Airtel" always means BHARTIARTL.
 *
 * <p>Three steps, first match wins:
 * <ol>
 *   <li>the input is itself a listed symbol ("infy", "HDFCBANK");</li>
 *   <li>the normalised input equals a normalised company name - lowercased, with "ltd", "limited",
 *       "the", "and", "&amp;" and "." removed ("HDFC Bank" = "HDFC Bank Limited");</li>
 *   <li>the best token overlap (Jaccard similarity of the two word sets) at or above
 *       {@link #MIN_OVERLAP}. A tie for the best score is ambiguous and resolves to nothing -
 *       "Bank" must not silently become one bank of many.</li>
 * </ol>
 *
 * The list is a snapshot of NSE's EQUITY_L.csv; companies listed after it,
 * or on BSE only, are not in it and fall through to the next resolver.
 */
@Component
public class NseSymbolDirectory {

    private static final Logger log = LoggerFactory.getLogger(NseSymbolDirectory.class);
    static final String RESOURCE = "nse/equity-list.csv";
    /** Minimum Jaccard overlap for a partial name match: half the combined words must be shared. */
    static final double MIN_OVERLAP = 0.5;
    private static final Set<String> NOISE_WORDS = Set.of("ltd", "limited", "the", "and");

    /** A listed company. */
    public record Listing(String symbol, String name) {
    }

    private record Entry(Listing listing, Set<String> tokens, String normalisedName) {
    }

    private final Map<String, Listing> bySymbol = new HashMap<>();
    private final Map<String, Listing> byNormalisedName = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    public NseSymbolDirectory() {
        this(new ClassPathResource(RESOURCE));
    }

    /** A directory over any list in the same format, e.g. a newer EQUITY_L.csv. */
    public NseSymbolDirectory(Resource csv) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv.getInputStream(),
                StandardCharsets.UTF_8))) {
            reader.lines().skip(1).forEach(this::add);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not load the NSE equity list from " + RESOURCE, ex);
        }
        log.info("Loaded {} NSE listings from {}", bySymbol.size(), RESOURCE);
    }

    private void add(String line) {
        int comma = line.indexOf(',');
        if (comma <= 0) {
            return;
        }
        Listing listing = new Listing(line.substring(0, comma).strip().toUpperCase(Locale.ROOT),
                line.substring(comma + 1).strip());
        String normalised = normalise(listing.name());
        bySymbol.put(listing.symbol(), listing);
        byNormalisedName.putIfAbsent(normalised, listing);
        entries.add(new Entry(listing, tokens(normalised), normalised));
    }

    public Optional<Listing> resolve(String symbolOrName) {
        if (symbolOrName == null || symbolOrName.isBlank()) {
            return Optional.empty();
        }
        Listing exact = bySymbol.get(symbolOrName.strip().toUpperCase(Locale.ROOT));
        if (exact != null) {
            return Optional.of(exact);
        }
        String normalised = normalise(symbolOrName);
        Listing named = byNormalisedName.get(normalised);
        if (named != null) {
            return Optional.of(named);
        }
        return bestOverlap(tokens(normalised));
    }

    /** A listing whose symbol or whole normalised name is exactly the text; never a partial match. */
    public Optional<Listing> exact(String symbolOrName) {
        if (symbolOrName == null || symbolOrName.isBlank()) {
            return Optional.empty();
        }
        Listing bySym = bySymbol.get(symbolOrName.strip().toUpperCase(Locale.ROOT));
        return bySym != null ? Optional.of(bySym) : Optional.ofNullable(byNormalisedName.get(normalise(symbolOrName)));
    }

    /** Abbreviations a question about metrics or sectors uses that are not companies, even where a symbol matches. */
    private static final Set<String> NOT_COMPANIES = Set.of("ROE", "ROA", "ROCE", "OCF", "PAT", "EPS", "CAGR", "EBIT",
            "EBITDA", "NBFC", "NBFCS", "FMCG", "NSE", "BSE", "NIFTY", "IT", "PE", "PB", "DE", "FY", "TTM", "YOY", "INR");

    /**
     * The first listed company a piece of text names exactly: two to four consecutive words equal to a
     * company's normalised name ("Reliance Industries"), one capitalised word that is not the first
     * ("... like Infosys"), or a symbol written in capitals ("TCS"). Never a partial match, so a question
     * about "IT companies" or "banks" names none.
     */
    public Optional<Listing> namedIn(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] words = text.strip().split("[^\\p{Alnum}&.'-]+");
        for (int start = 0; start < words.length; start++) {
            for (int length = Math.min(4, words.length - start); length >= 1; length--) {
                String phrase = String.join(" ", Arrays.copyOfRange(words, start, start + length))
                        .replaceAll("['’]s$", "");
                if (phrase.isBlank()) {
                    continue;
                }
                if (length >= 2 || (start > 0 && Character.isUpperCase(phrase.charAt(0)))) {
                    Listing named = byNormalisedName.get(normalise(phrase));
                    if (named != null) {
                        return Optional.of(named);
                    }
                }
                String word = phrase.replaceAll("[.'-]+$", "");
                if (length == 1 && word.length() >= 2 && word.equals(word.toUpperCase(Locale.ROOT))
                        && word.chars().anyMatch(Character::isLetter) && !NOT_COMPANIES.contains(word)
                        && bySymbol.containsKey(word)) {
                    return Optional.of(bySymbol.get(word));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Listing> bestOverlap(Set<String> query) {
        if (query.isEmpty()) {
            return Optional.empty();
        }
        record Scored(Entry entry, double score) {
        }
        List<Scored> ranked = entries.stream()
                .map(entry -> new Scored(entry, jaccard(query, entry.tokens())))
                .filter(scored -> scored.score() >= MIN_OVERLAP)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();
        if (ranked.isEmpty() || (ranked.size() > 1 && ranked.get(0).score() == ranked.get(1).score())) {
            return Optional.empty();
        }
        return Optional.of(ranked.get(0).entry().listing());
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        long shared = a.stream().filter(b::contains).count();
        return shared == 0 ? 0 : (double) shared / (a.size() + b.size() - shared);
    }

    static String normalise(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replace("&", " ").replace(".", " ")
                .replaceAll("[^a-z0-9]+", " ");
        return String.join(" ", Arrays.stream(cleaned.split(" "))
                .filter(word -> !word.isBlank() && !NOISE_WORDS.contains(word))
                .toList());
    }

    private static Set<String> tokens(String normalised) {
        return normalised.isEmpty() ? Set.of() : new LinkedHashSet<>(Arrays.asList(normalised.split(" ")));
    }
}
