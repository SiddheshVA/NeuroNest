package com.neuronest.backend.pro;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProDocumentsTest {

    @Test
    void detectsRealSignaturesOnly() {
        assertThat(FileSignature.detect("%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII))).contains(FileSignature.PDF);
        assertThat(FileSignature.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0})).contains(FileSignature.PNG);
        assertThat(FileSignature.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})).contains(FileSignature.JPEG);
        assertThat(FileSignature.detect("this is just text pretending to be a PDF".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(FileSignature.detect("<html>%PDF-".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(FileSignature.detect(new byte[0])).isEmpty();
    }

    @Test
    void extensionMustAgreeWithContents() {
        assertThat(FileSignature.PDF.matchesExtensionOf("cert.PDF")).isTrue();
        assertThat(FileSignature.JPEG.matchesExtensionOf("photo.jpeg")).isTrue();
        assertThat(FileSignature.PNG.matchesExtensionOf("scan")).isTrue();
        assertThat(FileSignature.PNG.matchesExtensionOf("scan.pdf")).isFalse();
    }

    @Test
    void filenamesAreReducedToADisplayName() {
        assertThat(ProDocumentService.cleanFilename("..\\..\\windows\\evil.pdf")).isEqualTo("evil.pdf");
        assertThat(ProDocumentService.cleanFilename("/etc/passwd")).isEqualTo("passwd");
        assertThat(ProDocumentService.cleanFilename("a\u0000b\n.pdf")).isEqualTo("ab.pdf");
        assertThat(ProDocumentService.cleanFilename("../")).isNull();
        assertThat(ProDocumentService.cleanFilename("x".repeat(300) + ".pdf")).hasSize(255).endsWith(".pdf");
    }

    @Test
    void storageUsesServerSideNamesInsideTheRoot(@TempDir Path dir) throws Exception {
        ProDocumentStorage storage = new ProDocumentStorage(dir.toString());
        UUID professionalId = UUID.randomUUID();

        String relative = storage.store(professionalId, "%PDF-1.7".getBytes(), FileSignature.PDF);

        assertThat(relative).matches(professionalId + "/[0-9a-f-]{36}\\.pdf");
        assertThat(Files.readString(dir.resolve(relative))).isEqualTo("%PDF-1.7");
        storage.delete(relative);
        assertThat(Files.exists(dir.resolve(relative))).isFalse();
        storage.delete("../outside.txt"); // refused and logged, never touches anything outside the root
    }

    @Test
    void storageRootMustBeSetAndWritable(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> ProDocumentStorage.checkWritableRoot(" "))
                .hasMessageStartingWith("APP_STORAGE_DIR is not set.");
        Path file = Files.writeString(dir.resolve("not-a-dir"), "x");
        assertThatThrownBy(() -> ProDocumentStorage.checkWritableRoot(file.toString()))
                .hasMessageContaining("cannot be created, or is not writable");
        assertThat(ProDocumentStorage.checkWritableRoot(dir.resolve("new/sub").toString())).exists();
    }

    @Test
    void clerkPrimaryEmailMustBeTheVerifiedPrimaryOne() {
        JsonMapper json = JsonMapper.builder().build();
        String user = """
                {"primary_email_address_id":"idn_2",
                 "email_addresses":[
                   {"id":"idn_1","email_address":"old@example.com","verification":{"status":"verified"}},
                   {"id":"idn_2","email_address":"new@example.com","verification":{"status":"unverified"}}]}""";
        assertThat(ClerkBackendClient.parsePrimaryEmail(json.readTree(user)))
                .contains(new ClerkBackendClient.PrimaryEmail("new@example.com", false));
        assertThat(ClerkBackendClient.parsePrimaryEmail(json.readTree(user.replace("\"unverified\"", "\"verified\""))))
                .contains(new ClerkBackendClient.PrimaryEmail("new@example.com", true));
        assertThat(ClerkBackendClient.parsePrimaryEmail(json.readTree("{\"email_addresses\":[]}"))).isEmpty();
    }

    @Test
    void clerkClientRequiresASecretKey() {
        JsonMapper json = JsonMapper.builder().build();
        assertThatThrownBy(() -> new ClerkBackendClient("", "https://api.clerk.com/v1", json))
                .hasMessageStartingWith("CLERK_SECRET_KEY is not set.");
        assertThatThrownBy(() -> new ClerkBackendClient("pk_test_abc", "https://api.clerk.com/v1", json))
                .hasMessageContaining("should start with sk_");
    }

    @Test
    void registrationLimiterAllowsFivePerHourPerIp() {
        Instant start = Instant.parse("2026-10-12T10:00:00Z");
        Clock[] clock = {Clock.fixed(start, ZoneOffset.UTC)};
        ProRegistrationRateLimiter limiter = new ProRegistrationRateLimiter(new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return clock[0].instant(); }
        });
        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire("ip")).isEmpty();
        assertThat(limiter.tryAcquire("ip")).contains(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("other")).isEmpty();
        clock[0] = Clock.fixed(start.plus(Duration.ofHours(1)), ZoneOffset.UTC);
        assertThat(limiter.tryAcquire("ip")).isEmpty();
    }
}
