package com.neuronest.backend.booking;

import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.DateRange;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalDayOff;
import com.neuronest.backend.professional.ProfessionalDayOffRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A professional's bookable slots: AvailabilityService's slots for their weekly hours, minus their days
// off and every slot that overlaps a CONFIRMED booking. Shared by the public availability endpoint, the booking check and the
// professional's confirm, propose and accept checks, so all of them always agree.
@Service
public class SlotService {

    /** DAY_OFF: a slot on the weekly hours, but on a date the professional marked as off. */
    public enum SlotCheck { FREE, TAKEN, DAY_OFF, NOT_A_SLOT }

    private final AvailabilityService availability;
    private final BookingRepository bookings;
    private final ProfessionalDayOffRepository daysOff;

    public SlotService(AvailabilityService availability, BookingRepository bookings, ProfessionalDayOffRepository daysOff) {
        this.availability = availability;
        this.bookings = bookings;
        this.daysOff = daysOff;
    }

    /** Free slots per date, from and to included, with days off and taken slots removed. */
    public Map<LocalDate, List<ZonedDateTime>> freeSlots(Professional p, LocalDate from, LocalDate to, ZonedDateTime now) {
        List<DateRange> off = daysOff(p, from, to);
        Map<LocalDate, List<ZonedDateTime>> all = availability.freeSlots(p.getWeeklyHours(), p.getSessionMinutes(),
                p.getBufferMinutes(), from, to, now, Set.of(), off);
        if (all.isEmpty()) return all;
        Set<ZonedDateTime> taken = takenSlots(p, from, to, all);
        return taken.isEmpty() ? all : availability.freeSlots(p.getWeeklyHours(), p.getSessionMinutes(),
                p.getBufferMinutes(), from, to, now, taken, off);
    }

    /**
     * Whether this exact start time is a free slot, a slot that is already taken, a slot on one of the
     * professional's days off, or not a slot at all.
     */
    public SlotCheck check(Professional p, Instant start, ZonedDateTime now) {
        LocalDate date = start.atZone(AvailabilityService.ZONE).toLocalDate();
        Map<LocalDate, List<ZonedDateTime>> all = availability.freeSlots(p.getWeeklyHours(), p.getSessionMinutes(),
                p.getBufferMinutes(), date, date, now, Set.of());
        boolean isSlot = all.getOrDefault(date, List.of()).stream().anyMatch(slot -> slot.toInstant().equals(start));
        if (!isSlot) return SlotCheck.NOT_A_SLOT;
        if (isDayOff(p, start)) return SlotCheck.DAY_OFF;
        boolean taken = takenSlots(p, date, date, all).stream().anyMatch(slot -> slot.toInstant().equals(start));
        return taken ? SlotCheck.TAKEN : SlotCheck.FREE;
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
     * Whether a session starting at {@code start} would overlap the scheduled time of any CONFIRMED booking
     * of this professional, using their session length and buffer. Used when a professional confirms or
     * proposes a time and when a user accepts a proposal; callers lock the professional row first.
     */
    public boolean overlapsConfirmed(Professional p, Instant start) {
        int session = p.getSessionMinutes() == null ? 0 : p.getSessionMinutes();
        int occupied = session + Math.max(0, p.getBufferMinutes());
        List<Instant> scheduled = bookings.findConfirmedScheduledTimes(p.getId(),
                start.minusSeconds(occupied * 60L), start.plusSeconds(occupied * 60L));
        if (scheduled.isEmpty()) return false;
        ZonedDateTime slot = start.atZone(AvailabilityService.ZONE);
        return !availability.overlapping(Map.of(slot.toLocalDate(), List.of(slot)), scheduled, session,
                p.getBufferMinutes()).isEmpty();
    }

    // The slots in `slots` that overlap a confirmed booking. Each session occupies its length plus the
    // buffer, so the query window is widened by that much on both sides.
    private Set<ZonedDateTime> takenSlots(Professional p, LocalDate from, LocalDate to,
                                          Map<LocalDate, List<ZonedDateTime>> slots) {
        int occupied = p.getSessionMinutes() + p.getBufferMinutes();
        Instant windowStart = from.atStartOfDay(AvailabilityService.ZONE).minusMinutes(occupied).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(AvailabilityService.ZONE).plusMinutes(occupied).toInstant();
        List<Instant> scheduled = bookings.findConfirmedScheduledTimes(p.getId(), windowStart, windowEnd);
        if (scheduled.isEmpty()) return Set.of();
        return availability.overlapping(slots, scheduled, p.getSessionMinutes(), p.getBufferMinutes());
    }
}
