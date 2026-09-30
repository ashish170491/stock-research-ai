package com.ashish.stockresearch.marketdata.provider.nse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps every fetched filing document on disk, named as NSE names it. A filed document never changes -
 * a revision is filed as a new document - so each is downloaded once, and a nightly snapshot of fifty
 * companies fetches only the filings made since the last one.
 */
class NseDocumentStore {

    private final Path directory;

    NseDocumentStore(Path directory) {
        this.directory = directory;
    }

    /** The stored document for this link, fetching and keeping it first if it is not stored yet. */
    byte[] get(URI uri, java.util.function.Supplier<byte[]> fetch) {
        Path file = directory.resolve(Path.of(uri.getPath()).getFileName().toString()).normalize();
        if (!file.startsWith(directory.normalize())) {
            throw new IllegalArgumentException("Refusing to store outside " + directory + ": " + uri);
        }
        try {
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
            byte[] document = fetch.get();
            Files.createDirectories(directory);
            Path partial = Files.createTempFile(directory, file.getFileName().toString(), ".part");
            Files.write(partial, document);
            Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return document;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not keep the NSE document " + uri, ex);
        }
    }
}
