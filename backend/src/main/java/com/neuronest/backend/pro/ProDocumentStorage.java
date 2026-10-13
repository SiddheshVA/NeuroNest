package com.neuronest.backend.pro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

// Private file storage for professional documents under APP_STORAGE_DIR. Files are never served by
// the app: there is no static mapping or public route for this directory. Each professional gets a
// subfolder named by their (server-side) id, and each file a random UUID name; no part of any path
// comes from the user.
@Component
public class ProDocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(ProDocumentStorage.class);

    private final Path root;

    public ProDocumentStorage(@Value("${app.storage-dir:}") String storageDir) {
        this.root = checkWritableRoot(storageDir);
        log.info("Professional documents are stored privately under the configured APP_STORAGE_DIR.");
    }

    static Path checkWritableRoot(String storageDir) {
        if (storageDir == null || storageDir.isBlank()) {
            throw new IllegalStateException("APP_STORAGE_DIR is not set. Set it to a private folder outside the "
                    + "project and outside any web root, for example C:\\neuronest-private-uploads, as an "
                    + "environment variable or in backend/.env.properties.");
        }
        Path root;
        try {
            root = Path.of(storageDir.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("APP_STORAGE_DIR is not a valid path: " + e.getReason() + ".");
        }
        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, ".write-check-", ".tmp");
            Files.delete(probe);
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException("APP_STORAGE_DIR (" + root + ") does not exist and cannot be created, or "
                    + "is not writable by the backend: " + e.getMessage());
        }
        return root;
    }

    /** Writes the file and returns its storage path relative to the storage root. */
    public String store(UUID professionalId, byte[] content, FileSignature signature) {
        String relative = professionalId + "/" + UUID.randomUUID() + "." + signature.storedExtension();
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store an uploaded document.", e);
        }
        return relative;
    }

    /** Removes a stored file; a file that is already gone is not an error. */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException | IllegalStateException e) {
            log.warn("Could not delete a stored professional document: {}", e.getMessage());
        }
    }

    // Defence in depth: every path must stay inside the root.
    private Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalStateException("Refusing a storage path outside APP_STORAGE_DIR.");
        }
        return resolved;
    }
}
