package com.neuronest.backend.professional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Turns a professional's weekly hours into bookable slot start times. Pure apart from its two settings:
// everything else it needs, including "now" and the slots already taken, is passed in. All times are
// Asia/Kolkata, never the server's default zone.
@Service
public class AvailabilityService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** No slots are offered on dates further ahead than this. */
    public static final int MAX_DAYS_AHEAD = 60;
    public static final long DEFAULT_MIN_LEAD_MINUTES = 0;
    public static final long DEFAULT_MIN_HOLD_SECONDS = 60;

    // How far ahead a slot must start to be offered and booked: booking.min-lead-minutes (0: any slot that
    // has not started), and never less than booking.min-hold-seconds (60), so a paid slot cannot already be
    // gone by the time the payment is made.
    private final Duration minLead;
    private final Duration minHold;

    /** The defaults: no minimum lead time, at least a minute to pay. */
    public AvailabilityService() {
        this(DEFAULT_MIN_LEAD_MINUTES, DEFAULT_MIN_HOLD_SECONDS);
    }

    @Autowired
    public AvailabilityService(@Value("${booking.min-lead-minutes:" + DEFAULT_MIN_LEAD_MINUTES + "}") long minLeadMinutes,
                               @Value("${booking.min-hold-seconds:" + DEFAULT_MIN_HOLD_SECONDS + "}") long minHoldSeconds) {
        this.minLead = Duration.ofMinutes(Math.max(0, minLeadMinutes));
        this.minHold = Duration.ofSeconds(Math.max(0, minHoldSeconds));
    }

    /**
     * THE bookable-time rule, shared by the availability endpoint and the booking check so they never
     * disagree: a slot can be booked only if it starts at or after this.
     */
    public Instant earliestStart(Instant now) {
        return now.plus(minLead.compareTo(minHold) >= 0 ? minLead : minHold);
    }

    /**
     * Free slot start times per date, for dates from {@code from} to {@code to} (both included). For each
     * range a slot starts at the range start and every sessionMinutes + bufferMinutes after it, as long as
     * the whole session ends by the range end. Slots starting before {@link #earliestStart}, dates more than
     * 60 days after today (in India), and slots in {@code taken} are left out. Only dates with at least one
     * slot are returned, in date order.
     */
    public Map<LocalDate, List<ZonedDateTime>> freeSlots(Map<String, List<Map<String, String>>> weeklyHours,
                                                         Integer sessionMinutes, int bufferMinutes,
                                                         LocalDate from, LocalDate to, ZonedDateTime now,
                                                         Set<ZonedDateTime> taken) {
        return freeSlots(weeklyHours, sessionMinutes, bufferMinutes, from, to, now, taken, List.of());
    }

    /** As above, and no slot at all on a date inside any of {@code daysOff}. */
    public Map<LocalDate, List<ZonedDateTime>> freeSlots(Map<String, List<Map<String, String>>> weeklyHours,
                                                         Integer sessionMinutes, int bufferMinutes,
                                                         LocalDate from, LocalDate to, ZonedDateTime now,
                                                         Set<ZonedDateTime> taken, Collection<DateRange> daysOff) {
        Map<LocalDate, List<ZonedDateTime>> result = new TreeMap<>();
        if (from == null || to == null || now == null) return result;
        ZonedDateTime nowInIndia = now.withZoneSameInstant(ZONE);
        Instant earliest = earliestStart(nowInIndia.toInstant());
        LocalDate lastDate = nowInIndia.toLocalDate().plusDays(MAX_DAYS_AHEAD);
        LocalDate end = to.isAfter(lastDate) ? lastDate : to;
        // Compared as instants, so a taken slot given in any zone matches.
        Set<Instant> takenInstants = taken == null ? Set.of()
                : taken.stream().map(ZonedDateTime::toInstant).collect(Collectors.toSet());
        gridSlots(weeklyHours, sessionMinutes, bufferMinutes, from, end, daysOff).forEach((date, grid) -> {
            List<ZonedDateTime> slots = grid.stream()
                    .filter(slot -> !slot.toInstant().isBefore(earliest) && !takenInstants.contains(slot.toInstant()))
                    .toList();
            if (!slots.isEmpty()) result.put(date, slots);
        });
        return result;
    }

    /**
     * Every slot start on the grid per date, from and to included: a slot at each range start and every
     * sessionMinutes + bufferMinutes after it, as long as the session ends by the range end. Days off are
     * left out; nothing else is (no notice period, no limit ahead, nothing taken). Only dates with at least
     * one slot are returned, in date order.
     */
    public Map<LocalDate, List<ZonedDateTime>> gridSlots(Map<String, List<Map<String, String>>> weeklyHours,
                                                         Integer sessionMinutes, int bufferMinutes,
                                                         LocalDate from, LocalDate to, Collection<DateRange> daysOff) {
        Map<LocalDate, List<ZonedDateTime>> result = new TreeMap<>();
        if (sessionMinutes == null || sessionMinutes <= 0 || from == null || to == null || to.isBefore(from)) {
            return result;
        }
        int step = sessionMinutes + Math.max(0, bufferMinutes);
        var rangesByDay = WeeklyHours.ranges(weeklyHours);

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<WeeklyHours.Range> ranges = rangesByDay.get(date.getDayOfWeek());
            if (ranges == null || isDayOff(date, daysOff)) continue;
            List<ZonedDateTime> slots = new ArrayList<>();
            for (WeeklyHours.Range range : ranges) {
                for (int start = range.startMinute(); start + sessionMinutes <= range.endMinute(); start += step) {
                    slots.add(ZonedDateTime.of(date, LocalTime.of(start / 60, start % 60), ZONE));
                }
            }
            if (!slots.isEmpty()) result.put(date, slots);
        }
        return result;
    }

    public static boolean isDayOff(LocalDate date, Collection<DateRange> daysOff) {
        if (daysOff == null) return false;
        for (DateRange range : daysOff) {
            if (range.contains(date)) return true;
        }
        return false;
    }

    /**
     * The slots that overlap any of the scheduled booking start times, to pass back into freeSlots as the
     * taken set. A session occupies sessionMinutes plus bufferMinutes from its start; a slot and a booking
     * overlap when those spans intersect.
     */
    public Set<ZonedDateTime> overlapping(Map<LocalDate, List<ZonedDateTime>> slots, Collection<Instant> scheduledStarts,
                                          int sessionMinutes, int bufferMinutes) {
        Set<ZonedDateTime> taken = new HashSet<>();
        if (slots == null || scheduledStarts == null || scheduledStarts.isEmpty()) return taken;
        long occupiedSeconds = (sessionMinutes + Math.max(0, bufferMinutes)) * 60L;
        for (List<ZonedDateTime> day : slots.values()) {
            for (ZonedDateTime slot : day) {
                Instant start = slot.toInstant();
                Instant end = start.plusSeconds(occupiedSeconds);
                for (Instant booked : scheduledStarts) {
                    if (start.isBefore(booked.plusSeconds(occupiedSeconds)) && booked.isBefore(end)) {
                        taken.add(slot);
                        break;
                    }
                }
            }
        }
        return taken;
    }
}
