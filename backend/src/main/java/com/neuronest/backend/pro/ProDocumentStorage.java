package com.neuronest.backend.pro;

import com.neuronest.backend.professional.DocumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

// Private file storage for professional documents under APP_STORAGE_DIR. There is no static mapping or
// public route for this directory; the only way to read a file is the admin document route. New files are
// named by DocumentPaths (a folder from the name's slug and the id, a file name from the document type and
// random hex); older files keep their UUID-based paths and are read the same way. Every path is checked to
// stay inside the root.
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

    private static final int NAME_ATTEMPTS = 5;

    /**
     * Writes the file into {@code folder} (one safe folder name from DocumentPaths) and returns its storage
     * path relative to the storage root, with forward slashes. An existing file is never overwritten: a
     * name that is already taken gets new random characters.
     */
    public String store(String folder, DocumentType docType, byte[] content, FileSignature signature) {
        if (!DocumentPaths.isSafeFolder(folder)) {
            throw new IllegalStateException("Refusing a document folder name that is not safe.");
        }
        for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
            String relative = folder + "/" + DocumentPaths.newFileName(docType, signature);
            Path target = resolve(relative);
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return relative;
            } catch (FileAlreadyExistsException e) {
                // Try again with new random characters.
            } catch (IOException e) {
                throw new UncheckedIOException("Could not store an uploaded document.", e);
            }
        }
        throw new IllegalStateException("Could not find a free name for an uploaded document.");
    }

    /** Removes a stored file; a file that is already gone is not an error. */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException | IllegalStateException e) {
            log.warn("Could not delete a stored professional document: {}", e.getMessage());
        }
    }

    /**
     * The stored file for reading (only the admin document route uses this), or empty when it is missing.
     * Refuses any path that resolves outside the root, including through a symbolic link.
     */
    public Optional<Path> existingFile(String relativePath) {
        Path resolved;
        try {
            resolved = resolve(relativePath);
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Refusing a storage path that is not valid.");
        }
        if (!Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        try {
            if (!resolved.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalStateException("Refusing a storage path outside APP_STORAGE_DIR.");
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    // ---- For the one-time rename of older documents (DocumentRenamer) ----

    /** Whether a path inside the root names an existing file or folder. Refuses paths outside the root. */
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /**
     * Moves a stored file to a new path inside the root. Never overwrites: a target that exists is an error.
     * On the same disk this is a rename, which is atomic. Both paths are checked to stay inside the root,
     * the source also after following links.
     */
    public void move(String fromRelative, String toRelative) throws IOException {
        Path source = existingFile(fromRelative).orElseThrow(() -> new IOException("The file to move does not exist."));
        Path target = resolve(toRelative);
        if (Files.exists(target)) throw new FileAlreadyExistsException("The target already exists.");
        Files.createDirectories(target.getParent());
        if (!target.getParent().toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalStateException("Refusing a storage path outside APP_STORAGE_DIR.");
        }
        Files.move(source, target);
    }

    /** Removes a folder directly under the root only when it is empty; returns whether it was removed. */
    public boolean deleteFolderIfEmpty(String folder) {
        if (!DocumentPaths.isSafeFolder(folder)) return false;
        Path dir = resolve(folder);
        if (!Files.isDirectory(dir) || !dir.getParent().equals(root)) return false;
        try {
            Files.delete(dir);
            return true;
        } catch (DirectoryNotEmptyException e) {
            return false;
        } catch (IOException e) {
            log.warn("Could not remove an empty document folder: {}", e.getClass().getName());
            return false;
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
