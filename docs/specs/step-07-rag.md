# Step 7: RAG over annual reports and concall transcripts

| ID | Type | Criterion |
| --- | --- | --- |
| S7-1 | CODE | A PDF document reader, a persisted vector store and an Ollama embedding model are configured. The embedding model name is in config. Retrieval is an explicit `@Tool` (`searchCompanyDocuments`) calling the vector store directly, not a `QuestionAnswerAdvisor`/`RetrievalAugmentationAdvisor`: that advisor injects retrieved text into the prompt before the model answers, outside `ToolUsage`, so it would never be recorded as evidence for `NumericClaimVerifier` and `UnsupportedClaimFilter` without duplicating that plumbing - the same reason Step 1 chose a manually-managed `ChatMemory` over `MessageChatMemoryAdvisor`. |
| S7-2 | CODE | `POST /api/documents` ingests a PDF with metadata `symbol`, `docType`, `fiscalYear`, `page`. Chunks come from a token text splitter. |
| S7-3 | CODE | The vector store survives restarts (file-persisted SimpleVectorStore or PGVector). There's a test or clear load-on-startup code. |
| S7-4 | CODE | `searchCompanyDocuments(symbol, question)` filters by symbol. Test: a chunk for another symbol is never returned. |
| S7-5 | CODE | Results carry citations (document, fiscal year, page), and answers show them. |
| S7-6 | CODE | Document chunks count as evidence for `NumericClaimVerifier`, and figures are attributed to the document, not Yahoo (test). |
| S7-7 | CODE | Unit tests use a small fixture PDF and a deterministic or fake embedding model. No Ollama in the default test run. |
| S7-L1 | LIVE | After ingesting one annual report, a question about it answers with page citations, and every cited page contains the quoted text. |
