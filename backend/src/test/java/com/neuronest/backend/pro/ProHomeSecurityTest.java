package com.neuronest.backend.pro;

import com.neuronest.backend.admin.AdminJwtService;
import com.neuronest.backend.auth.ClerkTokenVerifier;
import com.neuronest.backend.auth.ClerkUserService;
import com.neuronest.backend.auth.JwtService;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.config.SecurityConfig;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalDayOffRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The new professional routes through the real security chains and the real service: only a signed-in
// professional gets in. A regular user's Clerk token, the older user token, an admin token and no token
// all get 401.
@WebMvcTest(controllers = ProHomeController.class)
@Import({SecurityConfig.class, ProHomeService.class})
class ProHomeSecurityTest {

    private static final String PRO_TOKEN = "pro-token";
    private static final String USER_TOKEN = "user-clerk-token";
    private static final String LEGACY_USER_TOKEN = "legacy-user-token";
    private static final String ADMIN_TOKEN = "admin-token";

    private static final List<MockHttpServletRequestBuilder> ROUTES = List.of(
            request(HttpMethod.GET, "/api/pro/dashboard"),
            request(HttpMethod.GET, "/api/pro/appointments"),
            request(HttpMethod.GET, "/api/pro/days-off"),
            request(HttpMethod.POST, "/api/pro/days-off").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"dateFrom\":\"2030-01-01\",\"dateTo\":\"2030-01-02\"}"),
            request(HttpMethod.DELETE, "/api/pro/days-off/{id}", UUID.randomUUID()));

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
    private ProfessionalDayOffRepository daysOff;
    @MockitoBean
    private BookingRepository bookings;

    @BeforeEach
    void setUp() {
        UUID professionalId = UUID.randomUUID();
        when(clerkTokenVerifier.verify(anyString())).thenReturn(null);
        when(clerkTokenVerifier.verify(PRO_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("pro_clerk", null, null));
        when(clerkTokenVerifier.verify(USER_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("user_clerk", null, null));
        when(adminJwtService.verify(ADMIN_TOKEN)).thenReturn(Optional.of("admin"));
        when(accounts.findByClerkUserId(anyString())).thenReturn(Optional.empty());
        when(accounts.findByClerkUserId("pro_clerk"))
                .thenReturn(Optional.of(new ProfessionalAccount(professionalId, "asha@example.com", "pro_clerk")));
        Professional p = new Professional();
        ReflectionTestUtils.setField(p, "id", professionalId);
        p.setVerificationStatus(VerificationStatus.VERIFIED);
        when(professionals.findById(professionalId)).thenReturn(Optional.of(p));
    }

    @Test
    void aUserTokenTheOlderUserTokenAnAdminTokenAndNoTokenAllGet401() throws Exception {
        for (MockHttpServletRequestBuilder route : ROUTES) {
            mvc.perform(route).andExpect(status().isUnauthorized());
            for (String token : new String[]{USER_TOKEN, LEGACY_USER_TOKEN, ADMIN_TOKEN}) {
                mvc.perform(route.header("Authorization", "Bearer " + token))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.error").exists());
            }
        }
    }

    @Test
    void aProfessionalTokenGetsIn() throws Exception {
        mvc.perform(get("/api/pro/dashboard").header("Authorization", "Bearer " + PRO_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));
        mvc.perform(get("/api/pro/appointments?when=soon").header("Authorization", "Bearer " + PRO_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("when must be upcoming or past."));
    }
}
