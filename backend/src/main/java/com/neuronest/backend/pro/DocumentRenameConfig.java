package com.neuronest.backend.pro;

import com.neuronest.backend.professional.ProfessionalDocumentRepository;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

// The one-time rename of older documents (DocumentRenamer). Nothing is registered unless
// RENAME_DOCUMENTS (rename.documents) is "dry" or "apply". It runs once after the app is ready; a failure
// is logged and the app keeps running. There is no web endpoint for it.
@Configuration(proxyBeanMethods = false)
public class DocumentRenameConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentRenameConfig.class);

    @Bean
    @ConditionalOnExpression("{'dry', 'apply'}.contains('${rename.documents:}'.trim().toLowerCase())")
    public ApplicationListener<ApplicationReadyEvent> documentRenameRunner(
            @Value("${rename.documents:}") String setting, ProfessionalDocumentRepository documents,
            ProfessionalRepository professionals, ProDocumentStorage storage, PlatformTransactionManager transactionManager) {
        DocumentRenamer.Mode mode = "apply".equals(setting.trim().toLowerCase(Locale.ROOT))
                ? DocumentRenamer.Mode.APPLY : DocumentRenamer.Mode.DRY;
        DocumentRenamer renamer = new DocumentRenamer(documents, professionals, storage,
                new TransactionTemplate(transactionManager));
        return event -> {
            try {
                renamer.run(mode);
            } catch (RuntimeException e) {
                log.error("Document rename stopped early: {}", e.getClass().getName());
            }
        };
    }
}
