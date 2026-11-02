package com.neuronest.backend.securitysuite;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.neuronest.backend.auth.User;
import com.neuronest.backend.wellness.WellnessContextProvider;
import com.neuronest.backend.wellness.WellnessMlStubs;
import com.neuronest.backend.wellness.WellnessTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Wellness check-ins through the production security chain, real Clerk token verification and a real
 * PostgreSQL. The ML service is a mock. A failure means a guest's check-in was stored, a user could read or
 * delete another user's check-ins, something was saved after the ML service failed, or answers leaked.
 */
class WellnessSecurityTest extends SecuritySuiteBase {

    private static final String CHECKIN = "/api/wellness/checkin";
    private static final String HISTORY = "/api/wellness/checkins";

    @Autowired
    WellnessContextProvider context;

    private User userA;
    private User userB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void users() {
        userA = user("well_a", "well.a@example.com");
        userB = user("well_b", "well.b@example.com");
        tokenA = SecurityTestTokens.clerk("well_a", "well.a@example.com");
        tokenB = SecurityTestTokens.clerk("well_b", "well.b@example.com");
        WellnessMlStubs.answers(wellnessMl, WellnessTestData.mlResponse("High", true, "apnea_like"));
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request).andReturn();
    }

    private static MockHttpServletRequestBuilder checkinRequest(String body) {
        return post(CHECKIN).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private int rows(UUID userId) {
        return jdbc.queryForObject("select count(*) from wellness_checkin where user_id = ?", Integer.class, userId);
    }

    private int allRows() {
        return jdbc.queryForObject("select count(*) from wellness_checkin", Integer.class);
    }

    /** A stored check-in of the user, created through the API, then dated. */
    private UUID stored(String token, Instant createdAt) throws Exception {
        MvcResult r = as(token, checkinRequest(WellnessTestData.requestJson()));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        UUID id = UUID.fromString(body(r).path("id").asString());
        jdbc.update("update wellness_checkin set created_at = ? where id = ?", Timestamp.from(createdAt), id);
        return id;
    }

    // ---- POST /api/wellness/checkin ----

    @Test
    void aGuestGetsTheResultAndNothingIsStored() throws Exception {
        MvcResult r = as(null, checkinRequest(WellnessTestData.requestJson()));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(r);
        assertThat(body.path("saved").asBoolean()).isFalse();
        assertThat(body.path("id").isNull()).isTrue();
        assertThat(body.path("result").path("mood").path("risk_band").asString()).isEqualTo("High");
        assertThat(body.path("result").path("sleep").path("pattern_code").asString()).isEqualTo("apnea_like");
        assertThat(body.path("result").path("disclaimer").asString()).contains("not a diagnosis");
        assertThat(JSON.convertValue(body.path("suggestedActions"), List.class))
                .containsExactly("ASSESSMENT_PHQ9", "FIND_PROFESSIONAL", "SEE_DOCTOR");
        assertThat(allRows()).isZero();
    }

    @Test
    void aSignedInUsersCheckinIsStoredAsTheirsAndReturnedWithItsId() throws Exception {
        MvcResult r = as(tokenA, checkinRequest(WellnessTestData.requestJson()));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(r);
        assertThat(body.path("saved").asBoolean()).isTrue();
        UUID id = UUID.fromString(body.path("id").asString());
        var row = jdbc.queryForMap("select user_id, segment, mood_band, mood_confidence, sleep_class, sleep_in_range,"
                + " inputs::text as inputs, result::text as result, model_versions::text as versions"
                + " from wellness_checkin where id = ?", id);
        assertThat(row.get("user_id")).isEqualTo(userA.getId());
        assertThat(row).containsEntry("segment", "working").containsEntry("mood_band", "High")
                .containsEntry("mood_confidence", "low").containsEntry("sleep_class", "apnea_like")
                .containsEntry("sleep_in_range", true);
        // Inputs are stored as sent to the ML service (allowed snake_case fields only).
        JsonNode inputs = JSON.readTree((String) row.get("inputs"));
        assertThat(inputs.path("daily_steps").asInt()).isEqualTo(WellnessTestData.SECRET_STEPS);
        assertThat(allKeys((String) row.get("inputs"))).doesNotContain("dailySteps", "family_history");
        // The stored result is exactly what the user was given.
        assertThat(JSON.readTree((String) row.get("result"))).isEqualTo(body.path("result"));
        assertThat(JSON.readTree((String) row.get("versions")).has("mood_working")).isTrue();
        assertThat(rows(userB.getId())).isZero();
    }

    @Test
    void whenTheMlServiceFailsTheAnswerIsA503AndNothingIsSaved() throws Exception {
        WellnessMlStubs.unavailable(wellnessMl);

        for (String token : new String[]{tokenA, null}) {
            MvcResult r = as(token, checkinRequest(WellnessTestData.requestJson()));
            assertThat(r.getResponse().getStatus()).isEqualTo(503);
            JsonNode body = body(r);
            assertThat(body.path("code").asString()).isEqualTo("WELLNESS_UNAVAILABLE");
            assertCleanErrorBody(r.getResponse().getContentAsString());
            assertThat(r.getResponse().getContentAsString()).doesNotContain("ml", "8001", "python", "Traceback");
        }
        assertThat(allRows()).isZero();
    }

    @Test
    void mlValidationErrorsBecomeA400WithFieldMessagesAndNothingIsSaved() throws Exception {
        WellnessMlStubs.rejects(wellnessMl, "pressure", "Input should be less than or equal to 5");

        MvcResult r = as(tokenA, checkinRequest(WellnessTestData.requestJson()));

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = body(r);
        assertThat(body.path("code").asString()).isEqualTo("WELLNESS_INVALID_INPUT");
        assertThat(body.path("fields").path(0).path("field").asString()).isEqualTo("pressure");
        assertThat(body.path("error").asString()).contains("pressure");
        assertThat(allRows()).isZero();
    }

    @Test
    void unknownFieldsWrongTypesAndFractionsAreRejectedBeforeTheMlService() throws Exception {
        String base = WellnessTestData.requestJson();
        String[][] cases = {
                {base.replace("\"segment\":\"working\"", "\"segment\":\"working\",\"gender\":\"female\""), "gender"},
                {base.replace("\"segment\":\"working\"",
                        "\"segment\":\"working\",\"Have you ever had suicidal thoughts ?\":\"No\""),
                        "Have you ever had suicidal thoughts ?"},
                {base.replace("\"pressure\":4", "\"pressure\":4.5"), "pressure"},
                {base.replace("\"age\":34", "\"age\":\"thirty\""), "age"},
                {base.replace("\"age\":34", "\"age\":34,\"city\":\"Pune\""), "city"}};
        for (String[] c : cases) {
            MvcResult r = as(tokenA, checkinRequest(c[0]));
            assertThat(r.getResponse().getStatus()).as(c[1]).isEqualTo(400);
            JsonNode body = body(r);
            assertThat(body.path("code").asString()).isEqualTo("WELLNESS_INVALID_INPUT");
            assertThat(body.path("fields").path(0).path("field").asString()).isEqualTo(c[1]);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("female", "Pune", "thirty");
        }
        assertThat(as(tokenA, checkinRequest("{not json")).getResponse().getStatus()).isEqualTo(400);
        assertThat(as(tokenA, post(CHECKIN)).getResponse().getStatus()).isEqualTo(400);
        WellnessMlStubs.neverCalled(wellnessMl);
        assertThat(allRows()).isZero();
    }

    // ---- History: own rows only ----

    @Test
    void historyNeedsASignedInUser() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get(HISTORY), get(HISTORY + "/latest"),
                delete(HISTORY + "/" + UUID.randomUUID()), delete(HISTORY))) {
            MvcResult r = as(null, request);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertCleanErrorBody(r.getResponse().getContentAsString());
        }
        // Admin tokens are not user tokens: the admin cannot read anyone's check-ins through this API.
        assertThat(as(SecurityTestTokens.admin(), get(HISTORY)).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void historyIsOwnRowsOnlyNewestFirstAndPaginated() throws Exception {
        Instant t = Instant.parse("2026-10-01T08:00:00Z");
        UUID a1 = stored(tokenA, t);
        UUID a2 = stored(tokenA, t.plusSeconds(3600));
        UUID a3 = stored(tokenA, t.plusSeconds(7200));
        UUID b1 = stored(tokenB, t.plusSeconds(9999));

        JsonNode page0 = body(as(tokenA, get(HISTORY).param("size", "2")));
        assertThat(ids(page0)).containsExactly(a3, a2);
        assertThat(page0.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.path("items").path(0).path("result").path("mood").path("risk_band").asString()).isEqualTo("High");
        assertThat(JSON.convertValue(page0.path("items").path(0).path("suggestedActions"), List.class))
                .containsExactly("ASSESSMENT_PHQ9", "FIND_PROFESSIONAL", "SEE_DOCTOR");
        assertThat(ids(body(as(tokenA, get(HISTORY).param("size", "2").param("page", "1"))))).containsExactly(a1);
        assertThat(ids(body(as(tokenB, get(HISTORY))))).containsExactly(b1);

        assertThat(UUID.fromString(body(as(tokenA, get(HISTORY + "/latest"))).path("id").asString())).isEqualTo(a3);
        assertThat(UUID.fromString(body(as(tokenB, get(HISTORY + "/latest"))).path("id").asString())).isEqualTo(b1);

        assertThat(as(tokenA, get(HISTORY).param("size", "51")).getResponse().getStatus()).isEqualTo(400);
        assertThat(as(tokenA, get(HISTORY).param("page", "-1")).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void latestIsEmptyWhenTheUserHasNone() throws Exception {
        stored(tokenB, Instant.now());
        MvcResult r = as(tokenA, get(HISTORY + "/latest"));
        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        assertThat(r.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void aUserCannotDeleteAnotherUsersCheckinAndItLooksLikeNotFound() throws Exception {
        UUID ofB = stored(tokenB, Instant.now());

        MvcResult foreign = as(tokenA, delete(HISTORY + "/" + ofB));
        MvcResult missing = as(tokenA, delete(HISTORY + "/" + UUID.randomUUID()));
        MvcResult malformed = as(tokenA, delete(HISTORY + "/not-a-uuid"));

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(malformed.getResponse().getStatus()).isEqualTo(404);
        assertThat(foreign.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(rows(userB.getId())).isEqualTo(1);

        assertThat(as(tokenB, delete(HISTORY + "/" + ofB)).getResponse().getStatus()).isEqualTo(204);
        assertThat(rows(userB.getId())).isZero();
    }

    @Test
    void deleteAllDeletesOnlyTheUsersOwnHistory() throws Exception {
        stored(tokenA, Instant.now());
        stored(tokenA, Instant.now());
        stored(tokenB, Instant.now());

        assertThat(as(tokenA, delete(HISTORY)).getResponse().getStatus()).isEqualTo(204);

        assertThat(rows(userA.getId())).isZero();
        assertThat(rows(userB.getId())).isEqualTo(1);
        assertThat(as(tokenA, delete(HISTORY)).getResponse().getStatus()).as("idempotent").isEqualTo(204);
    }

    @Test
    void theContextSummaryIsTheUsersOwnLatestOnly() throws Exception {
        Instant t = Instant.parse("2026-10-01T08:00:00Z");
        stored(tokenA, t);
        WellnessMlStubs.answers(wellnessMl, WellnessTestData.mlResponse("Low", false, null));
        stored(tokenA, t.plusSeconds(60));

        var summary = context.latestSummary(userA.getId()).orElseThrow();
        assertThat(summary.moodBand()).isEqualTo("Low");
        assertThat(summary.sleepConcern()).isFalse();
        assertThat(summary.sleepClass()).isNull();
        assertThat(summary.checkedInAt()).isEqualTo(t.plusSeconds(60));
        assertThat(context.latestSummary(userB.getId())).isEmpty();
        assertThat(context.latestSummary(null)).isEmpty();
    }

    @Test
    void seeDoctorComesFromTheMlFlagInTheAnswerAndInHistory() throws Exception {
        WellnessMlStubs.answers(wellnessMl, WellnessTestData.mlResponse("Low", true, "insomnia_like"));
        JsonNode answer = body(as(tokenA, checkinRequest(WellnessTestData.requestJson())));
        assertThat(JSON.convertValue(answer.path("suggestedActions"), List.class))
                .containsExactly("KEEP_TRACKING", "RELAXATION_SLEEP", "SEE_DOCTOR");

        // If ml-service ever stops flagging a class, SEE_DOCTOR follows it: nothing is hard-coded per class.
        WellnessMlStubs.answers(wellnessMl, WellnessTestData.mlResponse("Low", true, "insomnia_like", false));
        JsonNode unflagged = body(as(tokenB, checkinRequest(WellnessTestData.requestJson())));
        assertThat(JSON.convertValue(unflagged.path("suggestedActions"), List.class))
                .containsExactly("KEEP_TRACKING", "RELAXATION_SLEEP");

        // History recomputes from the stored result's flag.
        assertThat(JSON.convertValue(body(as(tokenA, get(HISTORY + "/latest"))).path("suggestedActions"), List.class))
                .containsExactly("KEEP_TRACKING", "RELAXATION_SLEEP", "SEE_DOCTOR");
        assertThat(JSON.convertValue(body(as(tokenB, get(HISTORY + "/latest"))).path("suggestedActions"), List.class))
                .containsExactly("KEEP_TRACKING", "RELAXATION_SLEEP");
    }

    // ---- Privacy ----

    @Test
    void answersAndResultsNeverReachTheLogs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            UUID id = stored(tokenA, Instant.now());
            as(tokenA, get(HISTORY));
            as(tokenA, delete(HISTORY + "/" + id));
            WellnessMlStubs.rejects(wellnessMl, "pressure", "Input should be less than or equal to 5");
            as(tokenA, checkinRequest(WellnessTestData.requestJson()));
        } finally {
            root.detachAppender(logs);
        }
        List<String> messages = new ArrayList<>();
        logs.list.forEach(e -> messages.add(e.getFormattedMessage()));
        String all = String.join("\n", messages);
        assertThat(all).doesNotContain(String.valueOf(WellnessTestData.SECRET_STEPS), "risk_band", "apnea",
                "overweight", "pattern_code", "well.a@example.com", userA.getId().toString());
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("id").asString())));
        return ids;
    }
}
