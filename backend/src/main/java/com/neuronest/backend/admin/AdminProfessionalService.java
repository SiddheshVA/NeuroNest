package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminProfessionalDtos.PageResponse;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalDetail;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalRequest;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalSummary;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalUpdateResult;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.professional.ConsultationMode;
import com.neuronest.backend.professional.ContactDetailsGuard;
import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.RegistrationBody;
import com.neuronest.backend.professional.VerificationLogEntry;
import com.neuronest.backend.professional.VerificationLogRepository;
import com.neuronest.backend.professional.VerificationStatus;
import com.neuronest.backend.professional.WeeklyHours;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AdminProfessionalService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MIN_REGISTRATION_YEAR = 1950;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final ProfessionalRepository repository;
    private final VerificationLogRepository verificationLog;
    private final ProfessionalAccountRepository accounts;
    private final Clock clock;

    @Autowired
    public AdminProfessionalService(ProfessionalRepository repository, VerificationLogRepository verificationLog,
                                    ProfessionalAccountRepository accounts) {
        this(repository, verificationLog, accounts, Clock.systemUTC());
    }

    AdminProfessionalService(ProfessionalRepository repository, VerificationLogRepository verificationLog,
                             ProfessionalAccountRepository accounts, Clock clock) {
        this.repository = repository;
        this.verificationLog = verificationLog;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProfessionalSummary> list(String verificationStatus, String credentialType, Boolean active,
                                                  String q, int page, int size) {
        if (page < 0) {
            throw new ApiValidationException("page must be 0 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        VerificationStatus status = parseEnum(VerificationStatus.class, "verificationStatus", verificationStatus);
        CredentialType type = parseEnum(CredentialType.class, "credentialType", credentialType);
        String search = trimToNull(q);

        Specification<Professional> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("verificationStatus"), status));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("credentialType"), type));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (search != null) {
                String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("contactEmail")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        Page<Professional> result = repository.findAll(spec, PageRequest.of(page, size, sort));
        return new PageResponse<>(result.getContent().stream().map(ProfessionalSummary::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ProfessionalDetail get(UUID id) {
        return ProfessionalDetail.from(find(id));
    }

    // Admin-created listings always start hidden and unverified, whatever the request contains. A new
    // listing has no professional account yet, so it may be sample data.
    @Transactional
    public ProfessionalDetail create(ProfessionalRequest request) {
        Professional professional = new Professional();
        applyEditableFields(professional, request);
        professional.setSampleData(Boolean.TRUE.equals(request.isSampleData()));
        professional.setVerificationStatus(VerificationStatus.DRAFT);
        professional.setVerified(false);
        professional.setActive(false);
        return ProfessionalDetail.from(repository.save(professional));
    }

    // Replaces every editable field (send the full record). Verification fields and "active" are
    // never touched here, except that changing an identity field on a VERIFIED listing sends it
    // back for review. isSampleData is kept when omitted.
    @Transactional
    public ProfessionalUpdateResult update(UUID id, ProfessionalRequest request, String actor) {
        Professional professional = find(id);

        String oldFullName = professional.getFullName();
        CredentialType oldCredentialType = professional.getCredentialType();
        RegistrationBody oldRegistrationBody = professional.getRegistrationBody();
        String oldRegistrationNumber = professional.getRegistrationNumber();

        applyEditableFields(professional, request);
        if (Boolean.TRUE.equals(request.isSampleData()) && accounts.existsByProfessionalId(id)) {
            throw new ApiValidationException("isSampleData cannot be true for a professional who has a professional "
                    + "account. This listing belongs to a real person.");
        }
        if (request.isSampleData() != null) {
            professional.setSampleData(request.isSampleData());
        }

        List<String> changedIdentityFields = new ArrayList<>();
        if (!Objects.equals(oldFullName, professional.getFullName())) changedIdentityFields.add("fullName");
        if (oldCredentialType != professional.getCredentialType()) changedIdentityFields.add("credentialType");
        if (oldRegistrationBody != professional.getRegistrationBody()) changedIdentityFields.add("registrationBody");
        if (!Objects.equals(oldRegistrationNumber, professional.getRegistrationNumber())) {
            changedIdentityFields.add("registrationNumber");
        }

        boolean reReviewRequired = professional.getVerificationStatus() == VerificationStatus.VERIFIED
                && !changedIdentityFields.isEmpty();
        if (reReviewRequired) {
            professional.setVerificationStatus(VerificationStatus.SUBMITTED);
            professional.setVerified(false);
            professional.setSubmittedAt(clock.instant());
            verificationLog.save(new VerificationLogEntry(professional.getId(), "SUBMITTED",
                    VerificationStatus.VERIFIED, VerificationStatus.SUBMITTED, actor,
                    "Re-review required: admin changed " + String.join(", ", changedIdentityFields) + "."));
        }

        Professional saved = repository.saveAndFlush(professional);
        return new ProfessionalUpdateResult(ProfessionalDetail.from(saved), reReviewRequired);
    }

    @Transactional
    public ProfessionalDetail setActive(UUID id, Boolean active) {
        if (active == null) {
            throw new ApiValidationException("active is required (true or false).");
        }
        Professional professional = find(id);
        boolean sample = professional.isSampleData() && !accounts.existsByProfessionalId(id);
        if (active && professional.getVerificationStatus() != VerificationStatus.VERIFIED && !sample) {
            throw new ConflictException("Only a VERIFIED listing (or sample data) can be enabled. This listing's "
                    + "verification status is " + professional.getVerificationStatus() + ".");
        }
        professional.setActive(active);
        return ProfessionalDetail.from(repository.saveAndFlush(professional));
    }

    private Professional find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ProfessionalNotFoundException(id));
    }

    private void applyEditableFields(Professional p, ProfessionalRequest r) {
        if (r == null) {
            throw new ApiValidationException("Request body is required.");
        }
        String fullName = required("fullName", r.fullName());
        CredentialType credentialType = requiredEnum(CredentialType.class, "credentialType", r.credentialType());
        ConsultationMode consultationMode = requiredEnum(ConsultationMode.class, "consultationMode", r.consultationMode());
        String contactEmail = required("contactEmail", r.contactEmail());
        if (!EMAIL.matcher(contactEmail).matches()) {
            throw new ApiValidationException("contactEmail is not a valid email address.");
        }
        RegistrationBody registrationBody = parseEnum(RegistrationBody.class, "registrationBody", r.registrationBody());

        int currentYear = Year.now(clock).getValue();
        if (r.registrationYear() != null
                && (r.registrationYear() < MIN_REGISTRATION_YEAR || r.registrationYear() > currentYear)) {
            throw new ApiValidationException("registrationYear must be between " + MIN_REGISTRATION_YEAR + " and "
                    + currentYear + ".");
        }
        consultationFee(r.consultationFee());
        nonNegative("yearsExperience", r.yearsExperience());
        String qualifications = noContactDetails("Qualifications", trimToNull(r.qualifications()));
        String bio = noContactDetails("Bio", trimToNull(r.bio()));
        String availabilityNote = noContactDetails("Availability note", trimToNull(r.availabilityNote()));
        List<String> specialties = cleanList(r.specialties());
        ContactDetailsGuard.checkAll("Specializations", specialties).ifPresent(error -> {
            throw new ApiValidationException(error);
        });
        String photoUrl = trimToNull(r.photoUrl());
        if (photoUrl != null && !photoUrl.toLowerCase(Locale.ROOT).matches("^https?://\\S+$")) {
            throw new ApiValidationException("photoUrl must be an http(s) URL.");
        }
        // Weekly hours use the same rules as the professional's own form. Missing or null keeps what is
        // stored; when either the hours or the session length changes, the resulting pair is checked.
        boolean hoursGiven = r.weeklyHours() != null && !r.weeklyHours().isNull();
        Integer sessionMinutes = r.sessionMinutes() != null
                ? WeeklyHours.sessionMinutes(r.sessionMinutes()) : p.getSessionMinutes();
        Map<String, List<Map<String, String>>> weeklyHours = hoursGiven || r.sessionMinutes() != null
                ? WeeklyHours.validate(hoursGiven ? WeeklyHours.fromJson(r.weeklyHours()) : p.getWeeklyHours(),
                        sessionMinutes)
                : p.getWeeklyHours();
        int bufferMinutes = r.bufferMinutes() != null ? WeeklyHours.bufferMinutes(r.bufferMinutes()) : p.getBufferMinutes();

        p.setFullName(fullName);
        p.setCredentialType(credentialType);
        p.setConsultationMode(consultationMode);
        p.setContactEmail(contactEmail);
        p.setRegistrationBody(registrationBody);
        p.setQualifications(qualifications);
        p.setRegistrationNumber(trimToNull(r.registrationNumber()));
        p.setRegistrationCouncil(trimToNull(r.registrationCouncil()));
        p.setRegistrationYear(r.registrationYear());
        p.setRegistrationExpiry(r.registrationExpiry());
        p.setBio(bio);
        p.setSpecialties(specialties);
        p.setLanguages(cleanList(r.languages()));
        p.setCity(trimToNull(r.city()));
        p.setState(trimToNull(r.state()));
        p.setConsultationFee(r.consultationFee());
        p.setYearsExperience(r.yearsExperience());
        p.setContactPhone(trimToNull(r.contactPhone()));
        p.setAvailabilityNote(availabilityNote);
        p.setPhotoUrl(photoUrl);
        p.setWeeklyHours(weeklyHours);
        p.setSessionMinutes(sessionMinutes);
        p.setBufferMinutes(bufferMinutes);
    }

    private static String required(String field, String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiValidationException(field + " is required.");
        }
        return trimmed;
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
        if (trimmed == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(trimmed)) {
                return constant;
            }
        }
        throw new ApiValidationException(field + " must be one of " + allowed(type) + " (got '" + trimmed + "').");
    }

    private static String allowed(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
    }

    // The professional's public text must not contain contact details (see ContactDetailsGuard).
    private static String noContactDetails(String fieldName, String value) {
        ContactDetailsGuard.check(fieldName, value).ifPresent(error -> {
            throw new ApiValidationException(error);
        });
        return value;
    }

    private static void consultationFee(Integer value) {
        if (value != null && (value < 0 || value > Professional.MAX_CONSULTATION_FEE)) {
            throw new ApiValidationException("consultationFee must be between 0 and " + Professional.MAX_CONSULTATION_FEE + ".");
        }
    }

    private static void nonNegative(String field, Integer value) {
        if (value != null && value < 0) {
            throw new ApiValidationException(field + " must not be negative.");
        }
    }

    // Trims entries and drops blanks and duplicates, keeping order. Missing means an empty list.
    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                cleaned.add(trimmed);
            }
        }
        return new ArrayList<>(cleaned);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
