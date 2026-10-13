package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminVerificationDtos.NeedsInfoRequest;
import com.neuronest.backend.admin.AdminVerificationDtos.RejectRequest;
import com.neuronest.backend.admin.AdminVerificationDtos.VerifyRequest;
import com.neuronest.backend.admin.AdminVerificationExceptions.ChecklistIncompleteException;
import com.neuronest.backend.admin.AdminVerificationExceptions.DocumentNotFoundException;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.pro.ProDocumentStorage;
import com.neuronest.backend.professional.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T20:00:00Z"); // 13 Oct in India
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final Map<String, Boolean> REGISTERED_OK = Map.of("nameMatchesRegister", true,
            "registrationNumberChecked", true, "registrationActive", true, "credentialTypeMatches", true,
            "idMatchesPerson", true);
    private static final Map<String, Boolean> COUNSELOR_OK = Map.of("nameMatchesDegree", true,
            "degreeCertificateSeen", true, "credentialTypeMatches", true);

    @TempDir
    Path storageDir;

    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    private final ProfessionalDocumentRepository documents = mock(ProfessionalDocumentRepository.class);
    private final VerificationLogRepository log = mock(VerificationLogRepository.class);
    private final UUID id = UUID.randomUUID();
    private AdminVerificationService service;
    private Professional professional;

    @BeforeEach
    void setUp() {
        service = new AdminVerificationService(professionals, documents, log,
                new ProDocumentStorage(storageDir.toString()), Clock.fixed(NOW, AdminVerificationService.ZONE));
        when(professionals.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(professionals.existsById(id)).thenReturn(true);
    }

    private Professional submitted(CredentialType type) {
        professional = new Professional();
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(type);
        professional.setConsultationMode(ConsultationMode.ONLINE);
        professional.setContactEmail("asha@example.com");
        professional.setVerificationStatus(VerificationStatus.SUBMITTED);
        when(professionals.findByIdForUpdate(id)).thenReturn(Optional.of(professional));
        return professional;
    }

    private static VerifyRequest request(String source, LocalDate expiry, Map<String, Boolean> checklist) {
        return new VerifyRequest(source, expiry, null, " Checked on the RCI register. ", null, null, checklist);
    }

    @Test
    void verifySetsEverythingAndLogsIt() {
        submitted(CredentialType.PSYCHOLOGIST);
        UUID evidence = UUID.randomUUID();
        when(documents.findByIdAndProfessionalId(evidence, id)).thenReturn(Optional.of(mock(ProfessionalDocument.class)));
        LocalDate expiry = TODAY.plusMonths(3);

        var detail = service.verify(id, new VerifyRequest("rci", expiry, null, "Checked.", evidence, null, REGISTERED_OK));

        assertThat(detail.verificationStatus()).isEqualTo("VERIFIED");
        assertThat(detail.verified()).isTrue();
        assertThat(detail.active()).isTrue();
        assertThat(detail.verifiedBy()).isEqualTo("ADMIN");
        assertThat(detail.verifiedAt()).isEqualTo(NOW);
        assertThat(detail.verificationSource()).isEqualTo("RCI");
        assertThat(detail.registrationExpiry()).isEqualTo(expiry);
        assertThat(detail.nextRecheckDate()).isEqualTo(expiry); // earlier than 12 months
        ArgumentCaptor<VerificationLogEntry> entry = ArgumentCaptor.forClass(VerificationLogEntry.class);
        verify(log).save(entry.capture());
        assertThat(entry.getValue().getAction()).isEqualTo("VERIFIED");
        assertThat(entry.getValue().getFromStatus()).isEqualTo("SUBMITTED");
        assertThat(entry.getValue().getActor()).isEqualTo("ADMIN");
        assertThat(entry.getValue().getSource()).isEqualTo("RCI");
        assertThat(entry.getValue().getEvidenceDocumentId()).isEqualTo(evidence);
    }

    @Test
    void recheckDefaultsToTwelveMonthsAndActivateFalseKeepsItHidden() {
        submitted(CredentialType.COUNSELOR);
        var detail = service.verify(id, new VerifyRequest("DEGREE_CERTIFICATE", null, null, null, null, false, COUNSELOR_OK));
        assertThat(detail.nextRecheckDate()).isEqualTo(TODAY.plusMonths(12));
        assertThat(detail.active()).isFalse();
        assertThat(detail.verificationNotes()).isNull();
    }

    @Test
    void verifyRejectsIncompleteChecklistWrongSourceAndBadDates() {
        submitted(CredentialType.PSYCHIATRIST);
        assertThatThrownBy(() -> service.verify(id, request("NMC", null, Map.of("nameMatchesRegister", true,
                "registrationActive", false))))
                .isInstanceOfSatisfying(ChecklistIncompleteException.class, e -> assertThat(e.missing())
                        .containsExactly("registrationNumberChecked", "registrationActive", "credentialTypeMatches",
                                "idMatchesPerson"));
        assertThatThrownBy(() -> service.verify(id, request("RCI", null, REGISTERED_OK)))
                .hasMessage("source must be NMC, STATE_COUNCIL, OTHER for a psychiatrist.");
        assertThatThrownBy(() -> service.verify(id, request(null, null, REGISTERED_OK)))
                .hasMessageStartingWith("source is required.");
        assertThatThrownBy(() -> service.verify(id, request("NMC", TODAY, REGISTERED_OK)))
                .hasMessage("registrationExpiry must be tomorrow or later (India time).");
        professional.setRegistrationExpiry(TODAY.minusDays(1));
        assertThatThrownBy(() -> service.verify(id, request("STATE_COUNCIL", null, REGISTERED_OK)))
                .hasMessageStartingWith("The stored registrationExpiry");
        UUID foreign = UUID.randomUUID();
        when(documents.findByIdAndProfessionalId(foreign, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify(id, new VerifyRequest("NMC", TODAY.plusDays(1), null, null, foreign,
                null, REGISTERED_OK))).hasMessage("evidenceDocumentId is not one of this professional's documents.");
        assertThatThrownBy(() -> service.verify(id, new VerifyRequest("NMC", TODAY.plusDays(1), null, "x".repeat(1001),
                null, null, REGISTERED_OK))).hasMessage("notes must be at most 1000 characters.");

        assertThat(professional.getVerificationStatus()).isEqualTo(VerificationStatus.SUBMITTED);
        verify(log, never()).save(any());
    }

    @Test
    void decisionsOnlyFromSubmitted() {
        submitted(CredentialType.COUNSELOR).setVerificationStatus(VerificationStatus.VERIFIED);
        assertThatThrownBy(() -> service.verify(id, request("DEGREE_CERTIFICATE", null, COUNSELOR_OK)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Only a SUBMITTED professional can be verified. This professional's verification status is VERIFIED.");
        assertThatThrownBy(() -> service.needsInfo(id, new NeedsInfoRequest("Please upload a clearer certificate.")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.reject(id, new RejectRequest("The registration could not be found.")))
                .isInstanceOf(ConflictException.class);
        when(professionals.findByIdForUpdate(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify(id, null)).isInstanceOf(ProfessionalNotFoundException.class);
    }

    @Test
    void needsInfoAndRejectHideAndRecordTheNote() {
        Professional p = submitted(CredentialType.PSYCHOLOGIST);
        p.setActive(true);
        assertThatThrownBy(() -> service.needsInfo(id, new NeedsInfoRequest(" short "))).isInstanceOf(ApiValidationException.class)
                .hasMessage("note must be between 10 and 1000 characters.");
        assertThatThrownBy(() -> service.needsInfo(id, new NeedsInfoRequest(null))).hasMessage("note is required (10 to 1000 characters).");

        var detail = service.needsInfo(id, new NeedsInfoRequest(" Please upload a clearer certificate. "));
        assertThat(detail.verificationStatus()).isEqualTo("NEEDS_INFO");
        assertThat(detail.verificationNotes()).isEqualTo("Please upload a clearer certificate.");
        assertThat(detail.active()).isFalse();
        assertThat(detail.verified()).isFalse();

        p.setVerificationStatus(VerificationStatus.SUBMITTED);
        assertThatThrownBy(() -> service.reject(id, new RejectRequest("no"))).hasMessage("reason must be between 10 and 1000 characters.");
        var rejected = service.reject(id, new RejectRequest("The registration could not be found."));
        assertThat(rejected.verificationStatus()).isEqualTo("REJECTED");
        assertThat(rejected.verificationNotes()).isEqualTo("The registration could not be found.");

        ArgumentCaptor<VerificationLogEntry> entries = ArgumentCaptor.forClass(VerificationLogEntry.class);
        verify(log, times(2)).save(entries.capture());
        assertThat(entries.getAllValues()).extracting(VerificationLogEntry::getAction).containsExactly("NEEDS_INFO", "REJECTED");
        assertThat(entries.getAllValues()).extracting(VerificationLogEntry::getActor).containsOnly("ADMIN");
    }

    @Test
    void documentFileMustBelongAndStayInsideStorage() throws Exception {
        UUID docId = UUID.randomUUID();
        Files.createDirectories(storageDir.resolve(id.toString()));
        Files.writeString(storageDir.resolve(id + "/a.pdf"), "%PDF-1.4");
        ProfessionalDocument doc = new ProfessionalDocument(id, DocumentType.ID_PROOF, id + "/a.pdf", "id.pdf",
                "application/pdf", 8);
        when(documents.findByIdAndProfessionalId(docId, id)).thenReturn(Optional.of(doc));

        var file = service.documentFile(id, docId);
        assertThat(file.size()).isEqualTo(8);
        assertThat(file.filename()).isEqualTo("id.pdf");

        assertThatThrownBy(() -> service.documentFile(id, UUID.randomUUID())).isInstanceOf(DocumentNotFoundException.class);
        ProfessionalDocument escaping = new ProfessionalDocument(id, DocumentType.ID_PROOF, "../outside.pdf", "x.pdf",
                "application/pdf", 8);
        UUID escapingId = UUID.randomUUID();
        when(documents.findByIdAndProfessionalId(escapingId, id)).thenReturn(Optional.of(escaping));
        assertThatThrownBy(() -> service.documentFile(id, escapingId)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void reviewQueueCountsDocuments() {
        Professional p = submitted(CredentialType.PSYCHOLOGIST);
        when(professionals.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(p)));
        when(documents.countByProfessionalIds(any())).thenReturn(List.<Object[]>of(new Object[]{p.getId(), 2L}));
        assertThatThrownBy(() -> service.reviewQueue(0, 0)).isInstanceOf(ApiValidationException.class);
        assertThat(service.reviewQueue(0, 20).items()).singleElement()
                .satisfies(item -> assertThat(item.documentCount()).isEqualTo(2));
    }
}
