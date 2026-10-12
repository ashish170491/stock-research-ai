package com.ashish.stockresearch.moat;

import com.ashish.stockresearch.tool.DocumentSearchTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a company says about its own competitive position, quoted word for word from the documents ingested for it
 * (annual reports, concall transcripts, investor presentations), each with its citation. Java picks the sentences -
 * no model reads, summarises or rewords them - and every quote is credited to the company's document, never stated
 * as a fact.
 */
@Component
public class CompanyStatements {

    private static final Logger log = LoggerFactory.getLogger(CompanyStatements.class);

    /** What a company writes when it describes an edge over its rivals. */
    static final Pattern EDGE = Pattern.compile("\\b(?:competitive (?:advantage|edge|position|strength)s?|moats?"
            + "|market (?:leader|leadership|share)|leadership position|(?:the )?(?:largest|leading) \\w+"
            + "|brands?|brand equity|distribution (?:network|reach|footprint)|pricing power|patents?|proprietary"
            + "|switching costs?|network effects?|economies of scale|scale advantage|cost leadership"
            + "|barriers? to entry|entry barriers?|differentiat\\w+|long-standing relationships?)\\b",
            Pattern.CASE_INSENSITIVE);
    /** A sentence that rates or prices the shares is never quoted: no answer may carry one. */
    private static final Pattern RATING = Pattern.compile("\\b(?:buy|sell|hold|target price|fair value|"
            + "recommend\\w*|undervalued|overvalued)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\"“(])");
    private static final List<String> QUERIES = List.of(
            "competitive advantages and strengths of the company",
            "market leadership, market share and position in the industry",
            "brands, distribution network and customer relationships",
            "patents, technology, cost advantages and scale");
    private static final int PER_QUERY = 5;
    private static final int MIN_WORDS = 8;
    private static final int MAX_CHARS = 320;

    private final DocumentSearchTools documents;

    public CompanyStatements(DocumentSearchTools documents) {
        this.documents = documents;
    }

    /** One sentence quoted from a company document. */
    public record Statement(String quote, String citation) {
    }

    /**
     * @param statements the quotes, at most the number asked for
     * @param searched   false when the documents could not be searched at all (the embedding model is down)
     * @param documents  true when any passage was found for the company, whether or not one described an edge
     */
    public record Found(List<Statement> statements, boolean searched, boolean documents) {
    }

    public Found about(String symbol, int max) {
        Set<String> seen = new LinkedHashSet<>();
        List<Statement> statements = new ArrayList<>();
        boolean any = false;
        try {
            for (String query : QUERIES) {
                for (DocumentSearchTools.Passage passage : documents.passages(symbol, query, PER_QUERY)) {
                    any = true;
                    for (String sentence : sentences(passage.text())) {
                        String key = sentence.toLowerCase(Locale.ROOT).replaceAll("\\W+", " ").strip();
                        if (statements.size() < max && seen.add(key)) {
                            statements.add(new Statement(sentence, passage.citation()));
                        }
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not search {}'s documents: {}", symbol, ex.getMessage());
            return new Found(List.copyOf(statements), false, any);
        }
        return new Found(List.copyOf(statements), true, any);
    }

    /** The sentences of a passage that describe an edge, verbatim apart from whitespace and length. */
    static List<String> sentences(String passage) {
        List<String> out = new ArrayList<>();
        if (passage == null || passage.isBlank()) {
            return out;
        }
        for (String raw : SENTENCE_END.split(passage.replaceAll("\\s+", " ").strip())) {
            String sentence = raw.strip();
            if (sentence.split("\\s+").length < MIN_WORDS || !EDGE.matcher(sentence).find()
                    || RATING.matcher(sentence).find()) {
                continue;
            }
            out.add(shortened(sentence));
        }
        return out;
    }

    /** Cut at a word boundary, marked with an ellipsis, so a quote never runs on for a paragraph. */
    private static String shortened(String sentence) {
        if (sentence.length() <= MAX_CHARS) {
            return sentence;
        }
        int cut = sentence.lastIndexOf(' ', MAX_CHARS);
        return sentence.substring(0, cut > 0 ? cut : MAX_CHARS).stripTrailing() + " …";
    }
}
