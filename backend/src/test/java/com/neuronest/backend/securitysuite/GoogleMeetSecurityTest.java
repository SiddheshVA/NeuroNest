package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.meeting.GoogleMeetService;
import com.neuronest.backend.professional.Professional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Google Meet over HTTP (GoogleMeetService is a mock: Google is never called). The admin-only test endpoint
 * opens for an admin token and nothing else; a professional adds a video link by hand to a confirmed video
 * session that has none.
 */
class GoogleMeetSecurityTest extends SecuritySuiteBase {

    private Professional asha;
    private User person;
    private String proToken;

    @BeforeEach
    void data() {
        asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
        person = user("user_person", "person@example.com");
        proToken = SecurityTestTokens.clerk("pro_asha", "asha@example.com");
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request).andReturn();
    }

    @Test
    void onlyAnAdminTokenOpensTheMeetTestEndpoint() throws Exception {
        var test = post("/api/admin/google-meet-test");
        assertThat(as(null, test).getResponse().getStatus()).isEqualTo(401);
        assertThat(as(SecurityTestTokens.clerk("user_person", "person@example.com"), post("/api/admin/google-meet-test"))
                .getResponse().getStatus()).isIn(401, 403);
        assertThat(as(proToken, post("/api/admin/google-meet-test")).getResponse().getStatus()).isIn(401, 403);
        assertThat(as(SecurityTestTokens.adminWrongSecret(), post("/api/admin/google-meet-test")).getResponse().getStatus())
                .isEqualTo(401);

        // Nobody but the admin reached Google.
        org.mockito.Mockito.verifyNoInteractions(googleMeet);

        org.mockito.Mockito.when(googleMeet.createSpace()).thenReturn(
                new GoogleMeetService.Space("spaces/jQCFfuBOdN5z", "https://meet.google.com/abc-mnop-xyz"));
        MvcResult admin = as(SecurityTestTokens.admin(), post("/api/admin/google-meet-test"));
        assertThat(admin.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(admin.getResponse().getContentAsString()).toString())
                .isEqualTo("{\"meetingUri\":\"https://meet.google.com/abc-mnop-xyz\",\"spaceName\":\"spaces/jQCFfuBOdN5z\"}");
        // Opened straight away, so both test accounts walk in.
        org.mockito.Mockito.verify(googleMeet).setAccessType("spaces/jQCFfuBOdN5z", GoogleMeetService.AccessType.OPEN);

        org.mockito.Mockito.when(googleMeet.createSpace()).thenThrow(new GoogleMeetService.NotConfiguredException());
        MvcResult notConfigured = as(SecurityTestTokens.admin(), post("/api/admin/google-meet-test"));
        assertThat(notConfigured.getResponse().getStatus()).isEqualTo(503);
        assertThat(JSON.readTree(notConfigured.getResponse().getContentAsString()).get("error").asString())
                .isEqualTo("Google Meet is not configured: set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and GOOGLE_REFRESH_TOKEN.");
    }

    @Test
    void onlyAnAdminTokenCanRetryABookingsMeetingLink() throws Exception {
        Booking b = confirmedVideoWithoutLink();
        String path = "/api/admin/bookings/" + b.getId() + "/retry-meeting-link";
        org.mockito.Mockito.when(googleMeet.createSpace()).thenReturn(
                new GoogleMeetService.Space("spaces/retried", "https://meet.google.com/ret-ried-now"));

        assertThat(as(null, post(path)).getResponse().getStatus()).isEqualTo(401);
        assertThat(as(SecurityTestTokens.clerk("user_person", "person@example.com"), post(path)).getResponse().getStatus())
                .isIn(401, 403);
        assertThat(as(proToken, post(path)).getResponse().getStatus()).isIn(401, 403);
        assertThat(as(SecurityTestTokens.adminWrongSecret(), post(path)).getResponse().getStatus()).isEqualTo(401);
        org.mockito.Mockito.verifyNoInteractions(googleMeet, notifications);

        MvcResult retried = as(SecurityTestTokens.admin(), post(path));
        assertThat(retried.getResponse().getStatus()).as(retried.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(retried.getResponse().getContentAsString()).get("result").asString()).isEqualTo("CREATED");
        Booking stored = bookings.findById(b.getId()).orElseThrow();
        assertThat(stored.getMeetingLink()).isEqualTo("https://meet.google.com/ret-ried-now");
        assertThat(stored.isManualMeetingLinkNeeded()).isFalse();
        org.mockito.Mockito.verify(notifications).send(org.mockito.ArgumentMatchers.anyList());

        // Again: it already has its link. A missing booking: 404.
        assertThat(JSON.readTree(as(SecurityTestTokens.admin(), post(path)).getResponse().getContentAsString())
                .get("result").asString()).isEqualTo("ALREADY_HAS_LINK");
        assertThat(as(SecurityTestTokens.admin(), post("/api/admin/bookings/" + java.util.UUID.randomUUID()
                + "/retry-meeting-link")).getResponse().getStatus()).isEqualTo(404);
    }

    private Booking confirmedVideoWithoutLink() {
        Booking b = booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED, Instant.now().plus(Duration.ofDays(3)), null);
        b.setMeetingLink(null);
        b.setManualMeetingLinkNeeded(true);
        return bookings.saveAndFlush(b);
    }

    private MvcResult addLink(String token, Booking b, String link) throws Exception {
        return as(token, post("/api/pro/bookings/" + b.getId() + "/meeting-link").contentType(MediaType.APPLICATION_JSON)
                .content("{\"meetingLink\":\"" + link + "\"}"));
    }

    @Test
    void aProfessionalAddsTheVideoLinkByHandWhenTheAutomaticOneFailed() throws Exception {
        Booking b = confirmedVideoWithoutLink();
        JsonNode before = JSON.readTree(as(proToken,
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/pro/bookings/" + b.getId()))
                .getResponse().getContentAsString());
        assertThat(before.get("meetingLinkNeeded").asBoolean()).isTrue();

        assertThat(addLink(proToken, b, "not a link").getResponse().getStatus()).isEqualTo(400);
        // Another professional gets the same 404 as a missing booking.
        Professional other = listed("Dr. Other");
        account(other, "pro_other");
        assertThat(addLink(SecurityTestTokens.clerk("pro_other", "other@example.com"), b, "https://meet.google.com/abc-defg-hij")
                .getResponse().getStatus()).isEqualTo(404);

        MvcResult added = addLink(proToken, b, "https://meet.google.com/abc-defg-hij");
        assertThat(added.getResponse().getStatus()).as(added.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode view = JSON.readTree(added.getResponse().getContentAsString());
        // Stored, but like every video link only ever given out by the join endpoint.
        assertThat(view.get("meetingLink").isNull()).isTrue();
        assertThat(view.get("meetingLinkNeeded").asBoolean()).isFalse();
        Booking stored = bookings.findById(b.getId()).orElseThrow();
        assertThat(stored.getMeetingLink()).isEqualTo("https://meet.google.com/abc-defg-hij");
        assertThat(stored.getMeetingSpaceName()).isNull();
        assertThat(stored.isManualMeetingLinkNeeded()).isFalse();
        assertThat(stored.getMeetingMethod()).isEqualTo("VIDEO");

        // It keeps its link: a second one is refused.
        assertThat(addLink(proToken, b, "https://meet.google.com/xyz").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aPhoneSessionOrAnUnconfirmedBookingCannotGetAVideoLink() throws Exception {
        Booking phone = confirmedVideoWithoutLink();
        phone.setPreferredMethod("PHONE");
        phone.setMeetingMethod("PHONE");
        bookings.saveAndFlush(phone);
        assertThat(addLink(proToken, phone, "https://meet.google.com/abc-defg-hij").getResponse().getStatus()).isEqualTo(409);

        Booking held = booking(person.getId(), asha.getId(), BookingStatus.AWAITING_PAYMENT,
                Instant.now().plus(Duration.ofDays(4)), null);
        assertThat(addLink(proToken, held, "https://meet.google.com/abc-defg-hij").getResponse().getStatus()).isEqualTo(409);
    }
}
