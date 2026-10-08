package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.documents.DocumentMetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Searches previously ingested company documents - annual reports, concall transcripts, investor
 * presentations (Step 7) - for passages relevant to a question about one company. A chunk's
 * figures are attributed to the document it came from, never to Yahoo Finance (S7-6): the tool's
 * own rendered text, not the model, says so.
 *
 * Every passage returned carries its citation - document type, fiscal year and page (S7-5) - both
 * in the text the model sees and recorded on {@link ToolUsage} for the Java-rendered "Document
 * sources" footer {@code AiService} adds to the answer, so a citation is shown whether or not the
 * model repeats it in words.
 */
@Component
public class DocumentSearchTools {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchTools.class);

    static final String SEARCH_TOOL = "searchCompanyDocuments";
    private static final int TOP_K = 5;
    /** Each chunk is at most this many characters in the tool result, so one search cannot flood the context. */
    private static final int MAX_CHUNK_CHARS = 600;

    private final VectorStore vectorStore;

    public DocumentSearchTools(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Tool(name = SEARCH_TOOL, description = """
            Searches documents ingested for one company - annual reports, concall transcripts, investor \
            presentations - for short passages relevant to a question. It does NOT return a whole document, a \
            summary of one, or any figure not quoted verbatim in a returned passage; if nothing has been ingested \
            for the symbol, or nothing relevant is found, it says so instead of guessing. \
            Use it for questions a quoted figure or a filed statement could answer - asset quality, capital \
            adequacy, management commentary, guidance, related-party transactions, auditor remarks - that the \
            other tools, sourced from Yahoo Finance, do not cover. \
            Quote a passage's figures exactly as shown; never recalculate them. Every passage names the document \
            type, fiscal year and page it came from - that is its source, never Yahoo Finance. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, HDFCBANK), not a company name.""",
            resultConverter = PlainTextResultConverter.class)
    public String searchCompanyDocuments(
            @ToolParam(description = "NSE/BSE trading symbol, e.g. TCS, HDFCBANK") String symbol,
            @ToolParam(description = "The question to search this company's ingested documents for") String question,
            ToolContext toolContext) {
        log.info("TOOL CALLED: searchCompanyDocuments symbol={} question={}", symbol, question);
        List<Document> results = results(symbol, question);
        String status = results.isEmpty() ? "NO_MATCH" : "OK";
        String normalized = symbol.strip().toUpperCase(Locale.ROOT).replace("'", "");
        String text = render(normalized, results);
        results.forEach(chunk -> ToolUsage.recordCitation(toolContext, new ToolUsage.Citation(citation(chunk))));
        return recorded(toolContext, SEARCH_TOOL, normalized, status, text);
    }

    /**
     * The same search and rendering, callable directly in Java - by the autonomous orchestrator's
     * SEARCH_DOCS step (roadmap Step 8), which executes every step itself rather than letting a model
     * call a tool. Records no {@link ToolUsage}: there is no {@link ToolContext} outside the tool-calling
     * loop, and the orchestrator folds this text into its own evidence pack instead.
     */
    public String search(String symbol, String question) {
        return render(symbol.strip().toUpperCase(Locale.ROOT).replace("'", ""), results(symbol, question));
    }

    private List<Document> results(String symbol, String question) {
        String normalized = symbol.strip().toUpperCase(Locale.ROOT).replace("'", "");
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .filterExpression(DocumentMetadataKeys.SYMBOL + " == '" + normalized + "'")
                .build();
        return vectorStore.similaritySearch(request);
    }

    private static String render(String symbol, List<Document> results) {
        StringBuilder text = new StringBuilder("TOOL RESULT: ").append(SEARCH_TOOL).append(" - status ")
                .append(results.isEmpty() ? "NO_MATCH" : "OK").append('\n');
        if (results.isEmpty()) {
            text.append("No ingested documents matched this question for ").append(symbol).append('.');
            return text.toString();
        }
        for (Document chunk : results) {
            text.append('[').append(citation(chunk)).append("]\n");
            String content = chunk.getText() == null ? "" : chunk.getText().strip();
            if (content.length() > MAX_CHUNK_CHARS) {
                content = content.substring(0, MAX_CHUNK_CHARS) + "...";
            }
            text.append(content).append("\n\n");
        }
        return text.toString().stripTrailing();
    }

    /** "SYMBOL — DOCTYPE FYyyyy, p. n": the citation shown both to the model and in the answer's footer. */
    private static String citation(Document chunk) {
        var metadata = chunk.getMetadata();
        Object symbol = metadata.get(DocumentMetadataKeys.SYMBOL);
        Object docType = metadata.get(DocumentMetadataKeys.DOC_TYPE);
        Object fiscalYear = metadata.get(DocumentMetadataKeys.FISCAL_YEAR);
        Object page = metadata.get(DocumentMetadataKeys.PAGE);
        return "%s — %s FY%s, p. %s".formatted(symbol, docType, fiscalYear, page);
    }

    private static String recorded(ToolContext context, String tool, String symbol, String status, String text) {
        ToolUsage.record(context, new ToolUsage.Call(tool, symbol, status, text));
        return text;
    }
}
