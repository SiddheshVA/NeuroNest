package com.neuronest.backend.booking;

import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

// A booking's meeting as both sides see it: from its start to the end of its last session, never the
// break blocked after it (blocked_until). Shared by the user's and the professional's views and every
// notification (emails now, WhatsApp and SMS later), so they all word the time the same way.
public final class MeetingTimes {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH).withZone(AvailabilityService.ZONE);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(AvailabilityService.ZONE);

    private MeetingTimes() {}

    /**
     * When the meeting ends: ends_at, which holds no break. A booking from before V16 has blocked_until =
     * ends_at, and its ends_at includes the professional's old buffer, so its sessions end that much earlier,
     * as they always showed. Null when no end is stored, or when an older request was moved to a proposed
     * time (the stored end belongs to the first start).
     */
    public static Instant end(Booking b, Professional p) {
        if (b.getEndsAt() == null || !Objects.equals(b.getPreferredDatetime(), b.scheduledTime())) return null;
        boolean beforeGrid = b.getBlockedUntil() == null || !b.getBlockedUntil().isAfter(b.getEndsAt());
        if (!beforeGrid) return b.getEndsAt();
        return b.getEndsAt().minusSeconds(60L * (p == null ? 0 : Math.max(0, p.getBufferMinutes())));
    }

    /** "Thu, 29 Oct 2026, 7:45 PM to 9:45 PM IST (2 sessions, 2 hours)", or only the start without an end. */
    public static String describe(Booking b, Professional p) {
        Instant start = b.scheduledTime();
        if (start == null) return "a time to be agreed";
        return DAY.format(start) + ", " + timeRange(b, p);
    }

    /** The same without the date, for the day itself: "7:45 PM to 9:45 PM IST (2 sessions, 2 hours)". */
    public static String timeRange(Booking b, Professional p) {
        Instant start = b.scheduledTime();
        if (start == null) return "a time to be agreed";
        Instant end = end(b, p);
        if (end == null || !end.isAfter(start)) return TIME.format(start) + " IST";
        int sessions = Math.max(1, b.getSessionCount());
        return TIME.format(start) + " to " + TIME.format(end) + " IST ("
                + sessions + (sessions == 1 ? " session, " : " sessions, ") + length(Duration.between(start, end)) + ")";
    }

    /** "2 hours", "1 hour 30 minutes", "45 minutes". */
    static String length(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        String h = hours == 0 ? "" : hours + (hours == 1 ? " hour" : " hours");
        String m = minutes == 0 ? "" : minutes + (minutes == 1 ? " minute" : " minutes");
        return h.isEmpty() ? m : m.isEmpty() ? h : h + " " + m;
    }
}
