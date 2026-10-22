package com.neuronest.backend.professional;

import org.springframework.stereotype.Service;

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

// Turns a professional's weekly hours into bookable slot start times. Pure: everything it needs,
// including "now" and the slots already taken, is passed in. All times are Asia/Kolkata, never the
// server's default zone.
@Service
public class AvailabilityService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** A slot must start at least this long after now. */
    public static final int MIN_NOTICE_HOURS = 24;
    /** No slots are offered on dates further ahead than this. */
    public static final int MAX_DAYS_AHEAD = 60;

    /**
     * Free slot start times per date, for dates from {@code from} to {@code to} (both included). For each
     * range a slot starts at the range start and every sessionMinutes + bufferMinutes after it, as long as
     * the whole session ends by the range end. Slots starting before now + 24 hours, dates more than 60
     * days after today (in India), and slots in {@code taken} are left out. Only dates with at least one
     * slot are returned, in date order.
     */
    public Map<LocalDate, List<ZonedDateTime>> freeSlots(Map<String, List<Map<String, String>>> weeklyHours,
                                                         Integer sessionMinutes, int bufferMinutes,
                                                         LocalDate from, LocalDate to, ZonedDateTime now,
                                                         Set<ZonedDateTime> taken) {
        Map<LocalDate, List<ZonedDateTime>> result = new TreeMap<>();
        if (sessionMinutes == null || sessionMinutes <= 0 || from == null || to == null || now == null
                || to.isBefore(from)) {
            return result;
        }
        ZonedDateTime nowInIndia = now.withZoneSameInstant(ZONE);
        ZonedDateTime earliest = nowInIndia.plusHours(MIN_NOTICE_HOURS);
        LocalDate lastDate = nowInIndia.toLocalDate().plusDays(MAX_DAYS_AHEAD);
        LocalDate end = to.isAfter(lastDate) ? lastDate : to;
        // Compared as instants, so a taken slot given in any zone matches.
        Set<Instant> takenInstants = taken == null ? Set.of()
                : taken.stream().map(ZonedDateTime::toInstant).collect(Collectors.toSet());
        int step = sessionMinutes + Math.max(0, bufferMinutes);
        var rangesByDay = WeeklyHours.ranges(weeklyHours);

        for (LocalDate date = from; !date.isAfter(end); date = date.plusDays(1)) {
            List<WeeklyHours.Range> ranges = rangesByDay.get(date.getDayOfWeek());
            if (ranges == null) continue;
            List<ZonedDateTime> slots = new ArrayList<>();
            for (WeeklyHours.Range range : ranges) {
                for (int start = range.startMinute(); start + sessionMinutes <= range.endMinute(); start += step) {
                    ZonedDateTime slot = ZonedDateTime.of(date, LocalTime.of(start / 60, start % 60), ZONE);
                    if (slot.isBefore(earliest) || takenInstants.contains(slot.toInstant())) continue;
                    slots.add(slot);
                }
            }
            if (!slots.isEmpty()) result.put(date, slots);
        }
        return result;
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
