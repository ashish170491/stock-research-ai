package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.documents.DocumentIngestionService;
import com.ashish.stockresearch.documents.DocumentsProperties;
import com.ashish.stockresearch.documents.FixedEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/documents} (S7-2): a PDF upload with its metadata reaches the ingestion service. */
class DocumentIngestionControllerTest {

    @TempDir
    Path tempDir;

    private MockMvc mvc;

    @BeforeEach
    void wireUpTheController() {
        SimpleVectorStore store = SimpleVectorStore.builder(new FixedEmbeddingModel()).build();
        DocumentIngestionService service = new DocumentIngestionService(store,
                new DocumentsProperties(tempDir.resolve("vector-store.json").toString()));
        mvc = MockMvcBuilders.standaloneSetup(new DocumentIngestionController(service)).build();
    }

    @Test
    void ingestsTheUploadedPdfAndReportsPagesAndChunks() throws Exception {
        byte[] pdf = new ClassPathResource("fixtures/documents/hdfcbank-annual-report-fy2026.pdf")
                .getContentAsByteArray();
        MockMultipartFile file = new MockMultipartFile("file", "hdfcbank-annual-report-fy2026.pdf",
                "application/pdf", pdf);

        mvc.perform(multipart("/api/documents").file(file)
                        .param("symbol", "hdfcbank")
                        .param("docType", "ANNUAL_REPORT")
                        .param("fiscalYear", "2026")
                        .param("documentName", "HDFC Bank Annual Report FY2026.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("HDFCBANK"))
                .andExpect(jsonPath("$.docType").value("ANNUAL_REPORT"))
                .andExpect(jsonPath("$.fiscalYear").value(2026))
                .andExpect(jsonPath("$.pages").value(3))
                .andExpect(jsonPath("$.chunks").value(3));
    }

    @Test
    void rejectsAnEmptyUpload() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        mvc.perform(multipart("/api/documents").file(empty)
                        .param("symbol", "HDFCBANK")
                        .param("docType", "ANNUAL_REPORT")
                        .param("fiscalYear", "2026"))
                .andExpect(status().isBadRequest());
    }
}
