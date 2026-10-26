package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.meeting.GoogleMeetService;
import com.neuronest.backend.professional.Professional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * "Join session" over HTTP, with real repositories and Google mocked: the raw link only comes from the join
 * endpoints, only to the booking's own user or professional, and only from 5 minutes before the start until
 * the end; inside that window the space is opened first.
 */
class MeetingJoinSecurityTest extends SecuritySuiteBase {

    private static final String SPACE = "spaces/jQCFfuBOdN5z";
    private static final String MEET = "https://meet.google.com/abc-mnop-xyz";

    private Professional asha;
    private User person;
    private String userToken;
    private String proToken;

    @BeforeEach
    void data() {
        asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
        person = user("user_person", "person@example.com");
        user("user_other", "other@example.com");
        userToken = SecurityTestTokens.clerk("user_person", "person@example.com");
        proToken = SecurityTestTokens.clerk("pro_asha", "asha@example.com");
    }

    // A confirmed one-session video booking with a Meet space, starting at `start`.
    private Booking video(Instant start) {
        Booking b = booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED, start.truncatedTo(ChronoUnit.SECONDS), null);
        b.setPreferredMethod("VIDEO");
        b.setMeetingMethod("VIDEO");
        b.setEndsAt(b.getPreferredDatetime().plus(Duration.ofMinutes(60)));
        b.setBlockedUntil(b.getPreferredDatetime().plus(Duration.ofMinutes(75)));
        b.setMeetingSpaceName(SPACE);
        b.setMeetingLink(MEET);
        return bookings.saveAndFlush(b);
    }

    private MvcResult join(String token, String path) throws Exception {
        var request = get(path);
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request).andReturn();
    }

    private static String userJoin(Booking b) {
        return "/api/bookings/" + b.getId() + "/join";
    }

    private static String proJoin(Booking b) {
        return "/api/pro/bookings/" + b.getId() + "/join";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void tooEarlyAnswers409WithTheWindowAndNeverTheLink() throws Exception {
        Booking b = video(Instant.now().plus(Duration.ofMinutes(30)));

        for (MvcResult result : List.of(join(userToken, userJoin(b)), join(proToken, proJoin(b)))) {
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            JsonNode body = json(result);
            assertThat(body.get("code").asString()).isEqualTo("TOO_EARLY");
            assertThat(OffsetDateTime.parse(body.get("opensAt").asString()).toInstant())
                    .isEqualTo(b.getPreferredDatetime().minus(Duration.ofMinutes(5)));
            assertThat(OffsetDateTime.parse(body.get("endsAt").asString()).toInstant()).isEqualTo(b.getEndsAt());
            assertThat(body.get("error").asString()).startsWith("Your join button becomes active at ").endsWith("(IST).");
            assertThat(result.getResponse().getContentAsString()).doesNotContain(MEET, "meet.google.com", SPACE);
        }
        verifyNoInteractions(googleMeet);
    }

    @Test
    void insideTheWindowTheSpaceIsOpenedOnceAndBothSidesGetTheLink() throws Exception {
        // Starts in 3 minutes: the window opened 2 minutes ago.
        Booking b = video(Instant.now().plus(Duration.ofMinutes(3)));

        MvcResult user = join(userToken, userJoin(b));
        assertThat(user.getResponse().getStatus()).as(user.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(user).toString()).isEqualTo("{\"meetingUri\":\"" + MEET + "\"}");
        verify(googleMeet, times(1)).setAccessType(SPACE, GoogleMeetService.AccessType.OPEN);
        assertThat(bookings.findById(b.getId()).orElseThrow().isMeetingAccessOpen()).isTrue();

        // Already open: the professional's join does not call Google again.
        MvcResult pro = join(proToken, proJoin(b));
        assertThat(pro.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(pro).get("meetingUri").asString()).isEqualTo(MEET);
        verify(googleMeet, times(1)).setAccessType(anyString(), any());

        // No other response carries the link.
        for (String path : List.of("/api/bookings/" + b.getId(), "/api/bookings/mine", "/api/bookings/summary")) {
            assertThat(join(userToken, path).getResponse().getContentAsString()).as(path).doesNotContain(MEET, SPACE);
        }
        for (String path : List.of("/api/pro/bookings/" + b.getId(), "/api/pro/bookings", "/api/pro/appointments",
                "/api/pro/dashboard")) {
            assertThat(join(proToken, path).getResponse().getContentAsString()).as(path).doesNotContain(MEET, SPACE);
        }
    }

    @Test
    void afterTheEndAnswers409Ended() throws Exception {
        Booking b = video(Instant.now().minus(Duration.ofHours(2)));

        for (MvcResult result : List.of(join(userToken, userJoin(b)), join(proToken, proJoin(b)))) {
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(json(result).get("code").asString()).isEqualTo("ENDED");
            assertThat(json(result).get("error").asString()).isEqualTo("This session has ended.");
            assertThat(result.getResponse().getContentAsString()).doesNotContain(MEET);
        }
        verifyNoInteractions(googleMeet);
    }

    @Test
    void onlyTheBookingsOwnUserAndProfessionalAndOnlyTheirOwnRole() throws Exception {
        Booking b = video(Instant.now().plus(Duration.ofMinutes(3)));
        String missing = join(userToken, "/api/bookings/00000000-0000-0000-0000-000000000000/join")
                .getResponse().getContentAsString();

        // Another user, another professional: the same 404 as a missing booking.
        MvcResult otherUser = join(SecurityTestTokens.clerk("user_other", "other@example.com"), userJoin(b));
        assertThat(otherUser.getResponse().getStatus()).isEqualTo(404);
        assertThat(otherUser.getResponse().getContentAsString()).isEqualTo(missing);
        Professional other = listed("Dr. Other");
        account(other, "pro_other");
        assertThat(join(SecurityTestTokens.clerk("pro_other", "other.pro@example.com"), proJoin(b)).getResponse().getStatus())
                .isEqualTo(404);

        // The wrong role, an admin token, or no token at all. A professional's Clerk sign-in is also a valid
        // user sign-in (a professional may book as a client), but not this booking's client: the same 404.
        assertThat(join(proToken, userJoin(b)).getResponse().getStatus()).isEqualTo(404);
        assertThat(join(proToken, userJoin(b)).getResponse().getContentAsString()).isEqualTo(missing);
        // A plain user on the professional area gets the portal's usual "no professional account" answer.
        MvcResult userOnPro = join(userToken, proJoin(b));
        assertThat(userOnPro.getResponse().getStatus()).isEqualTo(404);
        assertThat(userOnPro.getResponse().getContentAsString()).isEqualTo(
                "{\"error\":\"No professional account exists for this sign-in. Register as a professional first.\"}");
        for (String path : List.of(userJoin(b), proJoin(b))) {
            assertThat(join(SecurityTestTokens.admin(), path).getResponse().getStatus()).as(path).isIn(401, 403);
            assertThat(join(null, path).getResponse().getStatus()).as(path).isEqualTo(401);
        }
        verifyNoInteractions(googleMeet);
    }

    @Test
    void phoneSessionsPastedLinksAndGoogleFailures() throws Exception {
        Instant soon = Instant.now().plus(Duration.ofMinutes(3));
        Booking phone = video(soon);
        phone.setPreferredMethod("PHONE");
        phone.setMeetingMethod("PHONE");
        bookings.saveAndFlush(phone);
        MvcResult notVideo = join(userToken, userJoin(phone));
        assertThat(notVideo.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(notVideo).get("code").asString()).isEqualTo("NOT_AVAILABLE");

        // A link the professional pasted has no space: it is given out in the window, with no Google call.
        Booking pasted = video(soon.plus(Duration.ofSeconds(1)));
        pasted.setMeetingSpaceName(null);
        pasted.setMeetingLink("https://zoom.us/j/123456");
        bookings.saveAndFlush(pasted);
        MvcResult manual = join(userToken, userJoin(pasted));
        assertThat(manual.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(manual).get("meetingUri").asString()).isEqualTo("https://zoom.us/j/123456");
        verify(googleMeet, never()).setAccessType(anyString(), any());

        // Google cannot open the space: 503, the link withheld, nothing recorded.
        Booking b = video(soon.plus(Duration.ofSeconds(2)));
        doThrow(new GoogleMeetService.GoogleMeetException("Google Meet refused to change the meeting space (HTTP 500)."))
                .when(googleMeet).setAccessType(SPACE, GoogleMeetService.AccessType.OPEN);
        MvcResult failed = join(userToken, userJoin(b));
        assertThat(failed.getResponse().getStatus()).isEqualTo(503);
        assertThat(failed.getResponse().getContentAsString()).doesNotContain(MEET);
        assertThat(bookings.findById(b.getId()).orElseThrow().isMeetingAccessOpen()).isFalse();
    }
}
