package com.neuronest.backend.pro;

import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProDtos.AccountView;
import com.neuronest.backend.pro.ProDtos.DocumentView;
import com.neuronest.backend.pro.ProDtos.ProMeResponse;
import com.neuronest.backend.pro.ProDtos.ProfileView;
import com.neuronest.backend.pro.ProDtos.RegisterRequest;
import com.neuronest.backend.pro.ProDtos.VerificationNoteView;
import com.neuronest.backend.pro.ProExceptions.MissingItem;
import com.neuronest.backend.professional.ConsultationMode;
import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.RegistrationBody;
import com.neuronest.backend.professional.VerificationLogEntry;
import com.neuronest.backend.professional.VerificationLogRepository;
import com.neuronest.backend.professional.VerificationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

// The professional's own profile: registration, viewing, editing and submitting for verification.
// The professional is always found from the Clerk user id in the verified token.
@Service
public class ProProfileService {

    static final String ACTOR = "PROFESSIONAL";
    static final int MIN_REGISTRATION_YEAR = 1950;
    static final int MAX_TEXT = 200;
    static final int MAX_LONG_TEXT = 4000;
    static final int MAX_LIST_ITEMS = 20;
    static final int MAX_LIST_ITEM = 60;
    // Registration expiry is a calendar date for Indian registrations, so "today" is taken in IST.
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    // Fields that only an admin may change once the profile has left DRAFT or NEEDS_INFO.
    // Statuses in which the admin's note explains the current state to the professional.
    static final Set<VerificationStatus> NOTE_STATUSES = EnumSet.of(VerificationStatus.NEEDS_INFO,
            VerificationStatus.REJECTED, VerificationStatus.EXPIRED, VerificationStatus.SUSPENDED);

    static final List<String> LOCKED_FIELDS = List.of("fullName", "credentialType", "qualifications",
            "registrationBody", "registrationNumber", "registrationCouncil", "registrationYear", "registrationExpiry");

    private final ProfessionalRepository professionals;
    private final ProfessionalAccountRepository accounts;
    private final ProfessionalDocumentRepository documents;
    private final VerificationLogRepository verificationLog;
    private final ClerkBackendClient clerk;
    private final ProRegistrationRateLimiter rateLimiter;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public ProProfileService(ProfessionalRepository professionals, ProfessionalAccountRepository accounts,
                             ProfessionalDocumentRepository documents, VerificationLogRepository verificationLog,
                             ClerkBackendClient clerk, ProRegistrationRateLimiter rateLimiter,
                             PlatformTransactionManager transactionManager) {
        this(professionals, accounts, documents, verificationLog, clerk, rateLimiter,
                new TransactionTemplate(transactionManager), Clock.system(ZONE));
    }

    ProProfileService(ProfessionalRepository professionals, ProfessionalAccountRepository accounts,
                      ProfessionalDocumentRepository documents, VerificationLogRepository verificationLog,
                      ClerkBackendClient clerk, ProRegistrationRateLimiter rateLimiter,
                      TransactionTemplate transactions, Clock clock) {
        this.professionals = professionals;
        this.accounts = accounts;
        this.documents = documents;
        this.verificationLog = verificationLog;
        this.clerk = clerk;
        this.rateLimiter = rateLimiter;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ---- Registration ----

    // Not one transaction: the Clerk lookup happens first, so no database connection is held during it.
    public ProMeResponse register(ProPrincipal principal, RegisterRequest request, String ip) {
        if (accounts.findByClerkUserId(principal.clerkUserId()).isPresent()) {
            throw new ConflictException("A professional account already exists for this sign-in.");
        }
        Optional<Duration> wait = rateLimiter.tryAcquire(ip);
        if (wait.isPresent()) {
            throw new ProExceptions.RateLimitedException(wait.get());
        }
        if (request == null) {
            throw new ApiValidationException("Request body is required.");
        }
        String fullName = required("fullName", request.fullName(), MAX_TEXT);
        CredentialType credentialType = requiredEnum(CredentialType.class, "credentialType", request.credentialType());
        ConsultationMode consultationMode = requiredEnum(ConsultationMode.class, "consultationMode",
                request.consultationMode());

        ClerkBackendClient.PrimaryEmail email = clerk.primaryEmail(principal.clerkUserId())
                .orElseThrow(ProExceptions.EmailNotVerifiedException::new);
        if (!email.verified()) {
            throw new ProExceptions.EmailNotVerifiedException();
        }
        String address = email.address().toLowerCase(Locale.ROOT);

        try {
            return transactions.execute(status -> {
                if (accounts.existsByEmailIgnoreCase(address)) {
                    throw new ConflictException("This email address is already linked to another professional account.");
                }
                Professional p = new Professional();
                p.setFullName(fullName);
                p.setCredentialType(credentialType);
                p.setConsultationMode(consultationMode);
                p.setContactEmail(address);
                p.setVerificationStatus(VerificationStatus.DRAFT);
                p.setVerified(false);
                p.setActive(false);
                p.setSampleData(false);
                Professional saved = professionals.saveAndFlush(p);
                ProfessionalAccount account = accounts.saveAndFlush(
                        new ProfessionalAccount(saved.getId(), address, principal.clerkUserId()));
                return view(saved, account);
            });
        } catch (DataIntegrityViolationException e) {
            // Two registrations racing for the same Clerk user or email: the unique indexes decide.
            throw new ConflictException("A professional account already exists for this sign-in or email address.");
        }
    }

    // ---- Reading ----

    @Transactional(readOnly = true)
    public ProMeResponse me(ProPrincipal principal) {
        ProfessionalAccount account = account(principal);
        return view(professional(account), account);
    }

    // ---- Editing ----

    // Partial update: fields present in the body are applied (null clears an optional field), absent
    // fields stay as they are. Fields the professional may never set are ignored.
    @Transactional
    public ProMeResponse update(ProPrincipal principal, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ApiValidationException("Request body must be a JSON object.");
        }
        ProfessionalAccount account = account(principal);
        Professional p = professional(account);
        Body b = new Body(body);

        List<Runnable> changes = new ArrayList<>();
        List<String> changedLocked = new ArrayList<>();

        // Locked fields: parsed first so a 409 can name exactly what was being changed.
        lockedField(b, "fullName", () -> required("fullName", b.text("fullName"), MAX_TEXT), p.getFullName(),
                p::setFullName, changes, changedLocked);
        CredentialType credentialType = lockedField(b, "credentialType",
                () -> requiredEnum(CredentialType.class, "credentialType", b.text("credentialType")),
                p.getCredentialType(), p::setCredentialType, changes, changedLocked);
        lockedField(b, "qualifications", () -> limit("qualifications", b.text("qualifications"), MAX_TEXT),
                p.getQualifications(), p::setQualifications, changes, changedLocked);
        RegistrationBody registrationBody = lockedField(b, "registrationBody",
                () -> parseEnum(RegistrationBody.class, "registrationBody", b.text("registrationBody")),
                p.getRegistrationBody(), p::setRegistrationBody, changes, changedLocked);
        lockedField(b, "registrationNumber", () -> limit("registrationNumber", b.text("registrationNumber"), MAX_TEXT),
                p.getRegistrationNumber(), p::setRegistrationNumber, changes, changedLocked);
        String registrationCouncil = lockedField(b, "registrationCouncil",
                () -> limit("registrationCouncil", b.text("registrationCouncil"), MAX_TEXT),
                p.getRegistrationCouncil(), p::setRegistrationCouncil, changes, changedLocked);
        Integer registrationYear = lockedField(b, "registrationYear", () -> b.integer("registrationYear"),
                p.getRegistrationYear(), p::setRegistrationYear, changes, changedLocked);
        lockedField(b, "registrationExpiry", () -> b.date("registrationExpiry"), p.getRegistrationExpiry(),
                p::setRegistrationExpiry, changes, changedLocked);

        if (!changedLocked.isEmpty() && !SubmissionChecklist.isEditable(p.getVerificationStatus())) {
            throw new ConflictException("These details can only be changed by the admin once your profile has been "
                    + "submitted: " + String.join(", ", changedLocked) + ".");
        }

        // Always editable (outside DISABLED, which the filter already refused).
        if (b.has("bio")) { String v = limit("bio", b.text("bio"), MAX_LONG_TEXT); changes.add(() -> p.setBio(v)); }
        if (b.has("specialties")) { List<String> v = b.list("specialties"); changes.add(() -> p.setSpecialties(v)); }
        if (b.has("languages")) { List<String> v = b.list("languages"); changes.add(() -> p.setLanguages(v)); }
        if (b.has("city")) { String v = limit("city", b.text("city"), MAX_TEXT); changes.add(() -> p.setCity(v)); }
        if (b.has("state")) { String v = limit("state", b.text("state"), MAX_TEXT); changes.add(() -> p.setState(v)); }
        if (b.has("consultationMode")) {
            ConsultationMode v = requiredEnum(ConsultationMode.class, "consultationMode", b.text("consultationMode"));
            changes.add(() -> p.setConsultationMode(v));
        }
        if (b.has("consultationFee")) {
            Integer v = consultationFee(b.integer("consultationFee"));
            changes.add(() -> p.setConsultationFee(v));
        }
        if (b.has("yearsExperience")) {
            Integer v = yearsExperience(b.integer("yearsExperience"));
            changes.add(() -> p.setYearsExperience(v));
        }
        if (b.has("availabilityNote")) {
            String v = availabilityNote(b.text("availabilityNote"));
            changes.add(() -> p.setAvailabilityNote(v));
        }
        if (b.has("photoUrl")) { String v = photoUrl(b.text("photoUrl")); changes.add(() -> p.setPhotoUrl(v)); }
        if (b.has("contactPhone")) { String v = phone(b.text("contactPhone")); changes.add(() -> p.setContactPhone(v)); }

        // Rules across fields, checked on the resulting values before anything is applied. The
        // year and registration-body rules only apply when this request changes those fields, so an
        // older value set by the admin never blocks an unrelated edit.
        int currentYear = Year.now(clock).getValue();
        if (changedLocked.contains("registrationYear") && registrationYear != null
                && (registrationYear < MIN_REGISTRATION_YEAR || registrationYear > currentYear)) {
            throw new ApiValidationException("registrationYear must be between " + MIN_REGISTRATION_YEAR + " and "
                    + currentYear + ".");
        }
        boolean registrationChanged = changedLocked.contains("credentialType") || changedLocked.contains("registrationBody")
                || changedLocked.contains("registrationCouncil");
        if (registrationChanged && registrationBody != null
                && !SubmissionChecklist.allowedRegistrationBodies(credentialType).contains(registrationBody)) {
            throw new ApiValidationException("registrationBody must be " + SubmissionChecklist.describeAllowed(credentialType)
                    + " for a " + credentialType.name().toLowerCase(Locale.ROOT) + ".");
        }
        // The council names the body when it is OTHER; counselors are optional, but a chosen body needs its
        // council. For psychiatrists and psychologists the council is checked when submitting.
        if (registrationChanged && registrationCouncil == null) {
            if (registrationBody == RegistrationBody.OTHER) {
                throw new ApiValidationException("registrationCouncil must contain the name of the registration body "
                        + "when registrationBody is OTHER.");
            }
            if (registrationBody != null && credentialType == CredentialType.COUNSELOR) {
                throw new ApiValidationException("registrationCouncil is required when a registration body is chosen.");
            }
        }

        changes.forEach(Runnable::run);
        return view(professionals.saveAndFlush(p), account);
    }

    // ---- Submitting ----

    @Transactional
    public ProMeResponse submit(ProPrincipal principal) {
        ProfessionalAccount account = account(principal);
        Professional p = professional(account);
        VerificationStatus from = p.getVerificationStatus();
        if (!SubmissionChecklist.isEditable(from)) {
            throw new ConflictException("Your profile can only be submitted while it is a draft or needs more "
                    + "information. Its current status is " + from + ".");
        }
        List<MissingItem> missing = SubmissionChecklist.missing(p, uploadedTypes(p.getId()), LocalDate.now(clock));
        if (!missing.isEmpty()) {
            throw new ProExceptions.SubmissionIncompleteException(missing);
        }
        p.setVerificationStatus(VerificationStatus.SUBMITTED);
        p.setVerified(false);
        p.setSubmittedAt(clock.instant());
        Professional saved = professionals.saveAndFlush(p);
        verificationLog.save(new VerificationLogEntry(p.getId(), "SUBMITTED", from, VerificationStatus.SUBMITTED,
                ACTOR, null));
        return view(saved, account);
    }

    // ---- Shared ----

    ProfessionalAccount account(ProPrincipal principal) {
        return accounts.findByClerkUserId(principal.clerkUserId())
                .orElseThrow(ProExceptions.NotRegisteredException::new);
    }

    Professional professional(ProfessionalAccount account) {
        // The account's professional_id is a foreign key, so this row exists.
        return professionals.findById(account.getProfessionalId()).orElseThrow(ProExceptions.NotRegisteredException::new);
    }

    private Set<DocumentType> uploadedTypes(java.util.UUID professionalId) {
        Set<DocumentType> types = EnumSet.noneOf(DocumentType.class);
        documents.findByProfessionalIdOrderByUploadedAtAsc(professionalId).forEach(d -> types.add(d.getDocType()));
        return types;
    }

    ProMeResponse view(Professional p, ProfessionalAccount account) {
        List<ProfessionalDocument> docs = documents.findByProfessionalIdOrderByUploadedAtAsc(p.getId());
        Set<DocumentType> uploaded = EnumSet.noneOf(DocumentType.class);
        docs.forEach(d -> uploaded.add(d.getDocType()));
        boolean editable = SubmissionChecklist.isEditable(p.getVerificationStatus());
        List<MissingItem> missing = SubmissionChecklist.missing(p, uploaded, LocalDate.now(clock));
        return new ProMeResponse(
                AccountView.from(account),
                ProfileView.from(p),
                latestNote(p),
                docs.stream().map(DocumentView::from).toList(),
                SubmissionChecklist.requiredDocuments(p.getCredentialType()).stream().map(Enum::name).toList(),
                SubmissionChecklist.missingDocuments(p.getCredentialType(), uploaded).stream().map(Enum::name).toList(),
                missing,
                editable,
                editable,
                editable && missing.isEmpty());
    }

    // The note is professionals.verification_notes, the field the admin's needs-info and reject decisions
    // write. It is only sent while it explains the current status, so notes an admin wrote when verifying
    // stay internal. The matching log entry, when there is one, adds what the decision was and when.
    private VerificationNoteView latestNote(Professional p) {
        String note = trimToNull(p.getVerificationNotes());
        if (note == null || !NOTE_STATUSES.contains(p.getVerificationStatus())) {
            return null;
        }
        Optional<VerificationLogEntry> logged = verificationLog
                .findFirstByProfessionalIdAndNoteIsNotNullAndActorNotOrderByCreatedAtDesc(p.getId(), ACTOR)
                .filter(entry -> note.equals(trimToNull(entry.getNote())));
        return logged.map(entry -> new VerificationNoteView(note, entry.getAction(), entry.getCreatedAt()))
                .orElseGet(() -> new VerificationNoteView(note, null, null));
    }

    // Parses a locked field if present and records it when it differs; returns the resulting value.
    private static <T> T lockedField(Body b, String field, java.util.function.Supplier<T> parse, T current,
                                     Consumer<T> setter, List<Runnable> changes, List<String> changedLocked) {
        if (!b.has(field)) {
            return current;
        }
        T value = parse.get();
        if (!Objects.equals(value, current)) {
            changedLocked.add(field);
            changes.add(() -> setter.accept(value));
        }
        return value;
    }

    // ---- Input parsing ----

    // Reads typed values from the JSON body with readable errors for wrong types.
    static final class Body {
        private final JsonNode node;

        Body(JsonNode node) {
            this.node = node;
        }

        boolean has(String field) {
            return node.has(field);
        }

        String text(String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) return null;
            if (!value.isString()) throw new ApiValidationException(field + " must be text.");
            return trimToNull(value.asString());
        }

        Integer integer(String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) return null;
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw new ApiValidationException(field + " must be a whole number.");
            }
            return value.asInt();
        }

        LocalDate date(String field) {
            String text = text(field);
            if (text == null) return null;
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                throw new ApiValidationException(field + " must be a date in YYYY-MM-DD format.");
            }
        }

        // Trims entries and drops blanks and case-insensitive duplicates, keeping order.
        List<String> list(String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) return new ArrayList<>();
            if (!value.isArray()) throw new ApiValidationException(field + " must be a list of text values.");
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<String> cleaned = new ArrayList<>();
            for (JsonNode item : value) {
                if (item.isNull()) continue;
                if (!item.isString()) throw new ApiValidationException(field + " must be a list of text values.");
                String trimmed = trimToNull(item.asString());
                if (trimmed == null) continue;
                if (trimmed.length() > MAX_LIST_ITEM) {
                    throw new ApiValidationException("Each entry in " + field + " must be at most " + MAX_LIST_ITEM
                            + " characters.");
                }
                if (seen.add(trimmed.toLowerCase(Locale.ROOT))) cleaned.add(trimmed);
            }
            if (cleaned.size() > MAX_LIST_ITEMS) {
                throw new ApiValidationException(field + " can have at most " + MAX_LIST_ITEMS + " entries.");
            }
            return cleaned;
        }
    }

    private static String required(String field, String value, int max) {
        String trimmed = trimToNull(value);
        if (trimmed == null) throw new ApiValidationException(field + " is required.");
        return limit(field, trimmed, max);
    }

    private static String limit(String field, String value, int max) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > max) {
            throw new ApiValidationException(field + " must be at most " + max + " characters.");
        }
        return trimmed;
    }

    private static Integer consultationFee(Integer value) {
        if (value != null && (value < 0 || value > Professional.MAX_CONSULTATION_FEE)) {
            throw new ApiValidationException("consultationFee must be between 0 and " + Professional.MAX_CONSULTATION_FEE + ".");
        }
        return value;
    }

    private static Integer yearsExperience(Integer value) {
        if (value != null && (value < 0 || value > SubmissionChecklist.MAX_YEARS_EXPERIENCE)) {
            throw new ApiValidationException("yearsExperience must be between 0 and "
                    + SubmissionChecklist.MAX_YEARS_EXPERIENCE + ".");
        }
        return value;
    }

    private static String availabilityNote(String value) {
        String trimmed = limit("availabilityNote", value, MAX_TEXT);
        if (trimmed != null && trimmed.length() < SubmissionChecklist.MIN_AVAILABILITY_NOTE) {
            throw new ApiValidationException("availabilityNote must be between " + SubmissionChecklist.MIN_AVAILABILITY_NOTE
                    + " and " + MAX_TEXT + " characters.");
        }
        return trimmed;
    }

    private static String photoUrl(String value) {
        String trimmed = limit("photoUrl", value, 1000);
        if (trimmed != null && !trimmed.toLowerCase(Locale.ROOT).matches("^https://\\S+$")) {
            throw new ApiValidationException("photoUrl must start with https://.");
        }
        return trimmed;
    }

    // Stored as +91XXXXXXXXXX.
    private static String phone(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return null;
        String normalized = SubmissionChecklist.normalizeIndianMobile(trimmed);
        if (normalized == null) {
            throw new ApiValidationException("contactPhone must be a 10-digit Indian mobile number starting with 6, 7, 8 "
                    + "or 9. You may write it with +91 or a leading 0.");
        }
        return normalized;
    }

    private static <E extends Enum<E>> E requiredEnum(Class<E> type, String field, String value) {
        E parsed = parseEnum(type, field, value);
        if (parsed == null) {
            throw new ApiValidationException(field + " is required. Allowed values: " + allowed(type) + ".");
        }
        return parsed;
    }

    // Case-insensitive; blank means "not given". Never lets Enum.valueOf's IllegalArgumentException
    // escape, since ApiExceptionHandler would turn that into a 404.
    static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return null;
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(trimmed)) return constant;
        }
        throw new ApiValidationException(field + " must be one of " + allowed(type) + " (got '" + trimmed + "').");
    }

    private static String allowed(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
    }

    static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
