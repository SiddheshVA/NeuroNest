package com.neuronest.backend.professional;

import com.neuronest.backend.config.ApiValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyHoursTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private Map<String, List<Map<String, String>>> check(String body, Integer session) {
        return WeeklyHours.validate(WeeklyHours.fromJson(json.readTree(body)), session);
    }

    @Test
    void validHoursAreNormalized() {
        var result = check("{\"FRI\":[{\"start\":\"20:00\",\"end\":\"24:00\"},{\"start\":\"09:00\",\"end\":\"10:00\"}],"
                + "\"MON\":[{\"start\":\"00:00\",\"end\":\"00:30\"}],\"TUE\":[],\"WED\":null}", 30);
        assertThat(result.keySet()).containsExactly("MON", "FRI");
        assertThat(result.get("FRI")).containsExactly(Map.of("start", "09:00", "end", "10:00"),
                Map.of("start", "20:00", "end", "24:00"));
        assertThat(check("{}", null)).isEmpty();
        assertThat(WeeklyHours.validate(null, 60)).isEmpty();
    }

    @Test
    void everyRuleHasAReadableMessageNamingTheDay() {
        assertThatThrownBy(() -> check("{\"MONDAY\":[]}", 60)).isInstanceOf(ApiValidationException.class)
                .hasMessage("weeklyHours can only use the day keys MON, TUE, WED, THU, FRI, SAT, SUN (got 'MONDAY').");
        assertThatThrownBy(() -> check("[]", 60)).hasMessageStartingWith("weeklyHours must be an object");
        assertThatThrownBy(() -> check("{\"MON\":\"09:00-10:00\"}", 60))
                .hasMessageStartingWith("weeklyHours: Monday must be a list of time ranges");
        assertThatThrownBy(() -> check("{\"MON\":[{\"start\":900,\"end\":\"10:00\"}]}", 60))
                .hasMessageStartingWith("weeklyHours: each Monday time range needs a start and an end");
        assertThatThrownBy(() -> check("{\"TUE\":[{\"start\":\"9:00\",\"end\":\"10:00\"}]}", 60))
                .hasMessage("weeklyHours: Tuesday has a start time '9:00' that is not in HH:mm format, such as 09:00.");
        assertThatThrownBy(() -> check("{\"WED\":[{\"start\":\"09:10\",\"end\":\"10:15\"}]}", 60))
                .hasMessage("weeklyHours: Wednesday 09:10 is not on a 15-minute mark. Use minutes 00, 15, 30 or 45.");
        assertThatThrownBy(() -> check("{\"THU\":[{\"start\":\"11:00\",\"end\":\"10:00\"}]}", 60))
                .hasMessage("weeklyHours: Thursday 11:00 to 10:00 must start before it ends.");
        assertThatThrownBy(() -> check("{\"FRI\":[{\"start\":\"09:00\",\"end\":\"09:45\"}]}", 60))
                .hasMessage("weeklyHours: Friday 09:00 to 09:45 is shorter than one 60-minute session.");
        assertThatThrownBy(() -> check("{\"SAT\":[{\"start\":\"09:00\",\"end\":\"11:00\"},{\"start\":\"11:00\",\"end\":\"12:00\"}]}", 60))
                .hasMessage("weeklyHours: Saturday 09:00 to 11:00 and 11:00 to 12:00 overlap or touch. Leave a gap between ranges.");
        assertThatThrownBy(() -> check("{\"SAT\":[{\"start\":\"09:00\",\"end\":\"12:00\"},{\"start\":\"10:00\",\"end\":\"13:00\"}]}", 60))
                .hasMessageContaining("overlap or touch");
        assertThatThrownBy(() -> check("{\"SUN\":[{\"start\":\"24:00\",\"end\":\"24:00\"}]}", 30))
                .hasMessage("weeklyHours: Sunday starts at 24:00. Only an end time can be 24:00.");
        assertThatThrownBy(() -> check("{\"SUN\":[{\"start\":\"23:00\",\"end\":\"24:15\"}]}", 30))
                .hasMessage("weeklyHours: Sunday has an end time '24:15' outside 00:00 to 24:00.");
        assertThatThrownBy(() -> check("{\"MON\":[{\"start\":\"06:00\",\"end\":\"07:00\"},{\"start\":\"08:00\",\"end\":\"09:00\"},"
                + "{\"start\":\"10:00\",\"end\":\"11:00\"},{\"start\":\"12:00\",\"end\":\"13:00\"}]}", 60))
                .hasMessage("weeklyHours: Monday has 4 time ranges. Use at most 3 per day.");
    }

    @Test
    void sessionAndBufferValues() {
        assertThat(WeeklyHours.sessionMinutes(null)).isNull();
        assertThat(WeeklyHours.sessionMinutes(45)).isEqualTo(45);
        assertThatThrownBy(() -> WeeklyHours.sessionMinutes(90)).hasMessage("sessionMinutes must be 30, 45 or 60.");
        assertThat(WeeklyHours.bufferMinutes(null)).isZero();
        assertThat(WeeklyHours.bufferMinutes(15)).isEqualTo(15);
        assertThatThrownBy(() -> WeeklyHours.bufferMinutes(20)).hasMessage("bufferMinutes must be 0, 5, 10 or 15.");
    }
}
