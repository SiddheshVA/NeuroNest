package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminBookingDtos.AdminBookingItem;
import com.neuronest.backend.admin.AdminProfessionalDtos.PageResponse;
import com.neuronest.backend.auth.ClerkTokenVerifier;
import com.neuronest.backend.auth.ClerkUserService;
import com.neuronest.backend.auth.JwtService;
import com.neuronest.backend.config.SecurityConfig;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The new admin routes through the real security chains: only an admin token gets in. A signed-in
// user's token, a professional's token and no token all get 401.
@WebMvcTest(controllers = AdminBookingController.class)
@Import(SecurityConfig.class)
class AdminBookingSecurityTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-clerk-token";
    private static final String PRO_TOKEN = "pro-clerk-token";
    private static final UUID ID = UUID.randomUUID();

    private static final List<MockHttpServletRequestBuilder> ROUTES = List.of(
            request(HttpMethod.GET, "/api/admin/dashboard/summary"),
            request(HttpMethod.GET, "/api/admin/bookings"),
            request(HttpMethod.POST, "/api/admin/bookings/{id}/nudge", ID),
            request(HttpMethod.POST, "/api/admin/bookings/{id}/close", ID)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Closing this request.\"}"),
            request(HttpMethod.GET, "/api/admin/professionals/responsiveness"),
            request(HttpMethod.POST, "/api/admin/professionals/{id}/suspend", ID)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Suspending this professional.\"}"),
            request(HttpMethod.POST, "/api/admin/professionals/{id}/reinstate", ID)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Reinstating this professional.\"}"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private com.neuronest.backend.meeting.MeetingLinkRecovery meetingLinkRecovery;
    @MockitoBean
    private com.neuronest.backend.booking.BookingRepository bookingRows;
    @MockitoBean
    private AdminBookingService bookings;
    @MockitoBean
    private AdminSuspensionService suspensions;
    @MockitoBean
    private AdminJwtService adminJwtService;
    @MockitoBean
    private ClerkTokenVerifier clerkTokenVerifier;
    @MockitoBean
    private ClerkUserService clerkUserService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private ProfessionalAccountRepository accounts;

    @BeforeEach
    void setUp() {
        when(adminJwtService.verify(anyString())).thenReturn(Optional.empty());
        when(adminJwtService.verify(ADMIN_TOKEN)).thenReturn(Optional.of("admin"));
        // Valid Clerk tokens for a signed-in user and a professional: still not admin tokens.
        when(clerkTokenVerifier.verify(USER_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("user_clerk", null, null));
        when(clerkTokenVerifier.verify(PRO_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("pro_clerk", null, null));
        when(bookings.list(any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(new PageResponse<>(List.of(item()), 0, 20, 1, 1));
    }

    private static AdminBookingItem item() {
        Instant now = Instant.parse("2026-10-12T04:30:00Z");
        return new AdminBookingItem(UUID.randomUUID(), "PENDING", now, now.plusSeconds(3600), 3, UUID.randomUUID(),
                "Dr. Asha Rao", "Ravi Kumar", "ONLINE", "VIDEO", OffsetDateTime.parse("2026-10-14T18:00+05:30"), null,
                null, null, null, null, null);
    }

    @Test
    void aUserTokenAProfessionalTokenAndNoTokenGet401OnEveryNewRoute() throws Exception {
        for (MockHttpServletRequestBuilder route : ROUTES) {
            for (String token : new String[]{USER_TOKEN, PRO_TOKEN, null}) {
                MockHttpServletRequestBuilder call = token == null ? route : route.header("Authorization", "Bearer " + token);
                mvc.perform(call)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.error").value("Authentication required."));
            }
        }
    }

    @Test
    void theAdminTokenGetsInAndTheListJsonHasNoPrivateFields() throws Exception {
        String body = mvc.perform(request(HttpMethod.GET, "/api/admin/bookings").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode first = JsonMapper.builder().build().readTree(body).get("items").get(0);
        List<String> fields = new ArrayList<>(first.propertyNames());
        assertThat(fields).containsExactlyInAnyOrder("id", "status", "createdAt", "expiresAt", "hoursWaiting",
                "professionalId", "professionalName", "contactName", "preferredMode", "preferredMethod",
                "scheduledOrRequestedTime", "proposedDatetime", "respondedBy", "respondedAt", "adminNudgedAt", "adminNote",
                "paymentDueAt");
        assertThat(body).doesNotContain("contactEmail", "contact_email", "contactPhone", "\"note\"", "professionalNote",
                "sharedAssessment", "meetingLink", "meetingAddress", "userId", "sessionId");
    }
}
