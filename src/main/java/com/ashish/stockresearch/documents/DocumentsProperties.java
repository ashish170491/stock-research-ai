package com.ashish.stockresearch.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.documents")
/**
 * @param vectorStorePath where earlier versions kept the ingested documents as one JSON file (SimpleVectorStore);
 *                        read only by the one-off import into PostgreSQL ({@code migration.LegacyDataImport})
 */
public record DocumentsProperties(String vectorStorePath) {
}
