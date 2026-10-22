package com.neuronest.backend.pro;

import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProDtos.DocumentView;
import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

// Uploading and removing the professional's own verification documents.
@Service
public class ProDocumentService {

    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int MAX_DOCUMENTS = 10;
    static final int MAX_FILENAME = 255;

    private final ProProfileService profiles;
    private final ProfessionalDocumentRepository documents;
    private final ProDocumentStorage storage;

    public ProDocumentService(ProProfileService profiles, ProfessionalDocumentRepository documents,
                              ProDocumentStorage storage) {
        this.profiles = profiles;
        this.documents = documents;
        this.storage = storage;
    }

    @Transactional
    public DocumentView upload(ProPrincipal principal, String docTypeValue, MultipartFile file) {
        ProfessionalAccount account = profiles.account(principal);
        Professional p = profiles.professional(account);
        requireEditable(p);

        DocumentType docType = ProProfileService.parseEnum(DocumentType.class, "docType", docTypeValue);
        if (docType == null) {
            throw new ApiValidationException("docType is required. Allowed values: REGISTRATION_CERTIFICATE, ID_PROOF, "
                    + "DEGREE_CERTIFICATE, OTHER.");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiValidationException("file is required and must not be empty.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ProExceptions.FileTooLargeException();
        }
        if (documents.countByProfessionalId(p.getId()) >= MAX_DOCUMENTS) {
            throw new ConflictException("You can upload at most " + MAX_DOCUMENTS + " documents. Delete one before "
                    + "uploading another.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // The real type comes from the file's leading bytes; the declared content type is ignored.
        FileSignature signature = FileSignature.detect(content).orElseThrow(() -> new ApiValidationException(
                "Only PDF, JPG and PNG files are accepted, and this file's contents are none of those."));
        String originalFilename = cleanFilename(file.getOriginalFilename());
        if (!signature.matchesExtensionOf(originalFilename)) {
            throw new ApiValidationException("The file's contents are " + signature.name()
                    + ", which does not match its file name extension.");
        }

        String storagePath = storage.store(folderFor(p), docType, content, signature);
        try {
            ProfessionalDocument saved = documents.saveAndFlush(new ProfessionalDocument(p.getId(), docType, storagePath,
                    originalFilename, signature.contentType(), content.length));
            // If the transaction still rolls back after this point, don't leave an orphan file behind.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) storage.delete(storagePath);
                }
            });
            return DocumentView.from(saved);
        } catch (RuntimeException e) {
            storage.delete(storagePath);
            throw e;
        }
    }

    @Transactional
    public void delete(ProPrincipal principal, String docIdValue) {
        ProfessionalAccount account = profiles.account(principal);
        Professional p = profiles.professional(account);
        requireEditable(p);
        UUID docId;
        try {
            docId = UUID.fromString(docIdValue);
        } catch (IllegalArgumentException e) {
            throw new ProExceptions.DocumentNotFoundException();
        }
        // Only this professional's documents can be found here; anyone else's id is simply "not found".
        ProfessionalDocument document = documents.findByIdAndProfessionalId(docId, p.getId())
                .orElseThrow(ProExceptions.DocumentNotFoundException::new);
        documents.delete(document);
        String storagePath = document.getStoragePath();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.delete(storagePath);
            }
        });
    }

    // A professional's files stay together: later uploads reuse the folder of their earliest document, so
    // a name change never splits them. Only the first upload computes a folder from the name.
    private String folderFor(Professional p) {
        return documents.findByProfessionalIdOrderByUploadedAtAsc(p.getId()).stream()
                .map(d -> DocumentPaths.folderOf(d.getStoragePath()))
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseGet(() -> DocumentPaths.newFolder(p.getFullName(), p.getId()));
    }

    private static void requireEditable(Professional p) {
        if (!SubmissionChecklist.isEditable(p.getVerificationStatus())) {
            throw new ConflictException("Documents can only be changed while your profile is a draft or needs more "
                    + "information. Its current status is " + p.getVerificationStatus() + ".");
        }
    }

    // Keeps only a display name: no directories, no control characters, limited length. It is stored
    // for display and never used to build a path.
    static String cleanFilename(String name) {
        if (name == null) return null;
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("\\p{Cntrl}", "").trim();
        if (base.isEmpty()) return null;
        return base.length() > MAX_FILENAME ? base.substring(base.length() - MAX_FILENAME) : base;
    }
}
