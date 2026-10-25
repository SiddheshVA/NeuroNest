package com.neuronest.backend.securitysuite;

import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * "Block slots" over HTTP with real repositories: block and unblock, a blocked slot hidden from clients and
 * refused at booking, and a held booking's slot shown as Booked and never blockable. The professional works
 * every day 09:00 to 17:00, so the grid slots are 09:00, 10:15, 11:30, 12:45, 14:00 and 15:15.
 */
class BlockedSlotsSecurityTest extends SecuritySuiteBase {

    private Professional asha;
    private String proToken;
    private String clientToken;
    private LocalDate day;

    @BeforeEach
    void data() {
        asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
        user("user_client", "client@example.com");
        proToken = SecurityTestTokens.clerk("pro_asha", "asha@example.com");
        clientToken = SecurityTestTokens.clerk("user_client", "client@example.com");
        // Three days ahead: inside the 7-day block window and past the 24-hour booking notice.
        day = LocalDate.now(AvailabilityService.ZONE).plusDays(3);
    }

    private String at(String time) {
        return day + "T" + time + ":00+05:30";
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token))).andReturn();
    }

    private MvcResult block(String time) throws Exception {
        return as(proToken, post("/api/pro/blocked-slots").contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\":\"" + at(time) + "\"}"));
    }

    private MvcResult book(String time, int sessions) throws Exception {
        String body = """
                {"professionalId":"%s","preferredMode":"ONLINE","preferredMethod":"VIDEO","contactName":"Ravi Kumar",
                 "contactPhone":"9876543210","slotStart":"%s","sessionCount":%d,"consentGiven":true}"""
                .formatted(asha.getId(), at(time), sessions);
        return as(clientToken, post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // The slot at `time` on `day` in a week answer.
    private JsonNode slot(JsonNode week, String time) {
        for (JsonNode d : week.get("days")) {
            if (!d.get("date").asString().equals(day.toString())) continue;
            for (JsonNode s : d.get("slots")) {
                if (s.get("start").asString().startsWith(day + "T" + time)) return s;
            }
        }
        throw new AssertionError("no slot at " + time);
    }

    private List<String> publicSlots() throws Exception {
        JsonNode availability = json(mvc.perform(get("/api/professionals/" + asha.getId() + "/availability?from=" + day
                + "&to=" + day)).andReturn());
        List<String> slots = new ArrayList<>();
        availability.get("days").forEach(d -> d.get("slots").forEach(s -> slots.add(s.asString())));
        return slots;
    }

    @Test
    void aProfessionalBlocksAndUnblocksAFreeSlot() throws Exception {
        JsonNode week = json(as(proToken, get("/api/pro/blocked-slots")));
        assertThat(week.get("days").size()).isEqualTo(7);
        assertThat(week.get("sessionMinutes").asInt()).isEqualTo(60);
        assertThat(week.get("breakMinutes").asInt()).isEqualTo(15);
        assertThat(slot(week, "10:15").get("status").asString()).isEqualTo("FREE");
        // The meeting end is shown, never the break.
        assertThat(slot(week, "10:15").get("end").asString()).startsWith(day + "T11:15");

        MvcResult blocked = block("10:15");
        assertThat(blocked.getResponse().getStatus()).as(blocked.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode after = slot(json(blocked), "10:15");
        assertThat(after.get("status").asString()).isEqualTo("BLOCKED");
        String blockId = after.get("blockId").asString();
        // Blocking it again changes nothing.
        assertThat(slot(json(block("10:15")), "10:15").get("blockId").asString()).isEqualTo(blockId);
        assertThat(jdbc.queryForObject("select count(*) from professional_blocked_slots", Long.class)).isEqualTo(1);

        // Another professional cannot unblock it: the same 404 as a missing one.
        Professional other = listed("Dr. Other");
        account(other, "pro_other");
        assertThat(as(SecurityTestTokens.clerk("pro_other", "other@example.com"),
                delete("/api/pro/blocked-slots/" + blockId)).getResponse().getStatus()).isEqualTo(404);

        MvcResult unblocked = as(proToken, delete("/api/pro/blocked-slots/" + blockId));
        assertThat(unblocked.getResponse().getStatus()).isEqualTo(200);
        assertThat(slot(json(unblocked), "10:15").get("status").asString()).isEqualTo("FREE");
        assertThat(as(proToken, delete("/api/pro/blocked-slots/" + blockId)).getResponse().getStatus()).isEqualTo(404);
        assertThat(publicSlots()).contains(at("10:15"));
    }

    @Test
    void aBlockedSlotIsHiddenFromClientsAndCannotBeBooked() throws Exception {
        block("10:15");

        assertThat(publicSlots()).contains(at("09:00"), at("11:30")).doesNotContain(at("10:15"));
        MvcResult direct = book("10:15", 1);
        assertThat(direct.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(direct).get("error").asString()).isEqualTo("This time is no longer available. Please choose another time.");
        // Two sessions from 09:00 would run into it.
        assertThat(book("09:00", 2).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from booking_requests", Long.class)).isZero();
        // The slots around it are still bookable.
        assertThat(book("11:30", 1).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void aSlotWithAHeldOrConfirmedBookingShowsAsBookedAndCannotBeBlocked() throws Exception {
        asha.setConsultationFee(800);
        professionals.saveAndFlush(asha);
        // A client's hold (waiting for payment) on two sessions from 10:15.
        MvcResult held = book("10:15", 2);
        assertThat(held.getResponse().getStatus()).as(held.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(json(held).get("status").asString()).isEqualTo("AWAITING_PAYMENT");

        JsonNode week = json(as(proToken, get("/api/pro/blocked-slots")));
        assertThat(slot(week, "10:15").get("status").asString()).isEqualTo("BOOKED");
        assertThat(slot(week, "11:30").get("status").asString()).isEqualTo("BOOKED");
        assertThat(slot(week, "12:45").get("status").asString()).isEqualTo("FREE");

        for (String time : List.of("10:15", "11:30")) {
            MvcResult refused = block(time);
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(json(refused).get("error").asString()).contains("booked by a client", "contact NeuroNest support");
        }
        assertThat(jdbc.queryForObject("select count(*) from professional_blocked_slots", Long.class)).isZero();

        // Confirmed (paid): still Booked.
        jdbc.update("update booking_requests set status = 'CONFIRMED'");
        assertThat(block("10:15").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void onlyTheProfessionalsOwnUpcomingGridSlotsInTheNextSevenDaysCanBeBlocked() throws Exception {
        assertThat(block("10:00").getResponse().getStatus()).as("not on the grid").isEqualTo(400);
        String eightDaysAhead = LocalDate.now(AvailabilityService.ZONE).plusDays(8) + "T09:00:00+05:30";
        assertThat(as(proToken, post("/api/pro/blocked-slots").contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\":\"" + eightDaysAhead + "\"}")).getResponse().getStatus()).isEqualTo(400);
        String yesterday = LocalDate.now(AvailabilityService.ZONE).minusDays(1) + "T09:00:00+05:30";
        assertThat(as(proToken, post("/api/pro/blocked-slots").contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\":\"" + yesterday + "\"}")).getResponse().getStatus()).isEqualTo(400);
        assertThat(as(proToken, post("/api/pro/blocked-slots").contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\":\"tomorrow\"}")).getResponse().getStatus()).isEqualTo(400);
        // A client token is not a professional.
        assertThat(as(clientToken, get("/api/pro/blocked-slots")).getResponse().getStatus()).isIn(401, 403);
    }
}
