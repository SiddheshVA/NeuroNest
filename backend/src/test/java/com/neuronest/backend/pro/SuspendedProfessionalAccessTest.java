package com.neuronest.backend.pro;

import com.neuronest.backend.admin.AdminBookingDtos.StatusNoteRequest;
import com.neuronest.backend.admin.AdminJwtService;
import com.neuronest.backend.admin.AdminSuspensionService;
import com.neuronest.backend.auth.ClerkTokenVerifier;
import com.neuronest.backend.auth.ClerkUserService;
import com.neuronest.backend.auth.JwtService;
import com.neuronest.backend.config.SecurityConfig;
import com.neuronest.backend.professional.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Suspending a professional disables their account, and the professional filter refuses their very next
// request with 403.
@WebMvcTest(controllers = ProController.class)
@Import(SecurityConfig.class)
class SuspendedProfessionalAccessTest {

    private static final String PRO_TOKEN = "pro-token";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProProfileService profiles;
    @MockitoBean
    private ProDocumentService documents;
    @MockitoBean
    private ProPhotoService photos;
    @MockitoBean
    private ProfessionalAccountRepository accounts;
    @MockitoBean
    private ClerkTokenVerifier clerkTokenVerifier;
    @MockitoBean
    private ClerkUserService clerkUserService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AdminJwtService adminJwtService;

    @Test
    void aSuspendedProfessionalsTokenGets403OnProMe() throws Exception {
        UUID id = UUID.randomUUID();
        ProfessionalAccount account = new ProfessionalAccount(id, "asha@example.com", "pro_clerk");
        when(clerkTokenVerifier.verify(PRO_TOKEN)).thenReturn(new ClerkTokenVerifier.ClerkClaims("pro_clerk", null, null));
        when(accounts.findByClerkUserId("pro_clerk")).thenReturn(Optional.of(account));
        when(accounts.findAllByProfessionalId(id)).thenReturn(List.of(account));

        // Before: the token works.
        mvc.perform(get("/api/pro/me").header("Authorization", "Bearer " + PRO_TOKEN)).andExpect(status().isOk());

        Professional professional = new Professional();
        ReflectionTestUtils.setField(professional, "id", id);
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(CredentialType.PSYCHOLOGIST);
        professional.setConsultationMode(ConsultationMode.ONLINE);
        professional.setContactEmail("asha@example.com");
        professional.setVerificationStatus(VerificationStatus.VERIFIED);
        ProfessionalRepository professionals = mock(ProfessionalRepository.class);
        when(professionals.findByIdForUpdate(id)).thenReturn(Optional.of(professional));
        when(professionals.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        new AdminSuspensionService(professionals, accounts, mock(VerificationLogRepository.class))
                .suspend(id, new StatusNoteRequest("Complaint under investigation."));

        // After: the same token is refused at once.
        mvc.perform(get("/api/pro/me").header("Authorization", "Bearer " + PRO_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("This professional account has been disabled. Please contact NeuroNest support."));
    }
}
