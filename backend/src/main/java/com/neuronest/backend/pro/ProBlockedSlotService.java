package com.neuronest.backend.pro;

import com.neuronest.backend.booking.BookingRepository.BookedRange;
import com.neuronest.backend.booking.SlotService;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProBlockedSlotDtos.BlockSlotRequest;
import com.neuronest.backend.pro.ProBlockedSlotDtos.BlockWeek;
import com.neuronest.backend.pro.ProBlockedSlotDtos.DayView;
import com.neuronest.backend.pro.ProBlockedSlotDtos.SlotView;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalBlockedSlot;
import com.neuronest.backend.professional.ProfessionalBlockedSlotRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.SessionGrid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// "Block slots": a professional blocks their own free grid slots in the next 7 days (an emergency, a meeting,
// an event) and unblocks them later. A block covers what a one-session booking there would (the slot and the
// break after it, SessionGrid), so clients are never offered it and cannot book it (SlotService, and the V18
// trigger in the database). A slot a client's held or confirmed booking covers is "Booked" and cannot be
// blocked; changing those goes through support (no cancellation or refund here). Blocking locks the
// professional's row, the same lock a booking takes, so a block and a booking never both win.
@Service
public class ProBlockedSlotService {

    /** Today and the 6 days after it, in India. */
    static final int DAYS = 7;
    static final String BOOKED_MESSAGE = "This time is booked by a client, so it cannot be blocked. "
            + "To change a booked session, please contact NeuroNest support.";
    static final String NOT_A_SLOT_MESSAGE = "Choose one of your own times in the next 7 days that has not started yet.";
    static final String JUST_TAKEN_MESSAGE = "This time was just booked or blocked. Please refresh and try again.";

    private final ProfessionalAccountRepository accounts;
    private final ProfessionalRepository professionals;
    private final ProfessionalBlockedSlotRepository blocks;
    private final SlotService slots;
    private final Clock clock;

    @Autowired
    public ProBlockedSlotService(ProfessionalAccountRepository accounts, ProfessionalRepository professionals,
                                 ProfessionalBlockedSlotRepository blocks, SlotService slots) {
        this(accounts, professionals, blocks, slots, Clock.systemUTC());
    }

    ProBlockedSlotService(ProfessionalAccountRepository accounts, ProfessionalRepository professionals,
                          ProfessionalBlockedSlotRepository blocks, SlotService slots, Clock clock) {
        this.accounts = accounts;
        this.professionals = professionals;
        this.blocks = blocks;
        this.slots = slots;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BlockWeek week(ProPrincipal principal) {
        Professional p = professionals.findById(professionalId(principal))
                .orElseThrow(ProExceptions.NotAProfessionalException::new);
        return week(p, clock.instant());
    }

    @Transactional
    public BlockWeek block(ProPrincipal principal, BlockSlotRequest request) {
        Instant start = start(request);
        // The same lock a booking takes first, so the booking or this block waits for the other.
        Professional p = professionals.findByIdForUpdate(professionalId(principal))
                .orElseThrow(ProExceptions.NotAProfessionalException::new);
        Instant now = clock.instant();
        SlotView slot = week(p, now).days().stream().flatMap(day -> day.slots().stream())
                .filter(s -> s.start().toInstant().equals(start))
                .findFirst()
                .orElseThrow(() -> new ApiValidationException(NOT_A_SLOT_MESSAGE));
        switch (slot.status()) {
            case "BOOKED" -> throw new ConflictException(BOOKED_MESSAGE);
            case "BLOCKED" -> { return week(p, now); }
            default -> { }
        }
        try {
            blocks.saveAndFlush(new ProfessionalBlockedSlot(p.getId(), start, start.plus(SlotService.occupied())));
        } catch (DataIntegrityViolationException e) {
            // The database's guards (V18): a booking or another block got there first.
            throw new ConflictException(JUST_TAKEN_MESSAGE);
        }
        return week(p, now);
    }

    @Transactional
    public BlockWeek unblock(ProPrincipal principal, String id) {
        UUID professionalId = professionalId(principal);
        ProfessionalBlockedSlot block = parseId(id).flatMap(uuid -> blocks.findByIdAndProfessionalId(uuid, professionalId))
                .orElseThrow(ProExceptions.BlockedSlotNotFoundException::new);
        blocks.delete(block);
        blocks.flush();
        Professional p = professionals.findById(professionalId).orElseThrow(ProExceptions.NotAProfessionalException::new);
        return week(p, clock.instant());
    }

    // Every grid slot from now to the end of the 7th day, with what covers it: a booking first, then a block.
    private BlockWeek week(Professional p, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, AvailabilityService.ZONE);
        LocalDate last = today.plusDays(DAYS - 1);
        Map<LocalDate, List<ZonedDateTime>> grid = slots.gridSlots(p, today, last);
        Instant from = today.atStartOfDay(AvailabilityService.ZONE).toInstant();
        Instant to = last.plusDays(1).atStartOfDay(AvailabilityService.ZONE).toInstant().plus(SlotService.occupied());
        List<BookedRange> booked = slots.bookedRanges(p, from, to);
        List<ProfessionalBlockedSlot> blocked = slots.blockedSlots(p, from, to);
        SessionGrid sessionGrid = SessionGrid.current();

        List<DayView> days = new ArrayList<>();
        for (LocalDate date = today; !date.isAfter(last); date = date.plusDays(1)) {
            List<SlotView> views = new ArrayList<>();
            for (ZonedDateTime slot : grid.getOrDefault(date, List.of())) {
                Instant start = slot.toInstant();
                if (!start.isAfter(now)) continue;
                Instant end = start.plus(SlotService.occupied());
                Optional<ProfessionalBlockedSlot> block = blocked.stream().filter(b -> b.overlaps(start, end)).findFirst();
                String status = booked.stream().anyMatch(range -> range.overlaps(start, end)) ? "BOOKED"
                        : block.isPresent() ? "BLOCKED" : "FREE";
                views.add(new SlotView(slot.toOffsetDateTime(),
                        sessionGrid.meetingEnd(start, 1).atZone(AvailabilityService.ZONE).toOffsetDateTime(), status,
                        "BLOCKED".equals(status) ? block.get().getId() : null));
            }
            days.add(new DayView(date, List.copyOf(views)));
        }
        return new BlockWeek(sessionGrid.sessionMinutes(), sessionGrid.breakMinutes(), List.copyOf(days));
    }

    private UUID professionalId(ProPrincipal principal) {
        return accounts.findByClerkUserId(principal.clerkUserId())
                .orElseThrow(ProExceptions.NotAProfessionalException::new).getProfessionalId();
    }

    private static Instant start(BlockSlotRequest request) {
        String text = request == null || request.start() == null ? "" : request.start().trim();
        if (text.isEmpty()) throw new ApiValidationException("start is required.");
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiValidationException("start must be a date and time with an offset, for example "
                    + "2026-10-29T19:45:00+05:30.");
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
