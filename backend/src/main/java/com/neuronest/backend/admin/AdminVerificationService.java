package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminProfessionalDtos.PageResponse;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalDetail;
import com.neuronest.backend.admin.AdminVerificationDtos.DocumentFile;
import com.neuronest.backend.admin.AdminVerificationDtos.DocumentView;
import com.neuronest.backend.admin.AdminVerificationDtos.LogEntryView;
import com.neuronest.backend.admin.AdminVerificationDtos.NeedsInfoRequest;
import com.neuronest.backend.admin.AdminVerificationDtos.RejectRequest;
import com.neuronest.backend.admin.AdminVerificationDtos.ReviewQueueItem;
import com.neuronest.backend.admin.AdminVerificationDtos.VerifyRequest;
import com.neuronest.backend.admin.AdminVerificationExceptions.ChecklistIncompleteException;
import com.neuronest.backend.admin.AdminVerificationExceptions.DocumentNotFoundException;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProDocumentStorage;
import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.VerificationLogEntry;
import com.neuronest.backend.professional.VerificationLogRepository;
import com.neuronest.backend.professional.VerificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// The admin's review of submitted professionals: the queue, their documents and log, and the three
// decisions (verify, needs more information, reject). Each decision locks the professional's row and
// re-checks the status inside its transaction, so two quick clicks cannot both succeed.
@Service
public class AdminVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AdminVerificationService.class);

    static final String ACTOR = "ADMIN";
    static final int MAX_NOTE = 1000;
    static final int MIN_NOTE = 10;
    static final int DEFAULT_RECHECK_MONTHS = 12;
    // Registration dates are Indian calendar dates.
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    static final List<String> REGISTERED_CHECKLIST = List.of("nameMatchesRegister", "registrationNumberChecked",
            "registrationActive", "credentialTypeMatches", "idMatchesPerson");
    static final List<String> COUNSELOR_CHECKLIST = List.of("nameMatchesDegree", "degreeCertificateSeen",
            "credentialTypeMatches");

    private final ProfessionalRepository professionals;
    private final ProfessionalDocumentRepository documents;
    private final VerificationLogRepository verificationLog;
    private final ProDocumentStorage storage;
    private final Clock clock;

    @Autowired
    public AdminVerificationService(ProfessionalRepository professionals, ProfessionalDocumentRepository documents,
                                    VerificationLogRepository verificationLog, ProDocumentStorage storage) {
        this(professionals, documents, verificationLog, storage, Clock.system(ZONE));
    }

    AdminVerificationService(ProfessionalRepository professionals, ProfessionalDocumentRepository documents,
                             VerificationLogRepository verificationLog, ProDocumentStorage storage, Clock clock) {
        this.professionals = professionals;
        this.documents = documents;
        this.verificationLog = verificationLog;
        this.storage = storage;
        this.clock = clock;
    }

    // ---- Reading ----

    @Transactional(readOnly = true)
    public PageResponse<ReviewQueueItem> reviewQueue(int page, int size) {
        if (page < 0) {
            throw new ApiValidationException("page must be 0 or greater.");
        }
        if (size < 1 || size > AdminProfessionalService.MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + AdminProfessionalService.MAX_PAGE_SIZE + ".");
        }
        Specification<Professional> submitted = (root, query, cb) ->
                cb.equal(root.get("verificationStatus"), VerificationStatus.SUBMITTED);
        Sort oldestFirst = Sort.by(Sort.Order.asc("submittedAt").nullsLast(), Sort.Order.asc("id"));
        Page<Professional> result = professionals.findAll(submitted, PageRequest.of(page, size, oldestFirst));

        Map<UUID, Long> counts = new HashMap<>();
        List<UUID> ids = result.getContent().stream().map(Professional::getId).toList();
        if (!ids.isEmpty()) {
            for (Object[] row : documents.countByProfessionalIds(ids)) {
                counts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        List<ReviewQueueItem> items = result.getContent().stream()
                .map(p -> ReviewQueueItem.from(p, counts.getOrDefault(p.getId(), 0L)))
                .toList();
        return new PageResponse<>(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<DocumentView> documents(UUID professionalId) {
        requireExists(professionalId);
        return documents.findByProfessionalIdOrderByUploadedAtAsc(professionalId).stream()
                .map(DocumentView::from).toList();
    }

    // The document must belong to this professional, and its file must resolve inside APP_STORAGE_DIR.
    @Transactional(readOnly = true)
    public DocumentFile documentFile(UUID professionalId, UUID documentId) {
        requireExists(professionalId);
        ProfessionalDocument doc = documents.findByIdAndProfessionalId(documentId, professionalId)
                .orElseThrow(DocumentNotFoundException::new);
        Path path;
        try {
            path = storage.existingFile(doc.getStoragePath()).orElseThrow(DocumentNotFoundException::new);
        } catch (IllegalStateException e) {
            log.warn("Refused to serve document {}: {}", documentId, e.getMessage());
            throw new DocumentNotFoundException();
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new DocumentNotFoundException();
        }
        String filename = doc.getOriginalFilename() == null || doc.getOriginalFilename().isBlank()
                ? "document" : doc.getOriginalFilename();
        return new DocumentFile(path, doc.getContentType(), filename, size);
    }

    @Transactional(readOnly = true)
    public List<LogEntryView> log(UUID professionalId) {
        requireExists(professionalId);
        return verificationLog.findByProfessionalIdOrderByCreatedAtDesc(professionalId).stream()
                .map(LogEntryView::from).toList();
    }

    // ---- Decisions ----

    @Transactional
    public ProfessionalDetail verify(UUID id, VerifyRequest request) {
        Professional p = lockSubmitted(id, "verified");
        if (request == null) {
            throw new ApiValidationException("Request body is required.");
        }
        CredentialType type = p.getCredentialType();

        VerificationSource source = AdminProfessionalService.parseEnum(VerificationSource.class, "source", request.source());
        if (source == null) {
            throw new ApiValidationException("source is required. Allowed values for a " + describe(type) + ": "
                    + names(allowedSources(type)) + ".");
        }
        if (!allowedSources(type).contains(source)) {
            throw new ApiValidationException("source must be " + names(allowedSources(type)) + " for a "
                    + describe(type) + ".");
        }

        List<String> missing = missingChecklistItems(type, request.checklist());
        if (!missing.isEmpty()) {
            throw new ChecklistIncompleteException(missing);
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate expiry = p.getRegistrationExpiry();
        if (request.registrationExpiry() != null) {
            if (!request.registrationExpiry().isAfter(today)) {
                throw new ApiValidationException("registrationExpiry must be tomorrow or later (India time).");
            }
            expiry = request.registrationExpiry();
        } else if (expiry != null && !expiry.isAfter(today)) {
            throw new ApiValidationException("The stored registrationExpiry (" + expiry + ") is not in the future. "
                    + "Send a registrationExpiry that is tomorrow or later.");
        }

        LocalDate inTwelveMonths = today.plusMonths(DEFAULT_RECHECK_MONTHS);
        LocalDate nextRecheck = request.nextRecheckDate();
        if (nextRecheck == null) {
            nextRecheck = expiry != null && expiry.isBefore(inTwelveMonths) ? expiry : inTwelveMonths;
        } else if (!nextRecheck.isAfter(today)) {
            throw new ApiValidationException("nextRecheckDate must be tomorrow or later (India time).");
        }

        String notes = optionalNote("notes", request.notes());
        UUID evidence = request.evidenceDocumentId();
        if (evidence != null && documents.findByIdAndProfessionalId(evidence, id).isEmpty()) {
            throw new ApiValidationException("evidenceDocumentId is not one of this professional's documents.");
        }
        boolean activate = request.activate() == null || request.activate();

        VerificationStatus from = p.getVerificationStatus();
        p.setRegistrationExpiry(expiry);
        p.setVerificationStatus(VerificationStatus.VERIFIED);
        p.setVerified(true);
        p.setVerifiedBy(ACTOR);
        p.setVerifiedAt(clock.instant());
        p.setVerificationSource(source.name());
        p.setVerificationNotes(notes);
        p.setNextRecheckDate(nextRecheck);
        p.setActive(activate);
        Professional saved = professionals.saveAndFlush(p);
        verificationLog.save(new VerificationLogEntry(id, "VERIFIED", from, VerificationStatus.VERIFIED, ACTOR,
                source.name(), notes, evidence));
        return ProfessionalDetail.from(saved);
    }

    @Transactional
    public ProfessionalDetail needsInfo(UUID id, NeedsInfoRequest request) {
        Professional p = lockSubmitted(id, "sent back for more information");
        String note = requiredNote("note", request == null ? null : request.note());
        return decide(p, VerificationStatus.NEEDS_INFO, note);
    }

    @Transactional
    public ProfessionalDetail reject(UUID id, RejectRequest request) {
        Professional p = lockSubmitted(id, "rejected");
        String reason = requiredNote("reason", request == null ? null : request.reason());
        return decide(p, VerificationStatus.REJECTED, reason);
    }

    // NEEDS_INFO and REJECTED: hidden, unverified, and the note is what the professional is shown.
    private ProfessionalDetail decide(Professional p, VerificationStatus to, String note) {
        VerificationStatus from = p.getVerificationStatus();
        p.setVerificationStatus(to);
        p.setVerificationNotes(note);
        p.setVerified(false);
        p.setActive(false);
        Professional saved = professionals.saveAndFlush(p);
        verificationLog.save(new VerificationLogEntry(p.getId(), to.name(), from, to, ACTOR, null, note, null));
        return ProfessionalDetail.from(saved);
    }

    // ---- Rules ----

    static Set<VerificationSource> allowedSources(CredentialType type) {
        return switch (type) {
            case PSYCHIATRIST -> EnumSet.of(VerificationSource.NMC, VerificationSource.STATE_COUNCIL, VerificationSource.OTHER);
            case PSYCHOLOGIST -> EnumSet.of(VerificationSource.RCI, VerificationSource.OTHER);
            case COUNSELOR -> EnumSet.of(VerificationSource.DEGREE_CERTIFICATE, VerificationSource.OTHER);
        };
    }

    static List<String> requiredChecklist(CredentialType type) {
        return type == CredentialType.COUNSELOR ? COUNSELOR_CHECKLIST : REGISTERED_CHECKLIST;
    }

    // Every required item must be exactly true; a missing or false item is listed.
    static List<String> missingChecklistItems(CredentialType type, Map<String, Boolean> checklist) {
        Map<String, Boolean> given = checklist == null ? Map.of() : checklist;
        return requiredChecklist(type).stream().filter(item -> !Boolean.TRUE.equals(given.get(item))).toList();
    }

    // ---- Helpers ----

    private Professional lockSubmitted(UUID id, String action) {
        Professional p = professionals.findByIdForUpdate(id).orElseThrow(() -> new ProfessionalNotFoundException(id));
        if (p.getVerificationStatus() != VerificationStatus.SUBMITTED) {
            throw new ConflictException("Only a SUBMITTED professional can be " + action + ". This professional's "
                    + "verification status is " + p.getVerificationStatus() + ".");
        }
        return p;
    }

    private void requireExists(UUID id) {
        if (!professionals.existsById(id)) {
            throw new ProfessionalNotFoundException(id);
        }
    }

    private static String requiredNote(String field, String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new ApiValidationException(field + " is required (" + MIN_NOTE + " to " + MAX_NOTE + " characters).");
        }
        if (trimmed.length() < MIN_NOTE || trimmed.length() > MAX_NOTE) {
            throw new ApiValidationException(field + " must be between " + MIN_NOTE + " and " + MAX_NOTE
                    + " characters.");
        }
        return trimmed;
    }

    private static String optionalNote(String field, String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_NOTE) {
            throw new ApiValidationException(field + " must be at most " + MAX_NOTE + " characters.");
        }
        return trimmed;
    }

    private static String describe(CredentialType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static String names(Set<VerificationSource> sources) {
        return sources.stream().map(Enum::name).collect(Collectors.joining(", "));
    }
}
