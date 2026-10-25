package com.neuronest.backend.booking;

import com.neuronest.backend.admin.AdminJwtService;
import com.neuronest.backend.auth.ClerkTokenVerifier;
import com.neuronest.backend.auth.ClerkUserService;
import com.neuronest.backend.auth.JwtService;
import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.BookingDtos.BookingSummary;
import com.neuronest.backend.booking.BookingDtos.NextAppointment;
import com.neuronest.backend.config.SecurityConfig;
import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/bookings/summary through the real security chains: only a Clerk sign-in gets in, the user is
// always the one from the token, and the JSON never carries anything private.
@WebMvcTest(controllers = BookingController.class)
@Import(SecurityConfig.class)
class BookingSummarySecurityTest {

    private static final String USER_TOKEN = "user-token";
    private static final String PRO_TOKEN = "pro-token";
    private static final String ADMIN_TOKEN = "admin-token";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BookingService service;
    @MockitoBean
    private BookingPaymentService payments;
    @MockitoBean
    private ClerkTokenVerifier clerkTokenVerifier;
    @MockitoBean
    private ClerkUserService clerkUserService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AdminJwtService adminJwtService;
    @MockitoBean
    private ProfessionalAccountRepository accounts;

    private final UUID userId = UUID.randomUUID();
    private final UUID professionalsOwnUserId = UUID.randomUUID();

    private static User user(UUID id) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @BeforeEach
    void setUp() {
        when(clerkTokenVerifier.verify(anyString())).thenReturn(null);
        when(clerkTokenVerifier.verify(USER_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("user_clerk", "u@example.com", null));
        when(clerkTokenVerifier.verify(PRO_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("pro_clerk", "p@example.com", null));
        when(clerkUserService.provision(eq("user_clerk"), any(), any())).thenReturn(user(userId));
        when(clerkUserService.provision(eq("pro_clerk"), any(), any())).thenReturn(user(professionalsOwnUserId));
        when(adminJwtService.verify(ADMIN_TOKEN)).thenReturn(Optional.of("admin"));
        when(service.summary(any())).thenReturn(new BookingSummary(null, List.of(), 0, 0));
        when(service.summary(userId)).thenReturn(summaryWithEverythingPrivateSetOnTheEntities());
    }

    // The entities carry every private value; the summary must leave all of them out.
    private static BookingSummary summaryWithEverythingPrivateSetOnTheEntities() {
        Professional p = new Professional();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setFullName("Dr. Asha Rao");
        p.setCredentialType(CredentialType.PSYCHOLOGIST);
        p.setCity("Pune");
        p.setContactEmail("PRIVATE-pro@example.com");
        p.setContactPhone("+919812345678");
        Booking b = new Booking();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setUserId(UUID.randomUUID());
        b.setSessionId(UUID.randomUUID());
        b.setContactEmail("PRIVATE-user@example.com");
        b.setContactPhone("+919876543210");
        b.setNote("PRIVATE-REQUEST-NOTE");
        b.setProfessionalNote("PRIVATE-PROFESSIONAL-NOTE");
        b.setAdminNote("PRIVATE-ADMIN-NOTE");
        b.setSharedAssessment(new SharedAssessment("PHQ9", "PHQ-9", 17, "moderately severe", "2026-10-01T10:00:00Z"));
        b.setStatus(BookingStatus.CONFIRMED);
        b.setPreferredMode("ONLINE");
        b.setPreferredDatetime(Instant.parse("2026-10-14T12:30:00Z"));
        b.setMeetingMethod("VIDEO");
        b.setMeetingLink("https://meet.example.com/room");
        return new BookingSummary(NextAppointment.from(b, p), List.of(), 2, 1);
    }

    @Test
    void noTokenAndAnAdminTokenGet401() throws Exception {
        mvc.perform(get("/api/bookings/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/summary").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isUnauthorized());
        verify(service, never()).summary(any());
    }

    @Test
    void theUsersSummaryHasOnlyTheAllowedFieldsAndNothingPrivate() throws Exception {
        String body = mvc.perform(get("/api/bookings/summary").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(service).summary(userId);
        JsonNode json = JsonMapper.builder().build().readTree(body);
        assertThat(new ArrayList<>(json.propertyNames()))
                .containsExactlyInAnyOrder("nextAppointment", "awaitingPayment", "waitingForProfessional", "needsYourAnswer");
        assertThat(new ArrayList<>(json.get("nextAppointment").propertyNames())).containsExactlyInAnyOrder("id",
                "scheduledTime", "preferredMode", "meetingMethod", "meetingLink", "meetingAddress", "professional",
                "sessionCount", "endDatetime", "professionalEmail", "professionalPhone");
        assertThat(new ArrayList<>(json.get("nextAppointment").get("professional").propertyNames()))
                .containsExactlyInAnyOrder("id", "fullName", "credentialType", "city");
        assertThat(json.get("waitingForProfessional").asLong()).isEqualTo(2);
        assertThat(json.get("needsYourAnswer").asLong()).isEqualTo(1);
        assertThat(json.get("nextAppointment").get("scheduledTime").asString()).isEqualTo("2026-10-14T18:00:00+05:30");
        // The appointment is CONFIRMED, so the professional's contact details are shared; nothing else private is.
        assertThat(json.get("nextAppointment").get("professionalEmail").asString()).isEqualTo("PRIVATE-pro@example.com");
        assertThat(json.get("nextAppointment").get("professionalPhone").asString()).isEqualTo("+919812345678");
        assertThat(body).doesNotContain("PRIVATE-user", "PRIVATE-REQUEST", "PRIVATE-PROFESSIONAL", "PRIVATE-ADMIN",
                "9876543210", "PHQ", "score", "userId", "sessionId", "contactEmail", "contactPhone", "Note", "note");
    }

    @Test
    void aProfessionalsTokenOnlyEverReachesThatPersonsOwnUserBookings() throws Exception {
        // A professional's Clerk sign-in is mapped onto their own users row, like any Clerk sign-in here:
        // they get their own bookings as a person (none), never another user's and never their listing's.
        String body = mvc.perform(get("/api/bookings/summary").header("Authorization", "Bearer " + PRO_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(service).summary(professionalsOwnUserId);
        verify(service, never()).summary(userId);
        assertThat(body).isEqualTo("{\"nextAppointment\":null,\"awaitingPayment\":[],\"waitingForProfessional\":0,\"needsYourAnswer\":0}");
        assertThat(List.of(body)).noneMatch(b -> b.contains("Asha"));
    }
}
