package com.neuronest.backend.pro;

import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DocumentRenameConfigTest {

    private final ProfessionalDocumentRepository documents = mock(ProfessionalDocumentRepository.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DocumentRenameConfig.class)
            .withBean(ProfessionalDocumentRepository.class, () -> documents)
            .withBean(ProfessionalRepository.class, () -> mock(ProfessionalRepository.class))
            .withBean(ProDocumentStorage.class, () -> mock(ProDocumentStorage.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void onlyDryOrApplyRegistersTheRunner() {
        runner.run(context -> assertThat(context).doesNotHaveBean("documentRenameRunner"));
        for (String off : new String[]{"", "off", "yes", "true", "applied"}) {
            runner.withPropertyValues("rename.documents=" + off)
                    .run(context -> assertThat(context).doesNotHaveBean("documentRenameRunner"));
        }
        for (String on : new String[]{"dry", "apply", " APPLY "}) {
            runner.withPropertyValues("rename.documents=" + on)
                    .run(context -> assertThat(context).hasBean("documentRenameRunner"));
        }
        // Registering it does not run it: it waits for the application to be ready.
        verifyNoInteractions(documents);
    }
}
