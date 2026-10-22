package com.neuronest.backend.pro;

import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One-time move of older documents (UUID folder and file names) to the readable names new uploads get
 * (DocumentPaths). Started by DocumentRenameConfig only when RENAME_DOCUMENTS is "dry" or "apply".
 * <ul>
 *   <li>All of a professional's documents go into one folder: their existing new-style folder if they have
 *   one, otherwise one built from their current name and id.</li>
 *   <li>A document is left alone when its path is already new style, when its file is not on this machine
 *   (its row is not touched either), when its stored path is unusable or outside APP_STORAGE_DIR, or when
 *   the target already exists.</li>
 *   <li>"apply" moves each file (never overwriting) and then updates that one row in its own transaction; if
 *   the row cannot be updated, the file is moved back. Afterwards an old folder is removed only if empty.</li>
 * </ul>
 * Running it again finds everything already new. It logs counts only: never names, file names or paths.
 */
final class DocumentRenamer {

    enum Mode { DRY, APPLY }

    /** In a dry run, {@code moved} is the number that would be moved. */
    record Result(int examined, int moved, int alreadyNew, int missingHere, int skipped, int failed,
                  int oldFoldersRemoved) {}

    private static final Logger log = LoggerFactory.getLogger(DocumentRenamer.class);

    private final ProfessionalDocumentRepository documents;
    private final ProfessionalRepository professionals;
    private final ProDocumentStorage storage;
    private final TransactionOperations transactions;

    DocumentRenamer(ProfessionalDocumentRepository documents, ProfessionalRepository professionals,
                    ProDocumentStorage storage, TransactionOperations transactions) {
        this.documents = documents;
        this.professionals = professionals;
        this.storage = storage;
        this.transactions = transactions;
    }

    private static final class Counts {
        int examined, moved, alreadyNew, missingHere, skipped, failed, oldFoldersRemoved;

        Result result() {
            return new Result(examined, moved, alreadyNew, missingHere, skipped, failed, oldFoldersRemoved);
        }
    }

    Result run(Mode mode) {
        Counts counts = new Counts();
        for (UUID professionalId : documents.findProfessionalIdsWithDocuments()) {
            try {
                renameFor(professionalId, mode, counts);
            } catch (RuntimeException e) {
                counts.failed++;
                log.warn("Document rename: one professional's documents could not be processed: {}", e.getClass().getName());
            }
        }
        Result result = counts.result();
        if (mode == Mode.DRY) {
            log.info("Document rename, dry run (nothing changed): examined {}, would move {}, already new {}, "
                            + "missing here {}, skipped because of a conflict {}.",
                    result.examined(), result.moved(), result.alreadyNew(), result.missingHere(), result.skipped());
        } else {
            log.info("Document rename applied: moved {}, failed {}, missing here {}, skipped {}, already new {}, "
                            + "old folders removed {}.",
                    result.moved(), result.failed(), result.missingHere(), result.skipped(), result.alreadyNew(),
                    result.oldFoldersRemoved());
        }
        return result;
    }

    private void renameFor(UUID professionalId, Mode mode, Counts counts) {
        List<ProfessionalDocument> docs = documents.findByProfessionalIdOrderByUploadedAtAsc(professionalId);
        String folder = targetFolder(professionalId, docs);
        Set<String> oldFolders = new LinkedHashSet<>();

        for (ProfessionalDocument doc : docs) {
            counts.examined++;
            String oldPath = doc.getStoragePath();
            if (DocumentPaths.isNewStyle(oldPath)) {
                counts.alreadyNew++;
                continue;
            }
            Optional<Path> file;
            try {
                file = storage.existingFile(oldPath);
            } catch (IllegalStateException e) {
                // A stored path that is not valid or points outside APP_STORAGE_DIR: never touched.
                counts.skipped++;
                continue;
            }
            if (file.isEmpty()) {
                counts.missingHere++;
                continue;
            }
            Optional<String> extension = DocumentPaths.storedExtensionOf(oldPath);
            if (extension.isEmpty()) {
                counts.skipped++;
                continue;
            }
            String newPath = folder + "/" + DocumentPaths.newFileName(doc.getDocType(), extension.get());
            if (storage.exists(newPath)) {
                counts.skipped++;
                continue;
            }
            if (mode == Mode.DRY) {
                counts.moved++;
                continue;
            }

            try {
                storage.move(oldPath, newPath);
            } catch (FileAlreadyExistsException e) {
                counts.skipped++;
                continue;
            } catch (IOException | RuntimeException e) {
                counts.failed++;
                log.warn("Document rename: a file could not be moved: {}", e.getClass().getName());
                continue;
            }
            if (!updateRow(doc.getId(), oldPath, newPath)) {
                moveBack(newPath, oldPath);
                counts.failed++;
                continue;
            }
            counts.moved++;
            DocumentPaths.folderOf(oldPath).ifPresent(oldFolders::add);
        }

        if (mode == Mode.APPLY) {
            for (String oldFolder : oldFolders) {
                if (!oldFolder.equals(folder) && storage.deleteFolderIfEmpty(oldFolder)) counts.oldFoldersRemoved++;
            }
        }
    }

    // Their existing new-style folder, so their files stay together; otherwise one from the current name.
    private String targetFolder(UUID professionalId, List<ProfessionalDocument> docs) {
        return docs.stream()
                .map(ProfessionalDocument::getStoragePath)
                .filter(DocumentPaths::isNewStyle)
                .map(DocumentPaths::folderOf)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseGet(() -> DocumentPaths.newFolder(
                        professionals.findById(professionalId).map(Professional::getFullName).orElse(null), professionalId));
    }

    // One row per transaction; only a row that still has the old path is changed.
    private boolean updateRow(UUID documentId, String oldPath, String newPath) {
        try {
            Integer changed = transactions.execute(status -> documents.updateStoragePath(documentId, oldPath, newPath));
            return changed != null && changed == 1;
        } catch (RuntimeException e) {
            log.warn("Document rename: a document row could not be updated: {}", e.getClass().getName());
            return false;
        }
    }

    private void moveBack(String newPath, String oldPath) {
        try {
            storage.move(newPath, oldPath);
        } catch (IOException | RuntimeException e) {
            log.error("Document rename: a moved file could not be moved back after a failed update: {}",
                    e.getClass().getName());
        }
    }
}
