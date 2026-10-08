package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.documents.DocType;
import com.ashish.stockresearch.documents.DocumentIngestionService;
import com.ashish.stockresearch.documents.DocumentIngestionService.IngestResult;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Ingests one PDF - an annual report, concall transcript or investor presentation - into the
 * document vector store that {@code searchCompanyDocuments} searches (S7-2).
 */
@RestController
public class DocumentIngestionController {

    private final DocumentIngestionService ingestionService;

    public DocumentIngestionController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/api/documents")
    public IngestResult ingest(@RequestParam MultipartFile file,
            @RequestParam String symbol,
            @RequestParam DocType docType,
            @RequestParam int fiscalYear,
            @RequestParam(required = false) String documentName) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded.");
        }
        String name = documentName == null || documentName.isBlank() ? file.getOriginalFilename() : documentName;
        try {
            return ingestionService.ingest(new ByteArrayResource(file.getBytes()), symbol, docType, fiscalYear, name);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.", ex);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not parse the uploaded PDF: " + ex.getMessage(), ex);
        }
    }
}
