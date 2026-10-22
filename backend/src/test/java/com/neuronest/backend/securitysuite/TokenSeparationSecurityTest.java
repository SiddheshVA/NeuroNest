package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Token separation: each area accepts only its own kind of credential. A failure here means a token
 * meant for one area (or a forged, expired or unsigned one) was accepted by another area, or that a
 * disabled or suspended professional could still use the portal.
 */
class TokenSeparationSecurityTest extends SecuritySuiteBase {

    private static final String AUTH_REQUIRED = "{\"error\":\"Authentication required.\"}";
    private static final String NO_PRO_ACCOUNT =
            "{\"error\":\"No professional account exists for this sign-in. Register as a professional first.\"}";

    private Professional asha;

    @BeforeEach
    void data() {
        asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
    }

    /** Every caller that is neither a valid admin nor a valid Clerk sign-in. */
    private static Map<String, String> forgedOrMissing() {
        Map<String, String> callers = new LinkedHashMap<>();
        callers.put("no token", null);
        callers.put("garbage token", SecurityTestTokens.GARBAGE);
        callers.put("admin token, wrong secret", SecurityTestTokens.adminWrongSecret());
        callers.put("admin token, expired", SecurityTestTokens.adminExpired());
        callers.put("admin token, alg none", SecurityTestTokens.adminAlgNone());
        callers.put("Clerk token, wrong key", SecurityTestTokens.clerkWrongKey("pro_asha", "asha@example.com"));
        callers.put("Clerk token, expired", SecurityTestTokens.clerkExpired("pro_asha", "asha@example.com"));
        callers.put("Clerk token, alg none", SecurityTestTokens.clerkAlgNone("pro_asha", "asha@example.com"));
        return callers;
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request).andReturn();
    }

    private void expect(MockHttpServletRequestBuilder request, String token, int status, String body, String caller)
            throws Exception {
        MvcResult result = call(request, token);
        assertThat(result.getResponse().getStatus()).as(caller).isEqualTo(status);
        if (body != null) assertThat(result.getResponse().getContentAsString()).as(caller).isEqualTo(body);
    }

    // ---- /api/admin/** ----

    @Test
    void theAdminAreaAcceptsOnlyAValidAdminToken() throws Exception {
        expect(get("/api/admin/professionals"), SecurityTestTokens.admin(), 200, null, "admin token");
        for (var caller : forgedOrMissing().entrySet()) {
            expect(get("/api/admin/professionals"), caller.getValue(), 401, AUTH_REQUIRED, caller.getKey());
        }
        expect(get("/api/admin/professionals"), SecurityTestTokens.clerk("pro_asha", "asha@example.com"), 401,
                AUTH_REQUIRED, "professional's Clerk token");
        expect(get("/api/admin/professionals"), SecurityTestTokens.clerk("user_plain", "plain@example.com"), 401,
                AUTH_REQUIRED, "plain user's Clerk token");
    }

    // ---- /api/pro/** ----

    @Test
    void thePortalAcceptsOnlyAProfessionalsClerkToken() throws Exception {
        expect(get("/api/pro/bookings"), SecurityTestTokens.clerk("pro_asha", "asha@example.com"), 200, null,
                "professional's Clerk token");
        expect(get("/api/pro/bookings"), SecurityTestTokens.clerk("user_plain", "plain@example.com"), 404,
                NO_PRO_ACCOUNT, "plain user's Clerk token");
        expect(get("/api/pro/bookings"), SecurityTestTokens.admin(), 401, AUTH_REQUIRED, "admin token");
        for (var caller : forgedOrMissing().entrySet()) {
            expect(get("/api/pro/bookings"), caller.getValue(), 401, AUTH_REQUIRED, caller.getKey());
        }
    }

    private static List<MockHttpServletRequestBuilder> portalRoutes() {
        return List.of(get("/api/pro/me"), get("/api/pro/bookings"), get("/api/pro/bookings/summary"),
                get("/api/pro/dashboard"), get("/api/pro/appointments"), get("/api/pro/days-off"),
                post("/api/pro/days-off").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateFrom\":\"2030-01-01\",\"dateTo\":\"2030-01-02\"}"),
                delete("/api/pro/days-off/" + java.util.UUID.randomUUID()));
    }

    @Test
    void aDisabledProfessionalGets403OnEveryPortalRoute() throws Exception {
        Professional disabledPro = listed("Dr. Disabled Person");
        ProfessionalAccount disabled = account(disabledPro, "pro_disabled");
        disabled.setStatus(ProfessionalAccountStatus.DISABLED);
        accounts.saveAndFlush(disabled);
        String token = SecurityTestTokens.clerk("pro_disabled", "disabled@example.com");

        for (MockHttpServletRequestBuilder route : portalRoutes()) {
            MvcResult result = call(route, token);
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString())
                    .isEqualTo("{\"error\":\"This professional account has been disabled. Please contact NeuroNest support.\"}");
        }
    }

    @Test
    void aSuspendedProfessionalGets403OnEveryPortalRouteAtOnce() throws Exception {
        String token = SecurityTestTokens.clerk("pro_asha", "asha@example.com");
        expect(get("/api/pro/dashboard"), token, 200, null, "before suspension");

        expect(post("/api/admin/professionals/" + asha.getId() + "/suspend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Complaint under investigation.\"}"),
                SecurityTestTokens.admin(), 200, null, "suspend");

        for (MockHttpServletRequestBuilder route : portalRoutes()) {
            assertThat(call(route, token).getResponse().getStatus()).isEqualTo(403);
        }
    }

    // ---- /api/bookings/** ----

    @Test
    void userBookingsAcceptOnlyAClerkSignIn() throws Exception {
        expect(get("/api/bookings/mine"), SecurityTestTokens.clerk("user_plain", "plain@example.com"), 200, null,
                "plain user's Clerk token");
        expect(get("/api/bookings/mine"), SecurityTestTokens.admin(), 401, AUTH_REQUIRED, "admin token");
        for (var caller : forgedOrMissing().entrySet()) {
            expect(get("/api/bookings/mine"), caller.getValue(), 401, AUTH_REQUIRED, caller.getKey());
        }
    }

    @Test
    void aProfessionalsClerkTokenUsesBookingsOnlyAsThePersonItIs() throws Exception {
        String token = SecurityTestTokens.clerk("pro_asha", "asha.person@example.com");
        User someoneElse = user("user_other", "other@example.com");
        Instant future = Instant.now().plus(Duration.ofDays(3));
        // A request someone else made to Asha's listing is not one of Asha's own bookings.
        Booking toHerListing = booking(someoneElse.getId(), asha.getId(), BookingStatus.PENDING, future, future);

        MvcResult mine = call(get("/api/bookings/mine"), token);
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(mine.getResponse().getContentAsString()).get("items").size()).isZero();
        assertThat(call(get("/api/bookings/" + toHerListing.getId()), token).getResponse().getStatus()).isEqualTo(404);

        // And she cannot book her own listing.
        String slot = Instant.now().plus(Duration.ofDays(3)).atZone(ZoneId.of("Asia/Kolkata"))
                .withHour(10).truncatedTo(ChronoUnit.HOURS).toOffsetDateTime().toString();
        String body = "{\"professionalId\":\"" + asha.getId() + "\",\"preferredMode\":\"ONLINE\",\"preferredMethod\":\"VIDEO\","
                + "\"contactName\":\"Asha\",\"contactPhone\":\"9876543210\",\"slotStart\":\"" + slot + "\",\"consentGiven\":true}";
        MvcResult own = call(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body), token);
        assertThat(own.getResponse().getStatus()).isEqualTo(409);
        assertThat(own.getResponse().getContentAsString()).contains("You cannot book your own professional listing.");
        assertThat(bookings.count()).isEqualTo(1);
    }
}
