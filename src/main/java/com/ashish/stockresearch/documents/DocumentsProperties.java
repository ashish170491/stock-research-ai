package com.ashish.stockresearch.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.documents")
/**
 * @param vectorStorePath where the ingested-document vector store is persisted as a single JSON
 *                        file; loaded on startup if present, saved again after every ingestion, so
 *                        it survives a restart without re-ingesting anything.
 */
public record DocumentsProperties(String vectorStorePath) {
}
