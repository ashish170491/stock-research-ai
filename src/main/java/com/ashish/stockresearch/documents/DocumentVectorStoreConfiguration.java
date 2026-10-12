package com.ashish.stockresearch.documents;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The vector store backing {@code searchCompanyDocuments} is Spring AI's {@code PgVectorStore}, auto-configured over
 * the application's PostgreSQL (spring.ai.vectorstore.pgvector.*): the table is created by Flyway
 * (db/migration/V2__documents.sql) and checked at startup, and every chunk survives a restart because it is a row
 * (S7-3). Nothing is loaded or saved by hand.
 */
@Configuration
@EnableConfigurationProperties(DocumentsProperties.class)
public class DocumentVectorStoreConfiguration {
}
