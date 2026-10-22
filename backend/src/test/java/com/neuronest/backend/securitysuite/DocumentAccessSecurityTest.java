package com.neuronest.backend.securitysuite;

import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Verification documents. A failure means a document could be read by someone other than the admin,
 * through the wrong professional and document pair, through a stored path that leaves the private
 * storage folder, or through any public or static route; or that the file is served without the
 * headers that keep browsers from caching or reinterpreting it.
 */
class DocumentAccessSecurityTest extends SecuritySuiteBase {

    private static final String FILE_CONTENT = "%PDF-1.7 DOCUMENT-CONTENT-SECRET";
    private static final String OUTSIDE_CONTENT = "%PDF-1.7 OUTSIDE-STORAGE-SECRET";

    private Professional asha;
    private Professional ravi;
    private ProfessionalDocument doc;
    private String relativePath;

    @BeforeEach
    void data() throws Exception {
        asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
        ravi = listed("Dr. Ravi Shah");
        String folder = "dr-asha-rao__" + asha.getId().toString().substring(0, 8);
        relativePath = folder + "/registration-certificate__0a1b2c3d.pdf";
        Path root = SuiteDatabase.storageDir();
        Files.createDirectories(root.resolve(folder));
        Files.writeString(root.resolve(relativePath), FILE_CONTENT, StandardCharsets.US_ASCII);
        doc = documents.saveAndFlush(new ProfessionalDocument(asha.getId(), DocumentType.REGISTRATION_CERTIFICATE,
                relativePath, "certificate.pdf", "application/pdf", FILE_CONTENT.length()));
    }

    private MvcResult fetch(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request).andReturn();
    }

    private String fileUrl(UUID professionalId, UUID documentId) {
        return "/api/admin/professionals/" + professionalId + "/documents/" + documentId + "/file";
    }

    @Test
    void theAdminGetsTheFileWithSafeHeadersOnlyForTheMatchingPair() throws Exception {
        MvcResult ok = fetch(fileUrl(asha.getId(), doc.getId()), SecurityTestTokens.admin());
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).isEqualTo(FILE_CONTENT);
        assertThat(ok.getResponse().getHeader("Content-Disposition")).startsWith("inline");
        assertThat(ok.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(ok.getResponse().getHeaders("Cache-Control")).anyMatch(v -> v.contains("no-store"));

        for (String path : List.of(fileUrl(ravi.getId(), doc.getId()), fileUrl(asha.getId(), UUID.randomUUID()),
                fileUrl(UUID.randomUUID(), doc.getId()))) {
            MvcResult wrong = fetch(path, SecurityTestTokens.admin());
            assertThat(wrong.getResponse().getStatus()).as(path).isEqualTo(404);
            assertThat(wrong.getResponse().getContentAsString()).as(path).doesNotContain("DOCUMENT-CONTENT-SECRET");
        }
    }

    @Test
    void noOneButTheAdminCanFetchTheFile() throws Exception {
        Map<String, String> callers = new LinkedHashMap<>();
        callers.put("no token", null);
        callers.put("garbage token", SecurityTestTokens.GARBAGE);
        callers.put("the professional's own Clerk token", SecurityTestTokens.clerk("pro_asha", "asha@example.com"));
        callers.put("a plain user's Clerk token", SecurityTestTokens.clerk("user_plain", "plain@example.com"));
        callers.put("an admin token with the wrong secret", SecurityTestTokens.adminWrongSecret());
        for (var caller : callers.entrySet()) {
            MvcResult result = fetch(fileUrl(asha.getId(), doc.getId()), caller.getValue());
            assertThat(result.getResponse().getStatus()).as(caller.getKey()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString()).as(caller.getKey()).doesNotContain("DOCUMENT-CONTENT-SECRET");
        }
    }

    @Test
    void aStoredPathOutsideTheStorageFolderIsRefusedAndNeverRead() throws Exception {
        Path outside = SuiteDatabase.storageDir().getParent().resolve("outside-" + UUID.randomUUID() + ".pdf");
        Files.writeString(outside, OUTSIDE_CONTENT, StandardCharsets.US_ASCII);
        try {
            for (String storedPath : List.of("../" + outside.getFileName(), outside.toAbsolutePath().toString(),
                    relativePath.substring(0, relativePath.indexOf('/')) + "/../../" + outside.getFileName())) {
                ProfessionalDocument evil = documents.saveAndFlush(new ProfessionalDocument(asha.getId(), DocumentType.OTHER,
                        storedPath, "evil.pdf", "application/pdf", 10));
                MvcResult result = fetch(fileUrl(asha.getId(), evil.getId()), SecurityTestTokens.admin());
                assertThat(result.getResponse().getStatus()).isEqualTo(404);
                assertThat(result.getResponse().getContentAsString()).doesNotContain("OUTSIDE-STORAGE-SECRET");
                assertCleanErrorBody(result.getResponse().getContentAsString());
            }
            assertThat(Files.readString(outside)).isEqualTo(OUTSIDE_CONTENT);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void noPublicOrStaticRouteServesTheStorageFolder() throws Exception {
        String folder = relativePath.substring(0, relativePath.indexOf('/'));
        String fileName = relativePath.substring(relativePath.indexOf('/') + 1);
        List<String> paths = Arrays.asList("/" + relativePath, "/" + folder, "/" + fileName, "/uploads/" + relativePath,
                "/uploads/" + fileName, "/files/" + relativePath, "/files/" + fileName, "/static/" + relativePath,
                "/storage/" + relativePath, "/documents/" + relativePath);
        for (String path : paths) {
            for (String token : new String[]{null, SecurityTestTokens.admin()}) {
                MvcResult result = fetch(path, token);
                assertThat(result.getResponse().getStatus()).as(path).isNotEqualTo(200);
                assertThat(result.getResponse().getContentAsString()).as(path).doesNotContain("DOCUMENT-CONTENT-SECRET");
            }
        }
    }
}
