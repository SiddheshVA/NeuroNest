package com.neuronest.backend.professional;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityServiceTest {

    private static final AvailabilityService SERVICE = new AvailabilityService();
    // Monday 12 Oct 2026, 10:00 in India. Slots start from Tuesday 13 Oct 10:00 (24 hours later).
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, AvailabilityService.ZONE);
    private static final LocalDate WED = LocalDate.of(2026, 10, 14);

    private static Map<String, List<Map<String, String>>> hours(String day, String... startEndPairs) {
        List<Map<String, String>> ranges = new java.util.ArrayList<>();
        for (int i = 0; i < startEndPairs.length; i += 2) {
            ranges.add(Map.of("start", startEndPairs[i], "end", startEndPairs[i + 1]));
        }
        return Map.of(day, ranges);
    }

    private static List<String> times(Map<LocalDate, List<ZonedDateTime>> slots, LocalDate date) {
        return slots.getOrDefault(date, List.of()).stream()
                .map(z -> z.toLocalTime().toString()).toList();
    }

    private Map<LocalDate, List<ZonedDateTime>> slots(Map<String, List<Map<String, String>>> hours, int session,
                                                      int buffer, LocalDate from, LocalDate to) {
        return SERVICE.freeSlots(hours, session, buffer, from, to, NOW, Set.of());
    }

    @Test
    void normalRangeStepsBySessionLength() {
        var result = slots(hours("WED", "09:00", "12:00"), 60, 0, WED, WED);
        assertThat(times(result, WED)).containsExactly("09:00", "10:00", "11:00");
        assertThat(result.get(WED).get(0).getZone()).isEqualTo(AvailabilityService.ZONE);
        assertThat(result.get(WED).get(0).getOffset().getId()).isEqualTo("+05:30");
    }

    @Test
    void aRangeThatDoesNotFitAWholeNumberOfSessionsDropsThePartialOne() {
        assertThat(times(slots(hours("WED", "09:00", "11:30"), 60, 0, WED, WED), WED))
                .containsExactly("09:00", "10:00");
        assertThat(times(slots(hours("WED", "09:00", "10:30"), 45, 0, WED, WED), WED))
                .containsExactly("09:00", "09:45");
    }

    @Test
    void bufferIsAddedBetweenSessions() {
        // 30-minute sessions with a 15-minute gap: 09:00, 09:45, 10:30 (11:15 would end after 11:30).
        assertThat(times(slots(hours("WED", "09:00", "11:30"), 30, 15, WED, WED), WED))
                .containsExactly("09:00", "09:45", "10:30");
    }

    @Test
    void twoRangesInOneDay() {
        assertThat(times(slots(hours("WED", "09:00", "10:00", "18:00", "19:30"), 45, 0, WED, WED), WED))
                .containsExactly("09:00", "18:00", "18:45");
    }

    @Test
    void anEmptyDayHasNoEntry() {
        var hours = Map.of("WED", List.<Map<String, String>>of(), "THU", List.of(Map.of("start", "09:00", "end", "10:00")));
        var result = slots(hours, 60, 0, WED, WED.plusDays(1));
        assertThat(result).containsOnlyKeys(WED.plusDays(1));
        // No session length means nothing can be booked.
        assertThat(SERVICE.freeSlots(hours("WED", "09:00", "12:00"), null, 0, WED, WED, NOW, Set.of())).isEmpty();
    }

    @Test
    void slotsWithinTwentyFourHoursAndDatesBeyondSixtyDaysAreLeftOut() {
        LocalDate tuesday = LocalDate.of(2026, 10, 13);
        // Now + 24 hours is Tuesday 10:00: 09:00 is too soon, 10:00 is exactly allowed.
        assertThat(times(slots(hours("TUE", "09:00", "12:00"), 60, 0, tuesday, tuesday), tuesday))
                .containsExactly("10:00", "11:00");
        // Today (Monday) is always too soon.
        assertThat(slots(hours("MON", "09:00", "23:00"), 60, 0, NOW.toLocalDate(), NOW.toLocalDate())).isEmpty();

        // 12 Oct + 60 days = 11 Dec (a Friday) is the last date offered; Friday 18 Dec is not.
        var fridays = slots(hours("FRI", "09:00", "10:00"), 60, 0, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
        assertThat(fridays).containsOnlyKeys(LocalDate.of(2026, 12, 4), LocalDate.of(2026, 12, 11));
    }

    @Test
    void aRangeSpanningSundayToMondayUsesEachDaysOwnHours() {
        LocalDate sunday = LocalDate.of(2026, 10, 18);
        LocalDate monday = LocalDate.of(2026, 10, 19);
        var hours = Map.of(
                "SUN", List.of(Map.of("start", "22:00", "end", "24:00")),
                "MON", List.of(Map.of("start", "00:00", "end", "01:00")));
        var result = slots(hours, 60, 0, sunday, monday);
        assertThat(result.keySet()).containsExactly(sunday, monday);
        assertThat(times(result, sunday)).containsExactly("22:00", "23:00");
        assertThat(times(result, monday)).containsExactly("00:00");
        assertThat(result.get(monday).get(0).toOffsetDateTime().toString()).isEqualTo("2026-10-19T00:00+05:30");
    }

    @Test
    void takenSlotsAreExcluded() {
        ZonedDateTime taken = ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, AvailabilityService.ZONE);
        // Given in UTC, it still matches the same instant.
        var result = SERVICE.freeSlots(hours("WED", "09:00", "12:00"), 60, 0, WED, WED, NOW,
                Set.of(taken.withZoneSameInstant(java.time.ZoneOffset.UTC)));
        assertThat(times(result, WED)).containsExactly("09:00", "11:00");
    }
}
