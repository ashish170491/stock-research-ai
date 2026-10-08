package com.ashish.stockresearch.documents;

/**
 * Metadata keys a chunk in the document vector store carries, set by {@link DocumentIngestionService}
 * before splitting and read back by {@code DocumentSearchTools} to filter and cite results (S7-2, S7-5).
 */
public final class DocumentMetadataKeys {

    public static final String SYMBOL = "symbol";
    public static final String DOC_TYPE = "docType";
    public static final String FISCAL_YEAR = "fiscalYear";
    public static final String PAGE = "page";
    public static final String DOCUMENT_NAME = "document";

    private DocumentMetadataKeys() {
    }
}
