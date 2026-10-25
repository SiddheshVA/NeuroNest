package com.neuronest.backend.booking;

import com.neuronest.backend.booking.BookingRepository.BookedRange;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.DateRange;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalBlockedSlot;
import com.neuronest.backend.professional.ProfessionalBlockedSlotRepository;
import com.neuronest.backend.professional.ProfessionalDayOff;
import com.neuronest.backend.professional.ProfessionalDayOffRepository;
import com.neuronest.backend.professional.SessionGrid;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A professional's bookable slots: AvailabilityService's slots for their weekly hours on the global
// SessionGrid, minus their days off and every slot whose blocked time (session plus break) overlaps the time
// an AWAITING_PAYMENT or CONFIRMED booking blocks (all of its sessions and breaks, see
// BookingRepository.findBookedRanges) or that the professional blocked (ProfessionalBlockedSlot). Shared by
// the public availability endpoint, the booking check, the professional's confirm, propose and accept checks
// and the professional's block screen, so all of them always agree.
@Service
public class SlotService {

    /**
     * DAY_OFF: a slot on the weekly hours, but on a date the professional marked as off. NOT_CONSECUTIVE:
     * the start is a slot, but the sessions after it are not back to back on the same day.
     */
    public enum SlotCheck { FREE, TAKEN, DAY_OFF, NOT_A_SLOT, NOT_CONSECUTIVE }

    private final AvailabilityService availability;
    private final BookingRepository bookings;
    private final ProfessionalDayOffRepository daysOff;
    private final ProfessionalBlockedSlotRepository blocks;

    public SlotService(AvailabilityService availability, BookingRepository bookings, ProfessionalDayOffRepository daysOff,
                       ProfessionalBlockedSlotRepository blocks) {
        this.availability = availability;
        this.bookings = bookings;
        this.daysOff = daysOff;
        this.blocks = blocks;
    }

    /** The time one slot blocks: one session plus the break after it. */
    public static Duration occupied() {
        return SessionGrid.current().step();
    }

    // What an old booking stored without any end blocks: the session length and buffer the professional
    // had chosen before the global grid, or one grid slot when they never chose one.
    private static Duration legacyOccupied(Professional p) {
        if (p.getSessionMinutes() == null) return occupied();
        return Duration.ofMinutes(p.getSessionMinutes() + Math.max(0, p.getBufferMinutes()));
    }

    // The grid's slots for these dates, before days off and bookings are taken out.
    private Map<LocalDate, List<ZonedDateTime>> gridSlots(Professional p, LocalDate from, LocalDate to, ZonedDateTime now,
                                                          Set<ZonedDateTime> taken, List<DateRange> off) {
        SessionGrid grid = SessionGrid.current();
        return availability.freeSlots(p.getWeeklyHours(), grid.sessionMinutes(), grid.breakMinutes(), from, to, now,
                taken, off);
    }

    /** Free slots per date, from and to included, with days off and taken slots removed. */
    public Map<LocalDate, List<ZonedDateTime>> freeSlots(Professional p, LocalDate from, LocalDate to, ZonedDateTime now) {
        List<DateRange> off = daysOff(p, from, to);
        Map<LocalDate, List<ZonedDateTime>> all = gridSlots(p, from, to, now, Set.of(), off);
        if (all.isEmpty()) return all;
        Set<ZonedDateTime> taken = takenSlots(p, from, to, all);
        return taken.isEmpty() ? all : gridSlots(p, from, to, now, taken, off);
    }

    /**
     * Whether this exact start time is a free slot, a slot that is already taken, a slot on one of the
     * professional's days off, or not a slot at all.
     */
    public SlotCheck check(Professional p, Instant start, ZonedDateTime now) {
        return checkRun(p, start, 1, now);
    }

    /**
     * Whether {@code count} consecutive slots starting at {@code start} can all be booked: each one is a
     * grid slot on the same day, each starts exactly one session plus break after the one before (next to
     * each other in the grid, not across two ranges), and none is taken or on a day off. Together they block
     * start to start + count x (session + break), the same span the no-overlap constraint covers.
     */
    public SlotCheck checkRun(Professional p, Instant start, int count, ZonedDateTime now) {
        LocalDate date = start.atZone(AvailabilityService.ZONE).toLocalDate();
        List<ZonedDateTime> day = gridSlots(p, date, date, now, Set.of(), List.of()).getOrDefault(date, List.of());
        int first = -1;
        for (int i = 0; i < day.size(); i++) {
            if (day.get(i).toInstant().equals(start)) {
                first = i;
                break;
            }
        }
        if (first < 0) return SlotCheck.NOT_A_SLOT;
        if (first + count > day.size()) return SlotCheck.NOT_CONSECUTIVE;
        Duration step = occupied();
        for (int i = 1; i < count; i++) {
            if (!day.get(first + i).toInstant().equals(start.plus(step.multipliedBy(i)))) return SlotCheck.NOT_CONSECUTIVE;
        }
        if (isDayOff(p, start)) return SlotCheck.DAY_OFF;
        List<ZonedDateTime> run = day.subList(first, first + count);
        return takenSlots(p, date, date, Map.of(date, run)).isEmpty() ? SlotCheck.FREE : SlotCheck.TAKEN;
    }

    /** Whether the date of {@code start} (in India) is one of the professional's days off. */
    public boolean isDayOff(Professional p, Instant start) {
        LocalDate date = start.atZone(AvailabilityService.ZONE).toLocalDate();
        return AvailabilityService.isDayOff(date, daysOff(p, date, date));
    }

    private List<DateRange> daysOff(Professional p, LocalDate from, LocalDate to) {
        return daysOff.findOverlapping(p.getId(), from, to).stream().map(ProfessionalDayOff::range).toList();
    }

    /**
     * Whether one session starting at {@code start} (with its break) would overlap the time any
     * AWAITING_PAYMENT or CONFIRMED booking of this professional blocks. Used when a professional confirms or proposes a time and when
     * a user accepts a proposal; callers lock the professional row first.
     */
    public boolean overlapsBooked(Professional p, Instant start) {
        Duration occupied = occupied();
        Instant end = start.plus(occupied);
        return takenRanges(p, start, end).stream().anyMatch(range -> range.overlaps(start, end));
    }

    /** Every grid slot per date, from and to included, without days off: no notice period, nothing taken out. */
    public Map<LocalDate, List<ZonedDateTime>> gridSlots(Professional p, LocalDate from, LocalDate to) {
        SessionGrid grid = SessionGrid.current();
        return availability.gridSlots(p.getWeeklyHours(), grid.sessionMinutes(), grid.breakMinutes(), from, to,
                daysOff(p, from, to));
    }

    /** The time this professional's AWAITING_PAYMENT and CONFIRMED bookings block within [from, to). */
    public List<BookedRange> bookedRanges(Professional p, Instant from, Instant to) {
        return bookings.findBookedRanges(p.getId(), from, to, legacyOccupied(p));
    }

    /** This professional's blocked slots within [from, to). */
    public List<ProfessionalBlockedSlot> blockedSlots(Professional p, Instant from, Instant to) {
        return blocks.findOverlapping(p.getId(), from, to);
    }

    // Booked and blocked time together: what a client can never book.
    private List<BookedRange> takenRanges(Professional p, Instant from, Instant to) {
        List<BookedRange> taken = new ArrayList<>(bookedRanges(p, from, to));
        blockedSlots(p, from, to).forEach(b -> taken.add(new BookedRange(b.getStartsAt(), b.getBlockedUntil())));
        return taken;
    }

    // The slots in `slots` whose blocked time (session plus break) overlaps an AWAITING_PAYMENT or CONFIRMED
    // booking or a slot the professional blocked.
    private Set<ZonedDateTime> takenSlots(Professional p, LocalDate from, LocalDate to,
                                          Map<LocalDate, List<ZonedDateTime>> slots) {
        Duration occupied = occupied();
        Instant windowStart = from.atStartOfDay(AvailabilityService.ZONE).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(AvailabilityService.ZONE).toInstant().plus(occupied);
        List<BookedRange> booked = takenRanges(p, windowStart, windowEnd);
        Set<ZonedDateTime> taken = new HashSet<>();
        if (booked.isEmpty()) return taken;
        for (List<ZonedDateTime> day : slots.values()) {
            for (ZonedDateTime slot : day) {
                Instant start = slot.toInstant();
                Instant end = start.plus(occupied);
                if (booked.stream().anyMatch(range -> range.overlaps(start, end))) taken.add(slot);
            }
        }
        return taken;
    }
}
