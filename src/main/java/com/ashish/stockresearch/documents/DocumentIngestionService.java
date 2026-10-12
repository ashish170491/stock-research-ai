package com.ashish.stockresearch.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Ingests one PDF (an annual report, concall transcript or investor presentation) into the
 * document vector store (S7-2): read page by page so every chunk keeps the page it came from,
 * tag every chunk with the company and document it is about before splitting - a token text
 * splitter copies a document's metadata onto every chunk it produces, so tagging happens first.
 * The store is PostgreSQL (pgvector), so every chunk survives a restart (S7-3). Ingesting the same document again
 * (same company, type, fiscal year and name) replaces its chunks rather than adding a second copy that every search
 * would then return twice.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    public DocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.splitter = TokenTextSplitter.builder().build();
    }

    public record IngestResult(String symbol, DocType docType, int fiscalYear, int pages, int chunks) {
    }

    /**
     * @param pdf          the PDF's content
     * @param symbol       NSE/BSE trading symbol the document is about
     * @param docType      what kind of document it is
     * @param fiscalYear   the fiscal year it reports on, or was published for
     * @param documentName a human-readable name shown in citations, e.g. the original filename
     */
    public IngestResult ingest(Resource pdf, String symbol, DocType docType, int fiscalYear, String documentName) {
        String normalizedSymbol = symbol.strip().toUpperCase(Locale.ROOT);
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build();
        List<Document> pages = new PagePdfDocumentReader(pdf, config).get();
        List<Document> tagged = pages.stream()
                .map(page -> page.mutate()
                        .metadata(DocumentMetadataKeys.SYMBOL, normalizedSymbol)
                        .metadata(DocumentMetadataKeys.DOC_TYPE, docType.name())
                        .metadata(DocumentMetadataKeys.FISCAL_YEAR, fiscalYear)
                        .metadata(DocumentMetadataKeys.DOCUMENT_NAME, documentName)
                        .metadata(DocumentMetadataKeys.PAGE, pageNumber(page))
                        .build())
                .toList();
        List<Document> chunks = splitter.apply(tagged);
        vectorStore.delete(sameDocument(normalizedSymbol, docType, fiscalYear, documentName));
        vectorStore.add(chunks);
        log.info("Ingested {} page(s), {} chunk(s) of {} ({} FY{}) for {}", pages.size(), chunks.size(),
                documentName, docType, fiscalYear, normalizedSymbol);
        return new IngestResult(normalizedSymbol, docType, fiscalYear, pages.size(), chunks.size());
    }

    /** The chunks of one document as an earlier ingestion stored them. */
    static Filter.Expression sameDocument(String symbol, DocType docType, int fiscalYear, String documentName) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return b.and(b.and(b.eq(DocumentMetadataKeys.SYMBOL, symbol), b.eq(DocumentMetadataKeys.DOC_TYPE, docType.name())),
                b.and(b.eq(DocumentMetadataKeys.FISCAL_YEAR, fiscalYear),
                        b.eq(DocumentMetadataKeys.DOCUMENT_NAME, documentName))).build();
    }

    /** The reader's own page number, 1-indexed already - the page a citation names. */
    private static int pageNumber(Document page) {
        Object start = page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
        return start instanceof Number number ? number.intValue() : 0;
    }
}
