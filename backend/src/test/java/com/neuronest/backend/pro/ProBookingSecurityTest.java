package com.neuronest.backend.pro;

import com.neuronest.backend.admin.AdminJwtService;
import com.neuronest.backend.auth.ClerkTokenVerifier;
import com.neuronest.backend.auth.ClerkUserService;
import com.neuronest.backend.auth.JwtService;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.SlotService;
import com.neuronest.backend.config.SecurityConfig;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalAccountStatus;
import com.neuronest.backend.professional.ProfessionalDayOffRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Runs /api/pro/bookings through the real security chains (SecurityConfig and ProAuthFilter) with the
// token checks and repositories mocked: only a professional's own Clerk token gets through.
@WebMvcTest(controllers = ProBookingController.class)
@Import({SecurityConfig.class, ProBookingService.class, SlotService.class, AvailabilityService.class})
class ProBookingSecurityTest {

    private static final String PRO_TOKEN = "pro-token";
    private static final String DISABLED_TOKEN = "disabled-token";
    private static final String USER_TOKEN = "user-token";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final String LEGACY_USER_TOKEN = "legacy-user-token";

    @Autowired
    private MockMvc mvc;

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
    @MockitoBean
    private ProfessionalRepository professionals;
    @MockitoBean
    private BookingRepository bookings;
    @MockitoBean
    private ProfessionalDayOffRepository daysOff;

    private final UUID professionalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(clerkTokenVerifier.verify(anyString())).thenReturn(null);
        when(clerkTokenVerifier.verify(PRO_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("pro_clerk", null, null));
        when(clerkTokenVerifier.verify(DISABLED_TOKEN))
                .thenReturn(new ClerkTokenVerifier.ClerkClaims("disabled_clerk", null, null));
        when(clerkTokenVerifier.verify(USER_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("user_clerk", null, null));
        // A valid admin token and a valid end-user session token, neither of which is a Clerk token.
        when(adminJwtService.verify(ADMIN_TOKEN)).thenReturn(Optional.of("admin"));

        when(accounts.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(accounts.findByClerkUserId("pro_clerk"))
                .thenReturn(Optional.of(new ProfessionalAccount(professionalId, "asha@example.com", "pro_clerk")));
        ProfessionalAccount disabled = new ProfessionalAccount(UUID.randomUUID(), "old@example.com", "disabled_clerk");
        ReflectionTestUtils.setField(disabled, "status", ProfessionalAccountStatus.DISABLED);
        when(accounts.findByClerkUserId("disabled_clerk")).thenReturn(Optional.of(disabled));
        when(bookings.findByProfessionalId(eq(professionalId), any(Pageable.class))).thenReturn(Page.empty());
    }

    @Test
    void theProfessionalsOwnTokenWorks() throws Exception {
        mvc.perform(get("/api/pro/bookings").header("Authorization", "Bearer " + PRO_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void aDisabledProfessionalGets403() throws Exception {
        mvc.perform(get("/api/pro/bookings").header("Authorization", "Bearer " + DISABLED_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/pro/bookings/{id}/decline", UUID.randomUUID())
                        .header("Authorization", "Bearer " + DISABLED_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminTokenDoesNotWork() throws Exception {
        mvc.perform(get("/api/pro/bookings").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required."));
        mvc.perform(get("/api/pro/bookings/summary").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aPlainUserTokenDoesNotWork() throws Exception {
        // A signed-in user without a professional account gets no bookings.
        mvc.perform(get("/api/pro/bookings").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No professional account exists for this sign-in. "
                        + "Register as a professional first."));
        mvc.perform(get("/api/pro/bookings/summary").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isNotFound());
        // The older end-user session token and cookie are not part of this chain.
        mvc.perform(get("/api/pro/bookings").header("Authorization", "Bearer " + LEGACY_USER_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/pro/bookings").cookie(new jakarta.servlet.http.Cookie("token", LEGACY_USER_TOKEN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noTokenGets401() throws Exception {
        mvc.perform(get("/api/pro/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/pro/bookings/{id}/confirm", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
