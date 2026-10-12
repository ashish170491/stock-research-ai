-- Chunks of ingested documents (annual reports, concall transcripts, investor presentations; roadmap Step 7),
-- searched by searchCompanyDocuments. The layout is the one Spring AI's PgVectorStore expects and validates at
-- startup (spring.ai.vectorstore.pgvector.schema-validation); 768 is nomic-embed-text's embedding size, so a
-- different embedding model needs a new migration, never an edit here.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE vector_store (
    id        UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(768)
);
CREATE INDEX spring_ai_vector_index ON vector_store USING HNSW (embedding vector_cosine_ops);
-- every search is filtered to one company
CREATE INDEX vector_store_symbol_idx ON vector_store ((metadata ->> 'symbol'));
