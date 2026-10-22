package com.neuronest.backend.pro;

import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import com.neuronest.backend.professional.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentNamingTest {

    private static final byte[] PDF = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private static final UUID ID = UUID.fromString("3f9a0c1b-2d4e-4f60-8a7b-9c0d1e2f3a4b");

    // ---- slug ----

    @Test
    void theSlugKeepsOnlyLowercaseLettersDigitsAndSingleHyphens() {
        assertThat(DocumentPaths.slug("Dr. Asha Rao")).isEqualTo("dr-asha-rao");
        assertThat(DocumentPaths.slug("  Ravi   Kumar  ")).isEqualTo("ravi-kumar");
        assertThat(DocumentPaths.slug("A.B.  C--D__E")).isEqualTo("a-b-c-d-e");
        assertThat(DocumentPaths.slug("MiXeD CaSe 42")).isEqualTo("mixed-case-42");
        assertThat(DocumentPaths.slug("José Pérez")).isEqualTo("jose-perez");
        assertThat(DocumentPaths.slug("../../etc/passwd")).isEqualTo("etc-passwd");
    }

    @Test
    void aNameWithoutLatinLettersOrDigitsOrAnEmptyNameBecomesProfessional() {
        assertThat(DocumentPaths.slug("आशा राव")).isEqualTo("professional");
        assertThat(DocumentPaths.slug("")).isEqualTo("professional");
        assertThat(DocumentPaths.slug("   ")).isEqualTo("professional");
        assertThat(DocumentPaths.slug("...---...")).isEqualTo("professional");
        assertThat(DocumentPaths.slug(null)).isEqualTo("professional");
        // Mixed scripts keep the Latin part.
        assertThat(DocumentPaths.slug("डॉ. Asha")).isEqualTo("asha");
    }

    @Test
    void aVeryLongNameIsCutToFortyCharactersWithoutATrailingHyphen() {
        String slug = DocumentPaths.slug("Dr. " + "Abcdefghij ".repeat(10));
        assertThat(slug).hasSizeLessThanOrEqualTo(40).doesNotEndWith("-").startsWith("dr-abcdefghij-");
        // A cut that would land just after a separator drops the hyphen.
        assertThat(DocumentPaths.slug("a".repeat(39) + " bbbb")).isEqualTo("a".repeat(39));
    }

    @Test
    void theFolderAndFileNamesFollowThePattern() {
        assertThat(DocumentPaths.newFolder("Dr. Asha Rao", ID)).isEqualTo("dr-asha-rao__3f9a0c1b");
        assertThat(DocumentPaths.newFolder("आशा", ID)).isEqualTo("professional__3f9a0c1b");
        assertThat(DocumentPaths.newFileName(DocumentType.REGISTRATION_CERTIFICATE, FileSignature.PDF))
                .matches("registration-certificate__[0-9a-f]{8}\\.pdf");
        assertThat(DocumentPaths.newFileName(DocumentType.ID_PROOF, FileSignature.JPEG)).matches("id-proof__[0-9a-f]{8}\\.jpg");
        assertThat(DocumentPaths.newFileName(DocumentType.OTHER, FileSignature.PNG)).matches("other__[0-9a-f]{8}\\.png");
    }

    @Test
    void onlyASafeFolderOfAnExistingPathIsReused() {
        assertThat(DocumentPaths.folderOf("dr-asha-rao__3f9a0c1b/id-proof__0a1b2c3d.jpg")).contains("dr-asha-rao__3f9a0c1b");
        assertThat(DocumentPaths.folderOf(ID + "/" + UUID.randomUUID() + ".pdf")).contains(ID.toString());
        assertThat(DocumentPaths.folderOf("../x/file.pdf")).isEmpty();
        assertThat(DocumentPaths.folderOf("file.pdf")).isEmpty();
        assertThat(DocumentPaths.folderOf("/abs/file.pdf")).isEmpty();
        assertThat(DocumentPaths.folderOf(null)).isEmpty();
    }

    // ---- storage ----

    @Test
    void storageRefusesAnUnsafeFolderAndKeepsEveryPathInsideTheRoot(@TempDir Path dir) {
        ProDocumentStorage storage = new ProDocumentStorage(dir.toString());
        for (String folder : new String[]{"..", "../escape__3f9a0c1b", "a/b", "", "Dr Asha__3f9a0c1b"}) {
            assertThatThrownBy(() -> storage.store(folder, DocumentType.OTHER, PDF, FileSignature.PDF))
                    .as(folder).isInstanceOf(IllegalStateException.class);
        }
        String relative = storage.store("dr-asha-rao__3f9a0c1b", DocumentType.OTHER, PDF, FileSignature.PDF);
        assertThat(relative).matches("dr-asha-rao__3f9a0c1b/other__[0-9a-f]{8}\\.pdf").doesNotContain("\\");
        assertThat(dir.resolve(relative)).exists();
    }

    @Test
    void anOldStyleUuidPathStillResolvesForTheAdminDownload(@TempDir Path dir) throws Exception {
        ProDocumentStorage storage = new ProDocumentStorage(dir.toString());
        String oldPath = ID + "/" + UUID.randomUUID() + ".pdf";
        Files.createDirectories(dir.resolve(ID.toString()));
        Files.write(dir.resolve(oldPath), PDF);

        assertThat(storage.existingFile(oldPath)).hasValueSatisfying(p -> assertThat(p).startsWith(dir.toAbsolutePath().normalize()));
        assertThatThrownBy(() -> storage.existingFile("../" + oldPath)).isInstanceOf(IllegalStateException.class);
    }

    // ---- uploads through the service ----

    private final ProProfileService profiles = mock(ProProfileService.class);
    private final ProfessionalDocumentRepository documents = mock(ProfessionalDocumentRepository.class);
    private final List<ProfessionalDocument> saved = new ArrayList<>();
    private final ProPrincipal principal = new ProPrincipal("pro_clerk");
    private Professional professional;

    @BeforeEach
    void setUp() {
        professional = new Professional();
        ReflectionTestUtils.setField(professional, "id", ID);
        professional.setFullName("Dr. Asha Rao");
        professional.setCredentialType(CredentialType.PSYCHOLOGIST);
        professional.setVerificationStatus(VerificationStatus.DRAFT);
        ProfessionalAccount account = new ProfessionalAccount(ID, "asha@example.com", "pro_clerk");
        when(profiles.account(principal)).thenReturn(account);
        when(profiles.professional(account)).thenReturn(professional);
        when(documents.findByProfessionalIdOrderByUploadedAtAsc(ID)).thenAnswer(inv -> List.copyOf(saved));
        when(documents.saveAndFlush(any())).thenAnswer(inv -> {
            ProfessionalDocument d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
            saved.add(d);
            return d;
        });
    }

    // The upload registers a clean-up for a rolled-back transaction, so it runs with synchronization on,
    // as it does inside its @Transactional method.
    private String upload(ProDocumentService service, DocumentType type, String filename, byte[] content) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(principal, type.name(), new MockMultipartFile("file", filename, "application/octet-stream", content));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        return saved.get(saved.size() - 1).getStoragePath();
    }

    @Test
    void aSecondUploadReusesTheFirstFolderEvenAfterANameChange(@TempDir Path dir) {
        ProDocumentService service = new ProDocumentService(profiles, documents, new ProDocumentStorage(dir.toString()));

        String first = upload(service, DocumentType.REGISTRATION_CERTIFICATE, "certificate.pdf", PDF);
        professional.setFullName("Dr. Asha Rao-Menon");
        String second = upload(service, DocumentType.ID_PROOF, "aadhaar.jpg", JPEG);

        assertThat(first).matches("dr-asha-rao__3f9a0c1b/registration-certificate__[0-9a-f]{8}\\.pdf");
        assertThat(second).matches("dr-asha-rao__3f9a0c1b/id-proof__[0-9a-f]{8}\\.jpg");
        assertThat(dir.resolve(first)).exists();
        assertThat(dir.resolve(second)).exists();
        // The original name is kept only for display.
        assertThat(saved.get(0).getOriginalFilename()).isEqualTo("certificate.pdf");
    }

    @Test
    void anOldStyleFirstDocumentKeepsLaterUploadsInItsFolder(@TempDir Path dir) {
        ProfessionalDocument old = new ProfessionalDocument(ID, DocumentType.OTHER, ID + "/" + UUID.randomUUID() + ".pdf",
                "old.pdf", "application/pdf", PDF.length);
        saved.add(old);
        ProDocumentService service = new ProDocumentService(profiles, documents, new ProDocumentStorage(dir.toString()));

        assertThat(upload(service, DocumentType.ID_PROOF, "id.pdf", PDF)).matches(ID + "/id-proof__[0-9a-f]{8}\\.pdf");
    }

    @Test
    void theExtensionFollowsTheDetectedTypeNotTheUploadName(@TempDir Path dir) {
        ProDocumentService service = new ProDocumentService(profiles, documents, new ProDocumentStorage(dir.toString()));

        assertThat(upload(service, DocumentType.ID_PROOF, "Photo.JPEG", JPEG)).endsWith(".jpg");
        assertThat(upload(service, DocumentType.OTHER, "scan", PDF)).endsWith(".pdf");
        // A name that disagrees with the contents is still refused, as before.
        assertThatThrownBy(() -> upload(service, DocumentType.OTHER, "scan.png", PDF))
                .hasMessageContaining("does not match its file name extension");
    }

    @Test
    void anUploadNamedToClimbOutOfTheFolderStaysInside(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("storage");
        ProDocumentService service = new ProDocumentService(profiles, documents, new ProDocumentStorage(root.toString()));

        String path = upload(service, DocumentType.OTHER, "../../x.pdf", PDF);

        assertThat(path).matches("dr-asha-rao__3f9a0c1b/other__[0-9a-f]{8}\\.pdf").doesNotContain("..", "x.pdf");
        assertThat(root.resolve(path).toRealPath()).startsWith(root.toRealPath());
        assertThat(Files.exists(dir.resolve("x.pdf"))).isFalse();
        try (var outside = Files.list(dir)) {
            assertThat(outside.map(p -> p.getFileName().toString())).containsExactly("storage");
        }
        ArgumentCaptor<ProfessionalDocument> document = ArgumentCaptor.forClass(ProfessionalDocument.class);
        verify(documents).saveAndFlush(document.capture());
        assertThat(document.getValue().getOriginalFilename()).isEqualTo("x.pdf");
    }
}
