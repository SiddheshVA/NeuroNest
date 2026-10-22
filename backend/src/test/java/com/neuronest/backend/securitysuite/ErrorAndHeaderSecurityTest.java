package com.neuronest.backend.securitysuite;

import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Error bodies and caching headers. A failure means an error response reveals internals (a stack
 * trace, a class name or SQL), or that an admin or professional response may be stored by a browser or
 * proxy cache.
 */
class ErrorAndHeaderSecurityTest extends SecuritySuiteBase {

    private record Call(String label, MockHttpServletRequestBuilder request, String token, int status) {}

    @BeforeEach
    void data() {
        Professional asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
        Professional off = listed("Dr. Disabled Person");
        ProfessionalAccount disabled = account(off, "pro_disabled");
        disabled.setStatus(ProfessionalAccountStatus.DISABLED);
        accounts.saveAndFlush(disabled);
    }

    private MvcResult perform(Call call) throws Exception {
        if (call.token() != null) call.request().header("Authorization", bearer(call.token()));
        return mvc.perform(call.request()).andReturn();
    }

    private static String pro() {
        return SecurityTestTokens.clerk("pro_asha", "asha@example.com");
    }

    @Test
    void errorBodiesRevealNoStackTraceClassNameOrSql() throws Exception {
        String admin = SecurityTestTokens.admin();
        String user = SecurityTestTokens.clerk("user_plain", "plain@example.com");
        String random = UUID.randomUUID().toString();
        List<Call> calls = List.of(
                new Call("admin area without a token", get("/api/admin/professionals"), null, 401),
                new Call("portal without a token", get("/api/pro/bookings"), null, 401),
                new Call("bookings without a token", get("/api/bookings/mine"), null, 401),
                new Call("my area without a sign-in", get("/api/me/dashboard"), null, 401),
                new Call("portal with a forged token", get("/api/pro/dashboard"), SecurityTestTokens.clerkWrongKey("pro_asha", "a@example.com"), 401),
                new Call("disabled professional", get("/api/pro/dashboard"), SecurityTestTokens.clerk("pro_disabled", "d@example.com"), 403),
                new Call("portal without a professional account", get("/api/pro/bookings"), user, 404),
                new Call("missing public listing", get("/api/professionals/" + random), null, 404),
                new Call("missing request in the portal", get("/api/pro/bookings/" + random), pro(), 404),
                new Call("missing day off in the portal", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/pro/days-off/" + random), pro(), 404),
                new Call("missing booking of a user", get("/api/bookings/" + random), user, 404),
                new Call("missing professional in the admin area", get("/api/admin/professionals/" + random), admin, 404),
                new Call("missing booking in the admin area", post("/api/admin/bookings/" + random + "/nudge"), admin, 404),
                new Call("missing document in the admin area",
                        get("/api/admin/professionals/" + random + "/documents/" + random + "/file"), admin, 404),
                new Call("malformed id in the admin area", get("/api/admin/professionals/not-a-uuid"), admin, 400),
                new Call("malformed body in the portal", post("/api/pro/days-off").contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"), pro(), 400));
        for (Call call : calls) {
            MvcResult result = perform(call);
            assertThat(result.getResponse().getStatus()).as(call.label()).isEqualTo(call.status());
            String body = result.getResponse().getContentAsString();
            assertThat(body).as(call.label()).isNotBlank();
            assertCleanErrorBody(body);
        }
    }

    @Test
    void adminAndPortalResponsesAreNeverCached() throws Exception {
        String admin = SecurityTestTokens.admin();
        List<Call> calls = List.of(
                new Call("admin list", get("/api/admin/professionals"), admin, 200),
                new Call("admin summary", get("/api/admin/dashboard/summary"), admin, 200),
                new Call("admin without a token", get("/api/admin/professionals"), null, 401),
                new Call("portal dashboard", get("/api/pro/dashboard"), pro(), 200),
                new Call("portal requests", get("/api/pro/bookings"), pro(), 200),
                new Call("portal without a token", get("/api/pro/bookings"), null, 401),
                new Call("disabled professional", get("/api/pro/bookings"), SecurityTestTokens.clerk("pro_disabled", "d@example.com"), 403),
                new Call("portal without a professional account", get("/api/pro/bookings"),
                        SecurityTestTokens.clerk("user_plain", "plain@example.com"), 404));
        for (Call call : calls) {
            MvcResult result = perform(call);
            assertThat(result.getResponse().getStatus()).as(call.label()).isEqualTo(call.status());
            assertThat(result.getResponse().getHeaders("Cache-Control")).as(call.label()).anyMatch(v -> v.contains("no-store"));
        }
    }
}
