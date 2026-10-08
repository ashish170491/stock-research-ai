package com.ashish.stockresearch.documents;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A deterministic, offline stand-in for an Ollama embedding model (S7-7: no Ollama in the default
 * test run). Hashes each word of the text into one of a small number of buckets, so texts sharing
 * words end up with a similar vector and a vector-store similarity search behaves sensibly,
 * without ever calling a real model.
 */
public final class FixedEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 32;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> results = new ArrayList<>();
        List<String> instructions = request.getInstructions();
        for (int i = 0; i < instructions.size(); i++) {
            results.add(new Embedding(vector(instructions.get(i)), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    private static float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        if (text == null) {
            return v;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (word.isBlank()) {
                continue;
            }
            v[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
        }
        return v;
    }
}
