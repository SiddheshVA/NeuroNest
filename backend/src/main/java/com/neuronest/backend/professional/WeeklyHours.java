package com.neuronest.backend.professional;

import com.neuronest.backend.config.ApiValidationException;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The weekly hours a professional takes sessions, shared by the professional and admin endpoints.
// Stored in professionals.weekly_hours as {"MON": [{"start": "09:00", "end": "12:00"}], ...}: 24-hour
// Asia/Kolkata local times, on 15-minute marks, with "24:00" allowed as an end. Days without ranges
// are left out.
public final class WeeklyHours {

    public static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    public static final Set<Integer> SESSION_MINUTES = Set.of(30, 45, 60);
    public static final Set<Integer> BUFFER_MINUTES = Set.of(0, 5, 10, 15);
    public static final int MAX_RANGES_PER_DAY = 3;
    private static final int END_OF_DAY = 24 * 60;
    private static final Pattern TIME = Pattern.compile("^(\\d{2}):(\\d{2})$");
    private static final Map<String, String> DAY_NAMES = Map.of("MON", "Monday", "TUE", "Tuesday",
            "WED", "Wednesday", "THU", "Thursday", "FRI", "Friday", "SAT", "Saturday", "SUN", "Sunday");

    /** One range in minutes from midnight; endMinute may be 1440 (24:00). */
    public record Range(int startMinute, int endMinute) {}

    private WeeklyHours() {}

    /**
     * Reads the request's weeklyHours into the stored shape, checking only the JSON shape. Null means no
     * hours. Run {@link #validate} on the result (with the session length that will apply) before saving.
     */
    public static Map<String, List<Map<String, String>>> fromJson(JsonNode node) {
        Map<String, List<Map<String, String>>> hours = new LinkedHashMap<>();
        if (node == null || node.isNull()) return hours;
        if (!node.isObject()) {
            throw new ApiValidationException("weeklyHours must be an object such as "
                    + "{\"MON\": [{\"start\": \"09:00\", \"end\": \"12:00\"}]}.");
        }
        for (Map.Entry<String, JsonNode> day : node.properties()) {
            String key = day.getKey();
            if (!DAYS.contains(key)) {
                throw new ApiValidationException("weeklyHours can only use the day keys " + String.join(", ", DAYS)
                        + " (got '" + key + "').");
            }
            JsonNode ranges = day.getValue();
            List<Map<String, String>> list = new ArrayList<>();
            if (ranges != null && !ranges.isNull()) {
                if (!ranges.isArray()) {
                    throw new ApiValidationException("weeklyHours: " + DAY_NAMES.get(key)
                            + " must be a list of time ranges such as [{\"start\": \"09:00\", \"end\": \"12:00\"}].");
                }
                for (JsonNode range : ranges) {
                    if (range == null || !range.isObject() || !isText(range.get("start")) || !isText(range.get("end"))) {
                        throw new ApiValidationException("weeklyHours: each " + DAY_NAMES.get(key)
                                + " time range needs a start and an end written as text, such as "
                                + "{\"start\": \"09:00\", \"end\": \"12:00\"}.");
                    }
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("start", range.get("start").asString().trim());
                    entry.put("end", range.get("end").asString().trim());
                    list.add(entry);
                }
            }
            hours.put(key, list);
        }
        return hours;
    }

    /**
     * Checks every rule and returns the hours in a canonical form: days in MON to SUN order, ranges
     * sorted by start, and days without ranges left out. Ranges must be at least one session long
     * when sessionMinutes is known.
     */
    public static Map<String, List<Map<String, String>>> validate(Map<String, List<Map<String, String>>> hours,
                                                                  Integer sessionMinutes) {
        Map<String, List<Map<String, String>>> normalized = new LinkedHashMap<>();
        if (hours == null) return normalized;
        for (String key : hours.keySet()) {
            if (!DAYS.contains(key)) {
                throw new ApiValidationException("weeklyHours can only use the day keys " + String.join(", ", DAYS)
                        + " (got '" + key + "').");
            }
        }
        for (String key : DAYS) {
            List<Map<String, String>> entries = hours.get(key);
            if (entries == null || entries.isEmpty()) continue;
            String day = DAY_NAMES.get(key);
            if (entries.size() > MAX_RANGES_PER_DAY) {
                throw new ApiValidationException("weeklyHours: " + day + " has " + entries.size()
                        + " time ranges. Use at most " + MAX_RANGES_PER_DAY + " per day.");
            }
            List<Range> ranges = new ArrayList<>();
            for (Map<String, String> entry : entries) {
                String startText = entry == null ? null : entry.get("start");
                String endText = entry == null ? null : entry.get("end");
                int start = minutes(day, startText, false);
                int end = minutes(day, endText, true);
                if (start >= end) {
                    throw new ApiValidationException("weeklyHours: " + day + " " + startText + " to " + endText
                            + " must start before it ends.");
                }
                if (sessionMinutes != null && end - start < sessionMinutes) {
                    throw new ApiValidationException("weeklyHours: " + day + " " + startText + " to " + endText
                            + " is shorter than one " + sessionMinutes + "-minute session.");
                }
                ranges.add(new Range(start, end));
            }
            ranges.sort(Comparator.comparingInt(Range::startMinute));
            for (int i = 1; i < ranges.size(); i++) {
                Range previous = ranges.get(i - 1);
                Range current = ranges.get(i);
                if (current.startMinute() <= previous.endMinute()) {
                    throw new ApiValidationException("weeklyHours: " + day + " " + format(previous) + " and "
                            + format(current) + " overlap or touch. Leave a gap between ranges.");
                }
            }
            List<Map<String, String>> canonical = new ArrayList<>();
            for (Range range : ranges) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("start", time(range.startMinute()));
                entry.put("end", time(range.endMinute()));
                canonical.add(entry);
            }
            normalized.put(key, canonical);
        }
        return normalized;
    }

    /** Null is allowed (not chosen yet); otherwise 30, 45 or 60. */
    public static Integer sessionMinutes(Integer value) {
        if (value != null && !SESSION_MINUTES.contains(value)) {
            throw new ApiValidationException("sessionMinutes must be 30, 45 or 60.");
        }
        return value;
    }

    /** Null means no buffer (0); otherwise 0, 5, 10 or 15. */
    public static int bufferMinutes(Integer value) {
        if (value == null) return 0;
        if (!BUFFER_MINUTES.contains(value)) {
            throw new ApiValidationException("bufferMinutes must be 0, 5, 10 or 15.");
        }
        return value;
    }

    public static boolean hasAnyRange(Map<String, List<Map<String, String>>> hours) {
        return hours != null && hours.values().stream().anyMatch(list -> list != null && !list.isEmpty());
    }

    /** Stored hours as ranges per day. Entries that cannot be read are skipped (stored data is validated). */
    public static Map<DayOfWeek, List<Range>> ranges(Map<String, List<Map<String, String>>> hours) {
        Map<DayOfWeek, List<Range>> result = new LinkedHashMap<>();
        if (hours == null) return result;
        for (int i = 0; i < DAYS.size(); i++) {
            List<Map<String, String>> entries = hours.get(DAYS.get(i));
            if (entries == null) continue;
            List<Range> ranges = new ArrayList<>();
            for (Map<String, String> entry : entries) {
                Integer start = entry == null ? null : parse(entry.get("start"), false);
                Integer end = entry == null ? null : parse(entry.get("end"), true);
                if (start != null && end != null && start < end) ranges.add(new Range(start, end));
            }
            ranges.sort(Comparator.comparingInt(Range::startMinute));
            if (!ranges.isEmpty()) result.put(DayOfWeek.of(i + 1), ranges);
        }
        return result;
    }

    /** A deep copy, so responses never share the entity's lists. */
    public static Map<String, List<Map<String, String>>> copy(Map<String, List<Map<String, String>>> hours) {
        Map<String, List<Map<String, String>>> copy = new LinkedHashMap<>();
        if (hours == null) return copy;
        hours.forEach((day, list) -> {
            List<Map<String, String>> ranges = new ArrayList<>();
            if (list != null) list.forEach(entry -> ranges.add(entry == null ? Map.of() : new LinkedHashMap<>(entry)));
            copy.put(day, ranges);
        });
        return copy;
    }

    // ---- helpers ----

    private static int minutes(String day, String text, boolean isEnd) {
        String which = isEnd ? "an end" : "a start";
        if (text == null || !TIME.matcher(text).matches()) {
            throw new ApiValidationException("weeklyHours: " + day + " has " + which + " time '" + text
                    + "' that is not in HH:mm format, such as 09:00.");
        }
        Matcher m = TIME.matcher(text);
        m.matches();
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        if (hour == 24 && minute == 0) {
            if (!isEnd) {
                throw new ApiValidationException("weeklyHours: " + day + " starts at 24:00. Only an end time can be 24:00.");
            }
            return END_OF_DAY;
        }
        if (hour > 23 || minute > 59) {
            throw new ApiValidationException("weeklyHours: " + day + " has " + which + " time '" + text
                    + "' outside 00:00 to 24:00.");
        }
        if (minute % 15 != 0) {
            throw new ApiValidationException("weeklyHours: " + day + " " + text + " is not on a 15-minute mark. "
                    + "Use minutes 00, 15, 30 or 45.");
        }
        return hour * 60 + minute;
    }

    // Lenient read for stored values; null when unreadable.
    private static Integer parse(String text, boolean isEnd) {
        if (text == null) return null;
        Matcher m = TIME.matcher(text);
        if (!m.matches()) return null;
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        if (hour == 24 && minute == 0) return isEnd ? END_OF_DAY : null;
        if (hour > 23 || minute > 59) return null;
        return hour * 60 + minute;
    }

    private static String time(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static String format(Range range) {
        return time(range.startMinute()) + " to " + time(range.endMinute());
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isString();
    }
}
