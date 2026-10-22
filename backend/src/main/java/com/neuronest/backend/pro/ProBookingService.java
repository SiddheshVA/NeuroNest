package com.neuronest.backend.pro;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.booking.SlotService;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProBookingDtos.ConfirmRequest;
import com.neuronest.backend.pro.ProBookingDtos.DeclineRequest;
import com.neuronest.backend.pro.ProBookingDtos.ProBookingPage;
import com.neuronest.backend.pro.ProBookingDtos.ProBookingSummary;
import com.neuronest.backend.pro.ProBookingDtos.ProBookingView;
import com.neuronest.backend.pro.ProBookingDtos.ProposeRequest;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.ContactDetailsGuard;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// A professional's own booking requests: listing, reading, and replying (confirm, decline, propose another
// time). The professional is always the one linked to the verified token, never one from the request;
// anyone else's booking gets the same 404 as a missing one. Nothing here logs request bodies, notes,
// contact details, meeting links or assessment data.
@Service
public class ProBookingService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_NOTE = 500;
    static final int MAX_LINK = 300;
    static final int MIN_ADDRESS = 5;
    static final int MAX_ADDRESS = 200;
    /** The user's time to answer a proposed time. */
    static final Duration USER_REPLY_WINDOW = Duration.ofHours(72);
    static final String OVERLAP_MESSAGE = "You already have an appointment at that time.";
    static final String EXPIRED_MESSAGE = "This request has expired.";
    static final String NOT_WAITING_MESSAGE = "This request is no longer waiting for a reply.";

    private record Meeting(String method, String link, String address) {}

    private final BookingRepository bookings;
    private final SlotService slots;
    private final ProfessionalRepository professionals;
    private final ProfessionalAccountRepository accounts;
    private final Clock clock;

    @Autowired
    public ProBookingService(BookingRepository bookings, SlotService slots, ProfessionalRepository professionals,
                             ProfessionalAccountRepository accounts) {
        this(bookings, slots, professionals, accounts, Clock.system(AvailabilityService.ZONE));
    }

    ProBookingService(BookingRepository bookings, SlotService slots, ProfessionalRepository professionals,
                      ProfessionalAccountRepository accounts, Clock clock) {
        this.bookings = bookings;
        this.slots = slots;
        this.professionals = professionals;
        this.accounts = accounts;
        this.clock = clock;
    }

    // ---- Reading ----

    @Transactional(readOnly = true)
    public ProBookingPage list(ProPrincipal principal, String status, int page, int size) {
        if (page < 0) throw new ApiValidationException("page must be 0 or greater.");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        BookingStatus filter = status(status);
        UUID professionalId = professionalId(principal);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        Page<Booking> result = filter == null
                ? bookings.findByProfessionalId(professionalId, pageRequest)
                : bookings.findByProfessionalIdAndStatus(professionalId, filter, pageRequest);
        Instant now = clock.instant();
        return new ProBookingPage(result.getContent().stream().map(b -> ProBookingView.from(b, now)).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ProBookingSummary summary(ProPrincipal principal) {
        UUID professionalId = professionalId(principal);
        Instant now = clock.instant();
        // Both counts leave out requests whose expires_at has passed.
        return new ProBookingSummary(
                bookings.countByProfessionalIdAndStatusAndExpiresAtAfter(professionalId, BookingStatus.PENDING, now),
                bookings.countByProfessionalIdAndStatusAndExpiresAtAfter(professionalId,
                        BookingStatus.ALTERNATE_PROPOSED, now));
    }

    @Transactional(readOnly = true)
    public ProBookingView get(ProPrincipal principal, String id) {
        UUID professionalId = professionalId(principal);
        Booking b = parseId(id).flatMap(uuid -> bookings.findByIdAndProfessionalId(uuid, professionalId))
                .orElseThrow(ProExceptions.BookingNotFoundException::new);
        return ProBookingView.from(b, clock.instant());
    }

    // ---- Replying ----
    // Each reply locks the professional row first and then the booking row, and re-checks the status
    // inside the transaction, so two quick replies cannot both succeed or double-book. A user accepting a
    // proposal takes the same professional lock.

    @Transactional
    public ProBookingView confirm(ProPrincipal principal, String id, ConfirmRequest r) {
        if (r == null) throw new ApiValidationException("Request body is required.");
        boolean alternate = chosenSlot(r.chosenSlot());
        Meeting meeting = meeting(r.meetingMethod(), r.meetingLink(), r.meetingAddress());
        String note = note(r.note());

        Professional p = lockedProfessional(principal);
        Booking b = lockedBooking(p, id);
        Instant now = clock.instant();
        requireWaiting(b, now);
        Instant chosen;
        if (alternate) {
            if (b.getAlternateDatetime() == null) {
                throw new ApiValidationException("This request has no second-choice time, so chosenSlot must be PREFERRED.");
            }
            chosen = b.getAlternateDatetime();
        } else {
            chosen = b.getPreferredDatetime();
        }
        if (chosen == null || !chosen.isAfter(now)) {
            throw new ApiValidationException("The chosen time has already passed. Propose another time instead.");
        }
        requireFitsRequest(b, meeting);
        if (slots.overlapsConfirmed(p, chosen)) throw new ConflictException(OVERLAP_MESSAGE);

        b.setStatus(BookingStatus.CONFIRMED);
        // preferred_datetime is always the scheduled time of a confirmed booking.
        b.setPreferredDatetime(chosen);
        apply(b, meeting, note, now);
        return ProBookingView.from(bookings.saveAndFlush(b), now);
    }

    @Transactional
    public ProBookingView decline(ProPrincipal principal, String id, DeclineRequest r) {
        String note = note(r == null ? null : r.note());

        Professional p = lockedProfessional(principal);
        Booking b = lockedBooking(p, id);
        Instant now = clock.instant();
        requireWaiting(b, now);

        b.setStatus(BookingStatus.DECLINED);
        b.setProfessionalNote(note);
        b.setRespondedBy("PROFESSIONAL");
        b.setRespondedAt(now);
        return ProBookingView.from(bookings.saveAndFlush(b), now);
    }

    @Transactional
    public ProBookingView propose(ProPrincipal principal, String id, ProposeRequest r) {
        if (r == null) throw new ApiValidationException("Request body is required.");
        Instant proposed = proposedSlot(r.proposedSlot());
        Meeting meeting = meeting(r.meetingMethod(), r.meetingLink(), r.meetingAddress());
        String note = note(r.note());

        Professional p = lockedProfessional(principal);
        Booking b = lockedBooking(p, id);
        ZonedDateTime now = ZonedDateTime.now(clock);
        requireWaiting(b, now.toInstant());
        requireFitsRequest(b, meeting);
        requireFreeSlot(p, proposed, now);

        b.setStatus(BookingStatus.ALTERNATE_PROPOSED);
        b.setProposedDatetime(proposed);
        // The meeting details stay hidden from the user until the booking is CONFIRMED.
        apply(b, meeting, note, now.toInstant());
        b.setExpiresAt(now.toInstant().plus(USER_REPLY_WINDOW));
        return ProBookingView.from(bookings.saveAndFlush(b), now.toInstant());
    }

    // ---- Checks ----

    private static void requireWaiting(Booking b, Instant now) {
        if (b.getStatus() != BookingStatus.PENDING) throw new ConflictException(NOT_WAITING_MESSAGE);
        if (b.getExpiresAt() == null || !b.getExpiresAt().isAfter(now)) throw new ConflictException(EXPIRED_MESSAGE);
    }

    // The meeting method must match what the user asked for: online by phone or video, or in person.
    private static void requireFitsRequest(Booking b, Meeting meeting) {
        if ("ONLINE".equals(b.getPreferredMode()) && meeting.method().equals("IN_PERSON")) {
            throw new ApiValidationException("This request is for an online session, so meetingMethod must be PHONE or VIDEO.");
        }
        if ("IN_PERSON".equals(b.getPreferredMode()) && !meeting.method().equals("IN_PERSON")) {
            throw new ApiValidationException("This request is for an in-person session, so meetingMethod must be IN_PERSON.");
        }
        if (meeting.method().equals("PHONE") && (b.getContactPhone() == null || b.getContactPhone().isBlank())) {
            throw new ApiValidationException("This request has no phone number, so the session cannot be by phone. "
                    + "Choose VIDEO instead.");
        }
    }

    // The same rules as a user's booking: one of the professional's own slots, 24 hours to 60 days ahead,
    // not taken by a confirmed booking.
    private void requireFreeSlot(Professional p, Instant start, ZonedDateTime now) {
        if (start.isBefore(now.plusHours(AvailabilityService.MIN_NOTICE_HOURS).toInstant())) {
            throw new ApiValidationException("proposedSlot must be at least " + AvailabilityService.MIN_NOTICE_HOURS
                    + " hours from now.");
        }
        LocalDate lastDate = now.toLocalDate().plusDays(AvailabilityService.MAX_DAYS_AHEAD);
        if (start.atZone(AvailabilityService.ZONE).toLocalDate().isAfter(lastDate)) {
            throw new ApiValidationException("proposedSlot must be within the next " + AvailabilityService.MAX_DAYS_AHEAD
                    + " days.");
        }
        switch (slots.check(p, start, now)) {
            case FREE -> { }
            case TAKEN -> throw new ConflictException(OVERLAP_MESSAGE);
            case NOT_A_SLOT -> throw new ApiValidationException("proposedSlot is not one of your available times. "
                    + "Please choose a time from your weekly hours.");
        }
    }

    private static void apply(Booking b, Meeting meeting, String note, Instant now) {
        b.setMeetingMethod(meeting.method());
        b.setMeetingLink(meeting.link());
        b.setMeetingAddress(meeting.address());
        b.setProfessionalNote(note);
        b.setRespondedBy("PROFESSIONAL");
        b.setRespondedAt(now);
    }

    // ---- Lookups ----

    private UUID professionalId(ProPrincipal principal) {
        return accounts.findByClerkUserId(principal.clerkUserId())
                .orElseThrow(ProExceptions.NotRegisteredException::new)
                .getProfessionalId();
    }

    private Professional lockedProfessional(ProPrincipal principal) {
        return professionals.findByIdForUpdate(professionalId(principal))
                .orElseThrow(ProExceptions.NotRegisteredException::new);
    }

    private Booking lockedBooking(Professional p, String id) {
        return parseId(id).flatMap(bookings::findByIdForUpdate)
                .filter(b -> p.getId().equals(b.getProfessionalId()))
                .orElseThrow(ProExceptions.BookingNotFoundException::new);
    }

    // ---- Parsing ----

    private static Optional<UUID> parseId(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static BookingStatus status(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return null;
        try {
            return BookingStatus.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiValidationException("status must be one of " + Arrays.stream(BookingStatus.values())
                    .map(Enum::name).collect(Collectors.joining(", ")) + ".");
        }
    }

    // True for ALTERNATE, false for PREFERRED.
    private static boolean chosenSlot(String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.equals("PREFERRED")) return false;
            if (upper.equals("ALTERNATE")) return true;
        }
        throw new ApiValidationException("chosenSlot must be PREFERRED or ALTERNATE.");
    }

    // The link is kept only for VIDEO and the address only for IN_PERSON; the other one is ignored.
    // The contact-details check is deliberately not applied here: a link or an address is the point.
    private static Meeting meeting(String methodValue, String linkValue, String addressValue) {
        String trimmed = trimToNull(methodValue);
        String method = trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
        if (method == null || !(method.equals("PHONE") || method.equals("VIDEO") || method.equals("IN_PERSON"))) {
            throw new ApiValidationException("meetingMethod must be PHONE, VIDEO or IN_PERSON.");
        }
        return switch (method) {
            case "VIDEO" -> new Meeting(method, meetingLink(linkValue), null);
            case "IN_PERSON" -> new Meeting(method, null, meetingAddress(addressValue));
            default -> new Meeting(method, null, null);
        };
    }

    private static String meetingLink(String value) {
        String link = trimToNull(value);
        if (link == null) throw new ApiValidationException("meetingLink is required for a video session.");
        if (link.length() > MAX_LINK) {
            throw new ApiValidationException("meetingLink must be at most " + MAX_LINK + " characters.");
        }
        if (!link.startsWith("https://")) throw new ApiValidationException("meetingLink must start with https://.");
        try {
            URI uri = new URI(link).parseServerAuthority();
            if (uri.getHost() == null || !uri.getHost().contains(".")) throw new URISyntaxException(link, "no host");
        } catch (URISyntaxException e) {
            throw new ApiValidationException("meetingLink must be a valid web address, for example "
                    + "https://meet.google.com/abc-defg-hij.");
        }
        return link;
    }

    private static String meetingAddress(String value) {
        String address = trimToNull(value);
        if (address == null || address.length() < MIN_ADDRESS || address.length() > MAX_ADDRESS) {
            throw new ApiValidationException("meetingAddress is required for an in-person session and must be between "
                    + MIN_ADDRESS + " and " + MAX_ADDRESS + " characters.");
        }
        return address;
    }

    private static String note(String value) {
        String note = trimToNull(value);
        if (note == null) return null;
        if (note.length() > MAX_NOTE) throw new ApiValidationException("note must be at most " + MAX_NOTE + " characters.");
        ContactDetailsGuard.check("note", note).ifPresent(message -> {
            throw new ApiValidationException(message);
        });
        return note;
    }

    private static Instant proposedSlot(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiValidationException("proposedSlot is required, for example 2026-10-20T18:00:00+05:30.");
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiValidationException("proposedSlot must be a date and time with an offset, for example "
                    + "2026-10-20T18:00:00+05:30.");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
