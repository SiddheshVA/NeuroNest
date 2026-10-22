package com.neuronest.backend.professional;

import com.neuronest.backend.booking.SlotService;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.professional.PublicProfessionalDtos.AvailabilityDay;
import com.neuronest.backend.professional.PublicProfessionalDtos.PublicAvailability;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    static final int DEFAULT_AVAILABILITY_DAYS = 14;
    static final int MAX_AVAILABILITY_RANGE_DAYS = 31;
    private static final DateTimeFormatter SLOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final PublicProfessionalRepository repository;
    private final SlotService slots;
    private final Clock clock;

    @Autowired
    public PublicProfessionalService(PublicProfessionalRepository repository, SlotService slots) {
        this(repository, slots, Clock.system(DIRECTORY_ZONE));
    }

    public PublicProfessionalService(PublicProfessionalRepository repository, SlotService slots, Clock clock) {
        this.repository = repository;
        this.slots = slots;
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
        return findVisible(id).map(PublicProfessional::from).orElseThrow(PublicProfessionalNotFoundException::new);
    }

    // Free slots for a visible listing; the same 404 as get() for a missing or hidden one. The range
    // defaults to today and the next 14 days, may span at most 31 days, and must lie within the 60-day
    // booking window.
    @Transactional(readOnly = true)
    public PublicAvailability availability(String id, String fromParam, String toParam) {
        LocalDate today = today();
        LocalDate from = parseDate("from", fromParam, today);
        LocalDate to = parseDate("to", toParam, from.plusDays(DEFAULT_AVAILABILITY_DAYS));
        if (to.isBefore(from)) {
            throw new ApiValidationException("to must be on or after from.");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_AVAILABILITY_RANGE_DAYS) {
            throw new ApiValidationException("The date range can be at most " + MAX_AVAILABILITY_RANGE_DAYS + " days long.");
        }
        LocalDate lastDate = today.plusDays(AvailabilityService.MAX_DAYS_AHEAD);
        if (from.isBefore(today) || to.isAfter(lastDate)) {
            throw new ApiValidationException("The dates must be between " + today + " and " + lastDate
                    + " (today and the next " + AvailabilityService.MAX_DAYS_AHEAD + " days, India time).");
        }
        Professional p = findVisible(id).orElseThrow(PublicProfessionalNotFoundException::new);

        // Slots that overlap a confirmed booking are left out (SlotService, shared with the booking check).
        var free = slots.freeSlots(p, from, to, ZonedDateTime.now(clock));
        List<AvailabilityDay> days = free.entrySet().stream()
                .map(e -> new AvailabilityDay(e.getKey(), e.getValue().stream().map(SLOT_FORMAT::format).toList()))
                .toList();
        return new PublicAvailability(p.getSessionMinutes(), AvailabilityService.ZONE.getId(), days);
    }

    /**
     * The listing if it is publicly visible, by the same rule as GET /api/professionals/{id}; empty for a
     * missing, hidden or malformed id. Also used by bookings.
     */
    @Transactional(readOnly = true)
    public Optional<Professional> findVisible(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return repository.findVisibleById(uuid, today());
    }

    private static LocalDate parseDate(String field, String value, LocalDate fallback) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return fallback;
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new ApiValidationException(field + " must be a date in YYYY-MM-DD format.");
        }
    }

    @Transactional(readOnly = true)
    public PublicProfessionalMeta meta() {
        LocalDate today = today();
        return new PublicProfessionalMeta(repository.findVisibleCities(today), repository.findVisibleLanguages(today));
    }

    /**
     * Up to {@code limit} other listings someone could request instead of {@code p}: bookable under the
     * public visibility rule, the same credential type, never {@code p} itself; the same city first, then
     * the most recently verified.
     */
    @Transactional(readOnly = true)
    public List<Professional> similarBookable(Professional p, int limit) {
        if (p == null || p.getCredentialType() == null || limit <= 0) return List.of();
        return repository.findSimilarCandidates(today(), p.getCredentialType().name(), p.getCity(), p.getId()).stream()
                .filter(PublicProfessionalDtos.PublicProfessional::bookable)
                .limit(limit)
                .toList();
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
