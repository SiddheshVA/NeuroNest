package com.neuronest.backend.professional;

import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.professional.PublicProfessionalDtos.PublicPage;
import com.neuronest.backend.professional.PublicProfessionalDtos.PublicProfessional;
import com.neuronest.backend.professional.PublicProfessionalDtos.PublicProfessionalMeta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

// Public, read-only directory. Visibility is enforced by PublicProfessionalRepository's queries;
// this class only validates and normalises the filters.
@Service
public class PublicProfessionalService {

    static final int DEFAULT_PAGE_SIZE = 12;
    static final int MAX_PAGE_SIZE = 50;
    // Registration expiry is a calendar date for Indian registrations, so "today" is taken in IST
    // rather than the server's (or the database's) timezone.
    static final ZoneId DIRECTORY_ZONE = ZoneId.of("Asia/Kolkata");

    private final PublicProfessionalRepository repository;
    private final Clock clock;

    @Autowired
    public PublicProfessionalService(PublicProfessionalRepository repository) {
        this(repository, Clock.system(DIRECTORY_ZONE));
    }

    PublicProfessionalService(PublicProfessionalRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicPage<PublicProfessional> search(String credentialType, String city, String language, String mode,
                                                 String q, int page, int size) {
        if (page < 0) {
            throw new ApiValidationException("page must be 0 or greater.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        CredentialType type = parseEnum(CredentialType.class, "credentialType", credentialType,
                "PSYCHOLOGIST, PSYCHIATRIST, COUNSELOR");
        String modeFilter = parseMode(mode);
        String search = trimToNull(q);
        String pattern = search == null ? null : "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";

        Page<Professional> result = repository.search(today(), type == null ? null : type.name(),
                trimToNull(city), modeFilter, trimToNull(language), pattern, PageRequest.of(page, size));
        return new PublicPage<>(result.getContent().stream().map(PublicProfessional::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    // Missing and hidden listings give the same exception (and so the same 404).
    @Transactional(readOnly = true)
    public PublicProfessional get(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new PublicProfessionalNotFoundException();
        }
        return repository.findVisibleById(uuid, today())
                .map(PublicProfessional::from)
                .orElseThrow(PublicProfessionalNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public PublicProfessionalMeta meta() {
        LocalDate today = today();
        return new PublicProfessionalMeta(repository.findVisibleCities(today), repository.findVisibleLanguages(today));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    // BOTH is a listing's mode, not a search choice: searching ONLINE or IN_PERSON already includes it.
    private static String parseMode(String value) {
        ConsultationMode mode = parseEnum(ConsultationMode.class, "mode", value, "ONLINE, IN_PERSON");
        if (mode == ConsultationMode.BOTH) {
            throw new ApiValidationException("mode must be one of ONLINE, IN_PERSON (got 'BOTH'). "
                    + "Listings offering both modes are included in either search.");
        }
        return mode == null ? null : mode.name();
    }

    // Case-insensitive; blank means "not given". Never lets Enum.valueOf's IllegalArgumentException
    // escape, since ApiExceptionHandler would turn that into a 404.
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String value, String allowed) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(trimmed)) {
                return constant;
            }
        }
        throw new ApiValidationException(field + " must be one of " + allowed + " (got '" + trimmed + "').");
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
