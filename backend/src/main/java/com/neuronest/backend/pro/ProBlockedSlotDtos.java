package com.neuronest.backend.pro;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The professional's "Block slots" screen: their grid slots for the next 7 days and what each one is. */
public final class ProBlockedSlotDtos {

    private ProBlockedSlotDtos() {}

    /** POST /api/pro/blocked-slots. start: one grid slot, ISO-8601 with an offset (2026-10-29T19:45:00+05:30). */
    public record BlockSlotRequest(String start) {}

    /**
     * One grid slot. status: FREE (can be blocked), BLOCKED (blockId unblocks it) or BOOKED (a client's held
     * or confirmed booking covers it; it cannot be blocked). end is when a one-session meeting there would
     * end, never the break after it. Times are shown in India, for example 2026-10-29T19:45+05:30.
     */
    public record SlotView(OffsetDateTime start, OffsetDateTime end, String status, UUID blockId) {}

    /** One date; slots is empty when the professional has no hours that day (or it is a day off). */
    public record DayView(LocalDate date, List<SlotView> slots) {}

    public record BlockWeek(int sessionMinutes, int breakMinutes, List<DayView> days) {}
}
