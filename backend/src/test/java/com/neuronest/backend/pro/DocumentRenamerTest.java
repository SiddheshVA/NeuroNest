package com.neuronest.backend.pro;

import com.neuronest.backend.pro.DocumentRenamer.Mode;
import com.neuronest.backend.pro.DocumentRenamer.Result;
import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// The one-time rename against a temporary APP_STORAGE_DIR and an in-memory documents table.
class DocumentRenamerTest {

    private static final byte[] PDF = "%PDF-1.7 old".getBytes(StandardCharsets.US_ASCII);
    private static final UUID ASHA = UUID.fromString("3f9a0c1b-2d4e-4f60-8a7b-9c0d1e2f3a4b");

    @TempDir
    Path dir;

    private Path root;
    private ProDocumentStorage storage;
    private final ProfessionalDocumentRepository documents = mock(ProfessionalDocumentRepository.class);
    private final ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    // The documents table: id -> row.
    private final Map<UUID, ProfessionalDocument> rows = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        root = dir.resolve("storage");
        storage = new ProDocumentStorage(root.toString());
        professional(ASHA, "Dr. Asha Rao");
        when(documents.findProfessionalIdsWithDocuments()).thenAnswer(inv ->
                rows.values().stream().map(ProfessionalDocument::getProfessionalId).distinct().toList());
        when(documents.findByProfessionalIdOrderByUploadedAtAsc(any())).thenAnswer(inv -> rows.values().stream()
                .filter(d -> d.getProfessionalId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(ProfessionalDocument::getUploadedAt)).toList());
        when(documents.updateStoragePath(any(), anyString(), anyString())).thenAnswer(inv -> {
            ProfessionalDocument d = rows.get((UUID) inv.getArgument(0));
            if (d == null || !d.getStoragePath().equals(inv.getArgument(1))) return 0;
            ReflectionTestUtils.setField(d, "storagePath", inv.getArgument(2));
            return 1;
        });
    }

    private DocumentRenamer renamer() {
        return new DocumentRenamer(documents, professionals, storage, TransactionOperations.withoutTransaction());
    }

    private void professional(UUID id, String name) {
        Professional p = new Professional();
        ReflectionTestUtils.setField(p, "id", id);
        p.setFullName(name);
        when(professionals.findById(id)).thenReturn(Optional.of(p));
    }

    /** An older document: a row, and (when onDisk) its file under the old UUID folder. */
    private ProfessionalDocument oldDocument(UUID professionalId, DocumentType type, String extension, boolean onDisk)
            throws Exception {
        String path = professionalId + "/" + UUID.randomUUID() + "." + extension;
        ProfessionalDocument d = new ProfessionalDocument(professionalId, type, path, "PRIVATE-original-name." + extension,
                "application/pdf", PDF.length);
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(d, "uploadedAt", java.time.Instant.now().plusMillis(rows.size()));
        rows.put(d.getId(), d);
        if (onDisk) {
            Files.createDirectories(root.resolve(professionalId.toString()));
            Files.write(root.resolve(path), PDF);
        }
        return d;
    }

    private List<String> filesOnDisk() throws Exception {
        List<String> files = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> files.add(root.relativize(p).toString().replace('\\', '/')));
        }
        return files;
    }

    @Test
    void anOldDocumentIsMovedItsRowUpdatedAndTheAdminDownloadStillFindsIt() throws Exception {
        ProfessionalDocument cert = oldDocument(ASHA, DocumentType.REGISTRATION_CERTIFICATE, "pdf", true);
        ProfessionalDocument id = oldDocument(ASHA, DocumentType.ID_PROOF, "jpg", true);

        Result result = renamer().run(Mode.APPLY);

        assertThat(result.moved()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.oldFoldersRemoved()).isEqualTo(1);
        assertThat(cert.getStoragePath()).matches("dr-asha-rao__3f9a0c1b/registration-certificate__[0-9a-f]{8}\\.pdf");
        assertThat(id.getStoragePath()).matches("dr-asha-rao__3f9a0c1b/id-proof__[0-9a-f]{8}\\.jpg");
        // The admin download reads the stored path through existingFile: it finds the moved file.
        assertThat(storage.existingFile(cert.getStoragePath())).hasValueSatisfying(p -> assertThat(p).hasBinaryContent(PDF));
        assertThat(filesOnDisk()).containsExactlyInAnyOrder(cert.getStoragePath(), id.getStoragePath());
        assertThat(Files.exists(root.resolve(ASHA.toString()))).isFalse();
        // The display name is not touched.
        assertThat(cert.getOriginalFilename()).isEqualTo("PRIVATE-original-name.pdf");
    }

    @Test
    void aSecondRunMovesNothing() throws Exception {
        oldDocument(ASHA, DocumentType.OTHER, "pdf", true);
        renamer().run(Mode.APPLY);
        List<String> afterFirst = filesOnDisk();

        Result second = renamer().run(Mode.APPLY);

        assertThat(second.moved()).isZero();
        assertThat(second.alreadyNew()).isEqualTo(1);
        assertThat(second.failed()).isZero();
        assertThat(filesOnDisk()).isEqualTo(afterFirst);
    }

    @Test
    void aMissingFileIsLeftAloneWithItsRowUnchanged() throws Exception {
        ProfessionalDocument missing = oldDocument(ASHA, DocumentType.OTHER, "pdf", false);
        String before = missing.getStoragePath();

        Result result = renamer().run(Mode.APPLY);

        assertThat(result.missingHere()).isEqualTo(1);
        assertThat(result.moved()).isZero();
        assertThat(missing.getStoragePath()).isEqualTo(before);
        verify(documents, never()).updateStoragePath(any(), anyString(), anyString());
    }

    @Test
    void aFailingDatabaseUpdateMovesTheFileBack() throws Exception {
        ProfessionalDocument doc = oldDocument(ASHA, DocumentType.OTHER, "pdf", true);
        String before = doc.getStoragePath();
        when(documents.updateStoragePath(any(), anyString(), anyString())).thenThrow(new IllegalStateException("database down"));

        Result result = renamer().run(Mode.APPLY);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.moved()).isZero();
        assertThat(doc.getStoragePath()).isEqualTo(before);
        assertThat(filesOnDisk()).containsExactly(before);
        assertThat(root.resolve(before)).hasBinaryContent(PDF);
        // The old folder still holds the file, so it is kept.
        assertThat(result.oldFoldersRemoved()).isZero();

        // An update that changes no row (the path changed meanwhile) is treated the same way.
        doReturn(0).when(documents).updateStoragePath(any(), anyString(), anyString());
        assertThat(renamer().run(Mode.APPLY).failed()).isEqualTo(1);
        assertThat(filesOnDisk()).containsExactly(before);
    }

    @Test
    void anExistingTargetIsNeverOverwritten() throws Exception {
        ProfessionalDocument doc = oldDocument(ASHA, DocumentType.OTHER, "pdf", true);
        String before = doc.getStoragePath();
        // Every target name the renamer could pick is reported as taken.
        ProDocumentStorage spy = spy(storage);
        doAnswer(inv -> ((String) inv.getArgument(0)).startsWith("dr-asha-rao__") || (boolean) inv.callRealMethod())
                .when(spy).exists(anyString());

        Result result = new DocumentRenamer(documents, professionals, spy, TransactionOperations.withoutTransaction())
                .run(Mode.APPLY);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.moved()).isZero();
        assertThat(doc.getStoragePath()).isEqualTo(before);
        assertThat(filesOnDisk()).containsExactly(before);

        // The storage move itself refuses an existing target and leaves both files as they were.
        Files.createDirectories(root.resolve("dr-asha-rao__3f9a0c1b"));
        Files.writeString(root.resolve("dr-asha-rao__3f9a0c1b/other__00000000.pdf"), "already here");
        assertThatThrownBy(() -> storage.move(before, "dr-asha-rao__3f9a0c1b/other__00000000.pdf"))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(root.resolve("dr-asha-rao__3f9a0c1b/other__00000000.pdf")).hasContent("already here");
        assertThat(root.resolve(before)).hasBinaryContent(PDF);
    }

    @Test
    void aNameInDevanagariUsesProfessional() throws Exception {
        UUID id = UUID.fromString("7a1b2c3d-0000-4000-8000-000000000001");
        professional(id, "आशा राव");
        ProfessionalDocument doc = oldDocument(id, DocumentType.DEGREE_CERTIFICATE, "png", true);

        renamer().run(Mode.APPLY);

        assertThat(doc.getStoragePath()).matches("professional__7a1b2c3d/degree-certificate__[0-9a-f]{8}\\.png");
    }

    @Test
    void allOfAProfessionalsDocumentsGoIntoTheirExistingNewStyleFolder() throws Exception {
        // A newer upload already lives in a folder made from an earlier name.
        String existingNew = "dr-asha-old__3f9a0c1b/other__0a0b0c0d.pdf";
        Files.createDirectories(root.resolve("dr-asha-old__3f9a0c1b"));
        Files.write(root.resolve(existingNew), PDF);
        ProfessionalDocument newer = new ProfessionalDocument(ASHA, DocumentType.OTHER, existingNew, "n.pdf", "application/pdf", 1);
        ReflectionTestUtils.setField(newer, "id", UUID.randomUUID());
        rows.put(newer.getId(), newer);
        ProfessionalDocument older = oldDocument(ASHA, DocumentType.ID_PROOF, "pdf", true);

        Result result = renamer().run(Mode.APPLY);

        assertThat(result.alreadyNew()).isEqualTo(1);
        assertThat(result.moved()).isEqualTo(1);
        assertThat(older.getStoragePath()).startsWith("dr-asha-old__3f9a0c1b/id-proof__");
        assertThat(newer.getStoragePath()).isEqualTo(existingNew);
    }

    @Test
    void aDryRunChangesNothingOnDiskOrInTheDatabase() throws Exception {
        ProfessionalDocument a = oldDocument(ASHA, DocumentType.OTHER, "pdf", true);
        ProfessionalDocument b = oldDocument(ASHA, DocumentType.ID_PROOF, "jpg", false);
        String pathA = a.getStoragePath();
        List<String> before = filesOnDisk();

        Result result = renamer().run(Mode.DRY);

        assertThat(result.examined()).isEqualTo(2);
        assertThat(result.moved()).isEqualTo(1);
        assertThat(result.missingHere()).isEqualTo(1);
        assertThat(result.oldFoldersRemoved()).isZero();
        assertThat(filesOnDisk()).isEqualTo(before);
        assertThat(a.getStoragePath()).isEqualTo(pathA);
        assertThat(b.getStoragePath()).doesNotContain("__");
        verify(documents, never()).updateStoragePath(any(), anyString(), anyString());
    }

    @Test
    void aStoredPathThatTriesToEscapeTheStorageDirectoryIsRefused() throws Exception {
        Path outside = Files.writeString(dir.resolve("outside.pdf"), "%PDF-1.7 outside");
        ProfessionalDocument escape = new ProfessionalDocument(ASHA, DocumentType.OTHER, "../outside.pdf", "x.pdf",
                "application/pdf", 1);
        ReflectionTestUtils.setField(escape, "id", UUID.randomUUID());
        rows.put(escape.getId(), escape);

        Result result = renamer().run(Mode.APPLY);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.moved()).isZero();
        assertThat(escape.getStoragePath()).isEqualTo("../outside.pdf");
        assertThat(outside).hasContent("%PDF-1.7 outside");
        assertThat(filesOnDisk()).isEmpty();
        assertThatThrownBy(() -> storage.move("../outside.pdf", "dr-asha-rao__3f9a0c1b/other__00000000.pdf"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.deleteFolderIfEmpty("..")).isFalse();
    }
}
