package com.neuronest.backend.booking;

import com.neuronest.backend.assessment.AssessmentResult;
import com.neuronest.backend.assessment.AssessmentResultRepository;
import com.neuronest.backend.auth.User;
import com.neuronest.backend.auth.UserRepository;
import com.neuronest.backend.booking.BookingDtos.AwaitingPayment;
import com.neuronest.backend.booking.BookingDtos.BookingPage;
import com.neuronest.backend.booking.BookingDtos.BookingSummary;
import com.neuronest.backend.booking.BookingDtos.NextAppointment;
import com.neuronest.backend.booking.BookingDtos.BookingView;
import com.neuronest.backend.booking.BookingDtos.CreateBookingRequest;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.ConsultationMode;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.PublicProfessionalService;
import com.neuronest.backend.professional.WeeklyHours;
import com.neuronest.backend.notification.BookingEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// A signed-in user's booking requests: creating one for an exact free slot, listing and reading their
// own, cancelling, and accepting a time the professional suggested. Nothing here logs request bodies,
// notes, phone numbers or assessment data.
@Service
public class BookingService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int MIN_NAME = 2;
    static final int MAX_NAME = 80;
    static final int MAX_NOTE = 500;
    static final int MAX_OPEN_PER_USER = 3;
    static final int MAX_OPEN_PER_PROFESSIONAL = 1;
    static final int MAX_NEW_PER_DAY = 5;
    static final Duration EXPIRES_AFTER = Duration.ofHours(72);
    static final String TAKEN_MESSAGE = "This time is no longer available. Please choose another time.";
    static final String PROPOSAL_TAKEN_MESSAGE = "This time is no longer available.";
    /** The user's time to pay once a time is agreed (bookings.payment-window-hours). */
    public static final Duration DEFAULT_PAYMENT_WINDOW = Duration.ofHours(24);

    // A 10-digit Indian mobile number starting 6 to 9, optionally written with +91 or a leading 0.
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^(?:\\+91|0)?([6-9][0-9]{9})$");

    private final BookingRepository bookings;
    private final SlotService slots;
    private final PublicProfessionalService directory;
    private final ProfessionalRepository professionals;
    private final ProfessionalAccountRepository accounts;
    private final UserRepository users;
    private final AssessmentResultRepository assessments;
    private final ApplicationEventPublisher events;
    private final PaymentAmounts amounts;
    private final Clock clock;
    private Duration paymentWindow = DEFAULT_PAYMENT_WINDOW;

    @Autowired
    public BookingService(BookingRepository bookings, SlotService slots, PublicProfessionalService directory,
                          ProfessionalRepository professionals, ProfessionalAccountRepository accounts,
                          UserRepository users, AssessmentResultRepository assessments,
                          ApplicationEventPublisher events, PaymentAmounts amounts) {
        this(bookings, slots, directory, professionals, accounts, users, assessments, events, amounts,
                Clock.system(AvailabilityService.ZONE));
    }

    BookingService(BookingRepository bookings, SlotService slots, PublicProfessionalService directory,
                   ProfessionalRepository professionals, ProfessionalAccountRepository accounts,
                   UserRepository users, AssessmentResultRepository assessments, ApplicationEventPublisher events,
                   PaymentAmounts amounts, Clock clock) {
        this.bookings = bookings;
        this.slots = slots;
        this.directory = directory;
        this.professionals = professionals;
        this.accounts = accounts;
        this.users = users;
        this.assessments = assessments;
        this.events = events;
        this.amounts = amounts;
        this.clock = clock;
    }

    @Autowired
    void setPaymentWindowHours(@Value("${bookings.payment-window-hours:24}") long hours) {
        this.paymentWindow = Duration.ofHours(hours);
    }

    // ---- Creating ----

    @Transactional
    public BookingView create(UUID userId, CreateBookingRequest r) {
        if (r == null) {
            throw new ApiValidationException("Request body is required.");
        }
        if (!Boolean.TRUE.equals(r.consentGiven())) {
            throw new ApiValidationException("consentGiven must be true. Please agree to share your details with "
                    + "the professional before you send the request.");
        }
        String contactName = trimToNull(r.contactName());
        if (contactName == null || contactName.length() < MIN_NAME || contactName.length() > MAX_NAME) {
            throw new ApiValidationException("contactName must be between " + MIN_NAME + " and " + MAX_NAME + " characters.");
        }
        String contactPhone = phone(r.contactPhone());
        String note = trimToNull(r.note());
        if (note != null && note.length() > MAX_NOTE) {
            throw new ApiValidationException("note must be at most " + MAX_NOTE + " characters.");
        }
        ConsultationMode mode = preferredMode(r.preferredMode());
        String method = preferredMethod(mode, r.preferredMethod());
        Instant slotStart = slotTime("slotStart", r.slotStart(), true);
        Instant alternateStart = slotTime("alternateSlotStart", r.alternateSlotStart(), false);
        if (alternateStart != null && alternateStart.equals(slotStart)) {
            throw new ApiValidationException("alternateSlotStart must be different from slotStart.");
        }

        User user = users.findById(userId).orElseThrow(BookingExceptions.NotSignedInException::new);
        String professionalId = trimToNull(r.professionalId());
        Professional p = (professionalId == null ? Optional.<Professional>empty() : directory.findVisible(professionalId))
                .orElseThrow(() -> new BookingExceptions.NotFoundException("This professional was not found."));
        if (p.isSampleData()) {
            throw new ConflictException("This is a sample listing and cannot be booked.");
        }
        if (user.getClerkUserId() != null && accounts.findByClerkUserId(user.getClerkUserId())
                .filter(account -> p.getId().equals(account.getProfessionalId())).isPresent()) {
            throw new ConflictException("You cannot book your own professional listing.");
        }
        if (p.getSessionMinutes() == null || !WeeklyHours.hasAnyRange(p.getWeeklyHours())) {
            throw new ConflictException("This professional has not set their availability yet.");
        }
        if (p.getConsultationMode() != ConsultationMode.BOTH && p.getConsultationMode() != mode) {
            throw new ApiValidationException("preferredMode must be " + p.getConsultationMode().name()
                    + ": this professional only offers "
                    + (p.getConsultationMode() == ConsultationMode.ONLINE ? "online" : "in-person") + " sessions.");
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        checkSlot(p, "slotStart", slotStart, now);
        if (alternateStart != null) checkSlot(p, "alternateSlotStart", alternateStart, now);

        Instant nowInstant = now.toInstant();
        if (bookings.countByUserIdAndCreatedAtAfter(userId, nowInstant.minus(Duration.ofHours(24))) >= MAX_NEW_PER_DAY) {
            throw new BookingExceptions.TooManyRequestsException("You have sent " + MAX_NEW_PER_DAY + " booking requests "
                    + "in the last 24 hours. Please try again later.");
        }
        if (bookings.countOpenByUser(userId, BookingStatus.OPEN, nowInstant) >= MAX_OPEN_PER_USER) {
            throw new ConflictException("You already have " + MAX_OPEN_PER_USER + " open booking requests. Please wait "
                    + "for a reply or cancel one before you send another.");
        }
        if (bookings.countOpenByUserAndProfessional(userId, p.getId(), BookingStatus.OPEN, nowInstant)
                >= MAX_OPEN_PER_PROFESSIONAL) {
            throw new ConflictException("You already have an open request with this professional. Please wait for a "
                    + "reply or cancel it before you send another.");
        }

        // Only read when the user ticked it, and only results under sessions the user owns.
        SharedAssessment shared = null;
        if (Boolean.TRUE.equals(r.shareLatestAssessment())) {
            List<AssessmentResult> latest = assessments.findLatestForUser(userId, PageRequest.of(0, 1));
            if (latest.isEmpty()) {
                throw new ApiValidationException("You have no saved assessment result to share.");
            }
            shared = SharedAssessment.from(latest.get(0));
        }

        Booking b = new Booking();
        b.setProfessionalId(p.getId());
        b.setUserId(userId);
        b.setContactName(contactName);
        // The email on the verified Clerk account, never one from the request.
        b.setContactEmail(user.getEmail());
        b.setContactPhone(contactPhone);
        b.setPreferredMode(mode.name());
        b.setPreferredMethod(method);
        b.setPreferredDatetime(slotStart);
        b.setAlternateDatetime(alternateStart);
        b.setNote(note);
        b.setStatus(BookingStatus.PENDING);
        b.setRespondedBy(null);
        b.setCreatedAt(nowInstant);
        b.setExpiresAt(nowInstant.plus(EXPIRES_AFTER));
        b.setConsentGivenAt(nowInstant);
        b.setSharedAssessment(shared);
        b.setAssessmentSharedAt(shared == null ? null : nowInstant);
        Booking saved = bookings.saveAndFlush(b);
        // Emails go out only after this transaction commits (BookingNotificationListener).
        events.publishEvent(new BookingEvent(BookingEvent.Type.REQUEST_CREATED, saved.getId()));
        return BookingView.from(saved, p);
    }

    // The start time must be exactly one of the professional's free slots.
    private void checkSlot(Professional p, String field, Instant start, ZonedDateTime now) {
        if (start.isBefore(now.plusHours(AvailabilityService.MIN_NOTICE_HOURS).toInstant())) {
            throw new ApiValidationException(field + " must be at least " + AvailabilityService.MIN_NOTICE_HOURS
                    + " hours from now.");
        }
        LocalDate lastDate = now.toLocalDate().plusDays(AvailabilityService.MAX_DAYS_AHEAD);
        if (start.atZone(AvailabilityService.ZONE).toLocalDate().isAfter(lastDate)) {
            throw new ApiValidationException(field + " must be within the next " + AvailabilityService.MAX_DAYS_AHEAD
                    + " days.");
        }
        switch (slots.check(p, start, now)) {
            case FREE -> { }
            case TAKEN, DAY_OFF -> throw new ConflictException(TAKEN_MESSAGE);
            case NOT_A_SLOT -> throw new ApiValidationException(field + " is not one of this professional's available "
                    + "times. Please choose a time from their availability.");
        }
    }

    // ---- Reading ----

    @Transactional(readOnly = true)
    public BookingPage mine(UUID userId, int page, int size) {
        if (page < 0) throw new ApiValidationException("page must be 0 or greater.");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        Page<Booking> result = bookings.findByUserId(userId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        Map<UUID, Professional> byId = professionals.findAllById(
                        result.getContent().stream().map(Booking::getProfessionalId).distinct().toList())
                .stream().collect(Collectors.toMap(Professional::getId, Function.identity()));
        return new BookingPage(result.getContent().stream()
                .map(b -> view(b, byId.get(b.getProfessionalId()))).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    // Only the user's own bookings: the next confirmed (paid) appointment, the agreed bookings still waiting
    // for payment whose deadline has not passed, and the open requests that have not expired.
    @Transactional(readOnly = true)
    public BookingSummary summary(UUID userId) {
        Instant now = clock.instant();
        NextAppointment next = bookings
                .findFirstByUserIdAndStatusAndPreferredDatetimeGreaterThanEqualOrderByPreferredDatetimeAscIdAsc(
                        userId, BookingStatus.CONFIRMED, now)
                .map(b -> NextAppointment.from(b, professionals.findById(b.getProfessionalId()).orElse(null)))
                .orElse(null);
        List<Booking> unpaid = bookings.findByUserIdAndStatusAndPaymentDueAtAfterOrderByPaymentDueAtAscIdAsc(
                userId, BookingStatus.AWAITING_PAYMENT, now);
        Map<UUID, Professional> byId = professionals.findAllById(
                        unpaid.stream().map(Booking::getProfessionalId).distinct().toList())
                .stream().collect(Collectors.toMap(Professional::getId, Function.identity()));
        return new BookingSummary(next,
                unpaid.stream().map(b -> {
                    Professional p = byId.get(b.getProfessionalId());
                    return AwaitingPayment.from(b, p, amounts.due(b, p).orElse(null));
                }).toList(),
                bookings.countByUserIdAndStatusAndExpiresAtAfter(userId, BookingStatus.PENDING, now),
                bookings.countByUserIdAndStatusAndExpiresAtAfter(userId, BookingStatus.ALTERNATE_PROPOSED, now));
    }

    // Someone else's booking gets the same 404 as a missing one.
    @Transactional(readOnly = true)
    public BookingView get(UUID userId, String id) {
        Booking b = parseId(id).flatMap(uuid -> bookings.findByIdAndUserId(uuid, userId)).orElseThrow(BookingService::notFound);
        return view(b, professionals.findById(b.getProfessionalId()).orElse(null));
    }

    // While AWAITING_PAYMENT, with the amount paying would charge (PaymentAmounts).
    private BookingView view(Booking b, Professional p) {
        PaymentAmounts.Amount due = b.getStatus() == BookingStatus.AWAITING_PAYMENT ? amounts.due(b, p).orElse(null) : null;
        return BookingView.from(b, p, due);
    }

    // ---- Cancelling ----

    // The row is locked and its status re-checked inside the transaction, so two quick clicks cannot both
    // cancel. An AWAITING_PAYMENT booking can be cancelled too: nothing has been paid (a verified payment
    // makes it CONFIRMED in the same locked transaction), so there is nothing to refund.
    @Transactional
    public BookingView cancel(UUID userId, String id) {
        Booking b = parseId(id).flatMap(bookings::findByIdForUpdate)
                .filter(found -> userId.equals(found.getUserId()))
                .orElseThrow(BookingService::notFound);
        if (!BookingStatus.OPEN.contains(b.getStatus()) && b.getStatus() != BookingStatus.AWAITING_PAYMENT) {
            throw new ConflictException("This booking request can no longer be cancelled. Its status is "
                    + b.getStatus().name() + ".");
        }
        b.setStatus(BookingStatus.CANCELLED);
        b.setRespondedBy("USER");
        b.setRespondedAt(clock.instant());
        Booking saved = bookings.saveAndFlush(b);
        events.publishEvent(new BookingEvent(BookingEvent.Type.CANCELLED_BY_USER, saved.getId()));
        return BookingView.from(saved, professionals.findById(saved.getProfessionalId()).orElse(null));
    }

    // ---- Accepting a proposed time ----

    // The professional row is locked first (the same lock the professional's confirm takes), then the
    // booking, and both the status and the time are re-checked inside the transaction, so two quick
    // requests cannot both succeed or double-book. The booking then waits for payment (AWAITING_PAYMENT,
    // which already blocks the slot) and becomes CONFIRMED once BookingPaymentService verifies it.
    @Transactional
    public BookingView acceptProposal(UUID userId, String id) {
        UUID professionalId = parseId(id).flatMap(uuid -> bookings.findProfessionalIdByIdAndUserId(uuid, userId))
                .orElseThrow(BookingService::notFound);
        Professional p = professionals.findByIdForUpdate(professionalId)
                .orElseThrow(() -> new ConflictException(PROPOSAL_TAKEN_MESSAGE));
        Booking b = parseId(id).flatMap(bookings::findByIdForUpdate)
                .filter(found -> userId.equals(found.getUserId()))
                .orElseThrow(BookingService::notFound);
        Instant now = clock.instant();
        if (b.getStatus() != BookingStatus.ALTERNATE_PROPOSED) {
            throw new ConflictException("This request has no suggested time waiting for your answer.");
        }
        if (b.getExpiresAt() == null || !b.getExpiresAt().isAfter(now)) {
            throw new ConflictException("This suggested time has expired.");
        }
        Instant proposed = b.getProposedDatetime();
        if (proposed == null || !proposed.isAfter(now) || slots.isDayOff(p, proposed)
                || slots.overlapsBooked(p, proposed)) {
            throw new ConflictException(PROPOSAL_TAKEN_MESSAGE);
        }
        // A free session (no fee) needs no payment: it is CONFIRMED now and payment_due_at stays empty.
        boolean paid = BookingPaymentService.requiresPayment(p);
        b.setStatus(paid ? BookingStatus.AWAITING_PAYMENT : BookingStatus.CONFIRMED);
        b.setPaymentDueAt(paid ? now.plus(paymentWindow) : null);
        b.setPreferredDatetime(proposed);
        b.setRespondedBy("USER");
        b.setRespondedAt(now);
        Booking saved = bookings.saveAndFlush(b);
        events.publishEvent(new BookingEvent(paid ? BookingEvent.Type.AWAITING_PAYMENT : BookingEvent.Type.CONFIRMED,
                saved.getId()));
        return view(saved, p);
    }

    // ---- Parsing ----

    private static BookingExceptions.NotFoundException notFound() {
        return new BookingExceptions.NotFoundException("This booking request was not found.");
    }

    private static Optional<UUID> parseId(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static ConsultationMode preferredMode(String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.equals("ONLINE")) return ConsultationMode.ONLINE;
            if (upper.equals("IN_PERSON")) return ConsultationMode.IN_PERSON;
        }
        throw new ApiValidationException("preferredMode must be ONLINE or IN_PERSON.");
    }

    // Required for ONLINE (PHONE or VIDEO). Ignored for IN_PERSON, which stores null.
    private static String preferredMethod(ConsultationMode mode, String value) {
        if (mode != ConsultationMode.ONLINE) return null;
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiValidationException("preferredMethod is required for an online session: PHONE or VIDEO.");
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!upper.equals("PHONE") && !upper.equals("VIDEO")) {
            throw new ApiValidationException("preferredMethod must be PHONE or VIDEO.");
        }
        return upper;
    }

    // ISO-8601 with an offset, for example 2026-10-20T18:00:00+05:30.
    private static Instant slotTime(String field, String value, boolean required) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            if (required) {
                throw new ApiValidationException(field + " is required, for example 2026-10-20T18:00:00+05:30.");
            }
            return null;
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiValidationException(field + " must be a date and time with an offset, for example "
                    + "2026-10-20T18:00:00+05:30.");
        }
    }

    // Required. Stored as +91XXXXXXXXXX.
    private static String phone(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ApiValidationException("A phone number is required so the professional can reach you after confirming.");
        }
        Matcher m = INDIAN_MOBILE.matcher(trimmed.replaceAll("[\\s()\\-]", ""));
        if (!m.matches()) {
            throw new ApiValidationException("contactPhone must be a 10-digit Indian mobile number starting with 6, 7, "
                    + "8 or 9. You may write it with +91 or a leading 0.");
        }
        return "+91" + m.group(1);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
