package com.neuronest.backend.pro;

import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The rename's two queries against a real PostgreSQL (tables built from the entities). Skipped when
// Docker is not available.
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class DocumentRenameRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ProfessionalDocumentRepository documents;
    @Autowired
    private EntityManager entityManager;

    @Test
    void theUpdateChangesOnlyARowThatStillHasTheOldPath() {
        UUID professional = UUID.randomUUID();
        String oldPath = professional + "/" + UUID.randomUUID() + ".pdf";
        ProfessionalDocument doc = documents.saveAndFlush(
                new ProfessionalDocument(professional, DocumentType.OTHER, oldPath, "a.pdf", "application/pdf", 10));
        documents.saveAndFlush(new ProfessionalDocument(professional, DocumentType.ID_PROOF,
                professional + "/" + UUID.randomUUID() + ".jpg", "b.jpg", "image/jpeg", 10));
        documents.saveAndFlush(new ProfessionalDocument(UUID.randomUUID(), DocumentType.OTHER, "x/y.pdf", "c.pdf",
                "application/pdf", 10));

        assertThat(documents.findProfessionalIdsWithDocuments()).hasSize(2).contains(professional);
        assertThat(documents.updateStoragePath(doc.getId(), "not/the-old-path.pdf", "new/path.pdf")).isZero();
        assertThat(documents.updateStoragePath(doc.getId(), oldPath, "dr-asha-rao__12345678/other__0a0b0c0d.pdf")).isEqualTo(1);
        entityManager.clear();
        assertThat(documents.findById(doc.getId())).get().extracting(ProfessionalDocument::getStoragePath)
                .isEqualTo("dr-asha-rao__12345678/other__0a0b0c0d.pdf");
    }
}
