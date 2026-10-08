package com.ashish.stockresearch.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * The vector store backing {@code searchCompanyDocuments}: one JSON file (S7-3), loaded here on
 * startup if it already exists, and saved again by {@link DocumentIngestionService} after every
 * ingestion. A fresh checkout or a wiped {@code data/} directory starts with an empty store, not
 * an error.
 */
@Configuration
@EnableConfigurationProperties(DocumentsProperties.class)
public class DocumentVectorStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorStoreConfiguration.class);

    @Bean
    public SimpleVectorStore documentVectorStore(EmbeddingModel embeddingModel, DocumentsProperties properties) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File(properties.vectorStorePath());
        if (file.exists()) {
            store.load(file);
            log.info("Loaded the ingested-document vector store from {}", file);
        }
        return store;
    }
}
