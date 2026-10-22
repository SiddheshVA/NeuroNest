package com.neuronest.backend.pro;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProBookingDtos.ProAppointmentPage;
import com.neuronest.backend.pro.ProBookingDtos.ProAppointmentView;
import com.neuronest.backend.pro.ProHomeDtos.DayOffCreated;
import com.neuronest.backend.pro.ProHomeDtos.DayOffRequest;
import com.neuronest.backend.pro.ProHomeDtos.DayOffView;
import com.neuronest.backend.pro.ProHomeDtos.NextAppointment;
import com.neuronest.backend.pro.ProHomeDtos.ProDashboard;
import com.neuronest.backend.pro.ProHomeDtos.Warning;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalDayOff;
import com.neuronest.backend.professional.ProfessionalDayOffRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.VerificationStatus;
import com.neuronest.backend.professional.WeeklyHours;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// The professional's own home summary, confirmed appointments and days off. The professional is always
// the one linked to the verified token; a valid sign-in without a professional account gets 401 here.
// Nothing here logs reasons, names or contact details.
@Service
public class ProHomeService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_REASON = 100;
    static final int MAX_DAYS_AHEAD = 365;
    static final int MAX_RANGE_DAYS = 60;
    static final int MAX_ACTIVE_ENTRIES = 50;
    static final int EXPIRY_WARNING_DAYS = 30;
    static final String OVERLAP_MESSAGE = "These dates overlap days you already marked as off.";
    static final Set<BookingStatus> OPEN = Set.of(BookingStatus.PENDING, BookingStatus.ALTERNATE_PROPOSED);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final ProfessionalAccountRepository accounts;
    private final ProfessionalRepository professionals;
    private final ProfessionalDayOffRepository daysOff;
    private final BookingRepository bookings;
    private final Clock clock;

    @Autowired
    public ProHomeService(ProfessionalAccountRepository accounts, ProfessionalRepository professionals,
                          ProfessionalDayOffRepository daysOff, BookingRepository bookings) {
        this(accounts, professionals, daysOff, bookings, Clock.system(AvailabilityService.ZONE));
    }

    ProHomeService(ProfessionalAccountRepository accounts, ProfessionalRepository professionals,
                   ProfessionalDayOffRepository daysOff, BookingRepository bookings, Clock clock) {
        this.accounts = accounts;
        this.professionals = professionals;
        this.daysOff = daysOff;
        this.bookings = bookings;
        this.clock = clock;
    }

    // ---- Days off ----

    @Transactional(readOnly = true)
    public List<DayOffView> daysOff(ProPrincipal principal) {
        UUID professionalId = account(principal).getProfessionalId();
        return daysOff.findByProfessionalIdAndDateToGreaterThanEqualOrderByDateFromAscIdAsc(professionalId, today()).stream()
                .map(ProHomeService::view).toList();
    }

    // The professional row is locked first, so two quick requests cannot both pass the limit or the
    // overlap check.
    @Transactional
    public DayOffCreated addDayOff(ProPrincipal principal, DayOffRequest r) {
        // Who is asking comes first, so a sign-in without a professional account always gets 401.
        UUID professionalId = account(principal).getProfessionalId();
        if (r == null) throw new ApiValidationException("Request body is required.");
        LocalDate today = today();
        LocalDate from = date("dateFrom", r.dateFrom());
        LocalDate to = date("dateTo", r.dateTo());
        if (from.isBefore(today)) throw new ApiValidationException("dateFrom must be today or later (India time).");
        if (to.isBefore(from)) throw new ApiValidationException("dateTo must be on or after dateFrom.");
        if (to.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new ApiValidationException("dateTo must be within the next " + MAX_DAYS_AHEAD + " days.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new ApiValidationException("One entry can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        String reason = r.reason() == null ? null : r.reason().trim();
        if (reason != null && reason.isEmpty()) reason = null;
        if (reason != null && reason.length() > MAX_REASON) {
            throw new ApiValidationException("reason must be at most " + MAX_REASON + " characters.");
        }

        professionals.findByIdForUpdate(professionalId).orElseThrow(ProExceptions.NotAProfessionalException::new);
        if (daysOff.countByProfessionalIdAndDateToGreaterThanEqual(professionalId, today) >= MAX_ACTIVE_ENTRIES) {
            throw new ConflictException("You can have at most " + MAX_ACTIVE_ENTRIES + " upcoming days-off entries. "
                    + "Delete one before you add another.");
        }
        if (!daysOff.findOverlapping(professionalId, from, to).isEmpty()) throw new ConflictException(OVERLAP_MESSAGE);

        ProfessionalDayOff saved = daysOff.saveAndFlush(new ProfessionalDayOff(professionalId, from, to, reason));
        // Bookings on these dates are only counted, never changed.
        Instant start = from.atStartOfDay(AvailabilityService.ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(AvailabilityService.ZONE).toInstant();
        long confirmed = bookings.countScheduledBetween(professionalId, Set.of(BookingStatus.CONFIRMED), start, end);
        long pending = bookings.countOpenRequestedBetween(professionalId, OPEN, clock.instant(), start, end);
        return new DayOffCreated(saved.getId(), saved.getDateFrom(), saved.getDateTo(), saved.getReason(),
                saved.getCreatedAt(), confirmed, pending);
    }

    // Someone else's entry gets the same 404 as a missing one.
    @Transactional
    public void deleteDayOff(ProPrincipal principal, String id) {
        UUID professionalId = account(principal).getProfessionalId();
        ProfessionalDayOff entry = parseId(id).flatMap(uuid -> daysOff.findByIdAndProfessionalId(uuid, professionalId))
                .orElseThrow(ProExceptions.DayOffNotFoundException::new);
        daysOff.delete(entry);
    }

    // ---- Appointments ----

    @Transactional(readOnly = true)
    public ProAppointmentPage appointments(ProPrincipal principal, String when, int page, int size) {
        UUID professionalId = account(principal).getProfessionalId();
        if (page < 0) throw new ApiValidationException("page must be 0 or greater.");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        String value = when == null ? "upcoming" : when.trim().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        Page<Booking> result = switch (value) {
            case "upcoming" -> bookings.findByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(professionalId,
                    BookingStatus.CONFIRMED, now, PageRequest.of(page, size,
                            Sort.by(Sort.Order.asc("preferredDatetime"), Sort.Order.asc("id"))));
            case "past" -> bookings.findByProfessionalIdAndStatusAndPreferredDatetimeLessThan(professionalId,
                    BookingStatus.CONFIRMED, now, PageRequest.of(page, size,
                            Sort.by(Sort.Order.desc("preferredDatetime"), Sort.Order.asc("id"))));
            default -> throw new ApiValidationException("when must be upcoming or past.");
        };
        return new ProAppointmentPage(result.getContent().stream().map(b -> ProAppointmentView.from(b, now)).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    // ---- Home summary ----

    @Transactional(readOnly = true)
    public ProDashboard dashboard(ProPrincipal principal) {
        ProfessionalAccount account = account(principal);
        Professional p = professionals.findById(account.getProfessionalId())
                .orElseThrow(ProExceptions.NotAProfessionalException::new);
        UUID id = p.getId();
        Instant now = clock.instant();
        LocalDate today = today();

        long pending = bookings.countByProfessionalIdAndStatusAndExpiresAtAfter(id, BookingStatus.PENDING, now);
        long awaitingUser = bookings.countByProfessionalIdAndStatusAndExpiresAtAfter(id, BookingStatus.ALTERNATE_PROPOSED, now);
        long upcoming = bookings.countByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqual(id, BookingStatus.CONFIRMED, now);
        NextAppointment next = bookings
                .findFirstByProfessionalIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                        id, BookingStatus.CONFIRMED, now)
                .map(b -> new NextAppointment(b.getId(), b.getContactName(),
                        b.getPreferredDatetime().atZone(AvailabilityService.ZONE).toOffsetDateTime(), b.getMeetingMethod()))
                .orElse(null);
        LocalDate expiry = p.getRegistrationExpiry();
        Long daysLeft = expiry == null ? null : ChronoUnit.DAYS.between(today, expiry);
        boolean hasWeeklyHours = p.getSessionMinutes() != null && WeeklyHours.hasAnyRange(p.getWeeklyHours());
        List<ProfessionalDayOff> activeDaysOff =
                daysOff.findByProfessionalIdAndDateToGreaterThanEqualOrderByDateFromAscIdAsc(id, today);

        List<Warning> warnings = new ArrayList<>();
        if (daysLeft != null && daysLeft >= 0 && daysLeft <= EXPIRY_WARNING_DAYS) {
            warnings.add(new Warning("REGISTRATION_EXPIRING", "Your registration expires on " + DATE.format(expiry)
                    + ". Update it with the NeuroNest team before then, or your listing will be hidden."));
        }
        if ((daysLeft != null && daysLeft < 0) || p.getVerificationStatus() == VerificationStatus.EXPIRED) {
            warnings.add(new Warning("REGISTRATION_EXPIRED", expiry == null
                    ? "Your registration has expired. Update it with the NeuroNest team to be listed again."
                    : "Your registration expired on " + DATE.format(expiry)
                            + ". Update it with the NeuroNest team to be listed again."));
        }
        if (!hasWeeklyHours) {
            warnings.add(new Warning("NO_WEEKLY_HOURS", "You have not set your weekly hours, so people cannot book you."));
        }
        if (p.getVerificationStatus() != VerificationStatus.VERIFIED || !p.isActive()) {
            warnings.add(new Warning("NOT_LISTED", "Your listing is not visible to the public right now."));
        }
        if (p.getVerificationStatus() == VerificationStatus.SUSPENDED) {
            warnings.add(new Warning("SUSPENDED", "Your listing has been suspended. Please contact NeuroNest support."));
        }
        if (confirmedOnDaysOff(id, activeDaysOff, now)) {
            warnings.add(new Warning("DAY_OFF_CONFLICTS", "You have a confirmed appointment on a day you marked as off."));
        }

        return new ProDashboard(p.getVerificationStatus().name(), p.isActive(), account.getStatus().name(), pending,
                awaitingUser, upcoming, next, expiry, daysLeft, hasWeeklyHours, activeDaysOff.size(), List.copyOf(warnings));
    }

    // Only appointments still to come count.
    private boolean confirmedOnDaysOff(UUID professionalId, List<ProfessionalDayOff> entries, Instant now) {
        for (ProfessionalDayOff entry : entries) {
            Instant start = entry.getDateFrom().atStartOfDay(AvailabilityService.ZONE).toInstant();
            Instant end = entry.getDateTo().plusDays(1).atStartOfDay(AvailabilityService.ZONE).toInstant();
            Instant from = start.isAfter(now) ? start : now;
            if (from.isBefore(end)
                    && bookings.countScheduledBetween(professionalId, Set.of(BookingStatus.CONFIRMED), from, end) > 0) {
                return true;
            }
        }
        return false;
    }

    // ---- Lookups and parsing ----

    private ProfessionalAccount account(ProPrincipal principal) {
        return accounts.findByClerkUserId(principal.clerkUserId())
                .orElseThrow(ProExceptions.NotAProfessionalException::new);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(AvailabilityService.ZONE));
    }

    private static DayOffView view(ProfessionalDayOff d) {
        return new DayOffView(d.getId(), d.getDateFrom(), d.getDateTo(), d.getReason(), d.getCreatedAt());
    }

    private static LocalDate date(String field, String value) {
        if (value == null || value.isBlank()) throw new ApiValidationException(field + " is required, for example 2026-10-20.");
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ApiValidationException(field + " must be a date such as 2026-10-20.");
        }
    }

    private static Optional<UUID> parseId(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
