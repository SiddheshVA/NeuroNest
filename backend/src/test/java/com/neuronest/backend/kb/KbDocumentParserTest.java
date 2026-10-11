package com.neuronest.backend.kb;

import com.neuronest.backend.kb.KbChunk.SectionKind;
import com.neuronest.backend.kb.KbDocumentParser.KbDocument;
import com.neuronest.backend.kb.KbDocumentParser.KbSection;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class KbDocumentParserTest {

    private static final String DOC = """
            ---
            id: anxiety-anxious-moment
            title: Coping in an anxious moment
            topic: anxiety
            tags: [anxiety, quick-relief]
            doc_type: technique
            safety_level: medium
            source_name: NIMH; NCCIH
            version: 2
            ---

            # Coping in an anxious moment

            ## Overview
            When anxiety rises quickly, the body can feel like it is in **alarm mode**.

            ## What you can try
            1. **Pause and name it.** Say "I am feeling anxious".
            2. Call a helpline such as Tele MANAS (14416 in India) if you want to talk.

            ## Getting urgent help
            Contact emergency services right away.

            ## Safety and limitations
            These skills do not treat an anxiety disorder.

            ## When professional support may help
            If it happens often, talk with a doctor. Or call 1-800-891-4416.

            ## Source
            Adapted from NIMH (public domain).
            """;

    @Test
    void chunksByH2SectionWithTitlePrefixAndKeepsSourceAsMetadataOnly() {
        KbDocument doc = KbDocumentParser.parse(DOC).orElseThrow();

        assertThat(doc.docId()).isEqualTo("anxiety-anxious-moment");
        assertThat(doc.version()).isEqualTo("2");
        assertThat(doc.safetyLevel()).isEqualTo("medium");
        assertThat(doc.sourceName()).isEqualTo("NIMH; NCCIH");
        assertThat(doc.retrievable()).isTrue();
        // No Source (metadata), no urgent-help section, and the section with a bare phone number is dropped.
        assertThat(doc.sections()).extracting(KbSection::name).containsExactly("Overview", "What you can try",
                "Safety and limitations");
        assertThat(doc.sections()).extracting(KbSection::kind).containsExactly(SectionKind.OVERVIEW,
                SectionKind.PRACTICAL, SectionKind.SAFETY);
        assertThat(doc.sections().getFirst().content()).isEqualTo("""
                Coping in an anxious moment - Overview
                When anxiety rises quickly, the body can feel like it is in alarm mode.""");
    }

    @Test
    void phoneNumbersNeverReachAChunk() {
        KbDocument doc = KbDocumentParser.parse(DOC).orElseThrow();

        // A bracketed number is cut cleanly...
        assertThat(doc.sections().get(1).content())
                .contains("Call a helpline such as Tele MANAS if you want to talk.").doesNotContain("14416");
        // ...any other number drops the whole section rather than leaving a broken sentence.
        assertThat(doc.sections()).extracting(KbSection::name).doesNotContain("When professional support may help");
        KbDocument bare = KbDocumentParser.parse(
                DOC.replace("These skills do not", "Dial 112 if these skills do not")).orElseThrow();
        assertThat(bare.sections()).extracting(KbSection::name).doesNotContain("Safety and limitations");
    }

    @Test
    void filesWithoutFrontMatterAndCrisisAdjacentDocsAreNotRetrievable() {
        assertThat(KbDocumentParser.parse("# NeuroNest RAG Knowledge Base\n\n## Folders\n")).isEmpty();
        assertThat(KbDocumentParser.parse(DOC.replace("safety_level: medium", "safety_level: crisis-adjacent"))
                .orElseThrow().retrievable()).isFalse();
    }

    @Test
    void sectionKinds() {
        assertThat(KbDocumentParser.kindOf("How to do it (about 10 minutes)")).isEqualTo(SectionKind.PRACTICAL);
        assertThat(KbDocumentParser.kindOf("Tips for practice")).isEqualTo(SectionKind.PRACTICAL);
        assertThat(KbDocumentParser.kindOf("Cautions")).isEqualTo(SectionKind.SAFETY);
        assertThat(KbDocumentParser.kindOf("Key points from the research")).isEqualTo(SectionKind.OTHER);
    }

    /** The real library: every doc parses, and no retrievable chunk carries a phone number. */
    @Test
    void theShippedKnowledgeBaseIsSafeToRetrieve() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:kb/**/*.md");
        List<KbDocument> docs = new ArrayList<>();
        for (Resource file : files) {
            KbDocumentParser.parse(file.getContentAsString(StandardCharsets.UTF_8)).ifPresent(docs::add);
        }

        assertThat(files).hasSize(38); // 37 docs + README
        assertThat(docs).hasSize(37);
        List<KbDocument> retrievable = docs.stream().filter(KbDocument::retrievable).toList();
        assertThat(retrievable).hasSize(36)
                .noneMatch(d -> d.docId().equals("support-services-india"));
        Pattern number = Pattern.compile("\\d{3,}");
        assertThat(retrievable).allSatisfy(doc -> {
            assertThat(doc.sections()).isNotEmpty();
            assertThat(doc.sections()).extracting(KbSection::kind).contains(SectionKind.OVERVIEW);
            assertThat(doc.sections()).allSatisfy(section -> {
                assertThat(section.name()).isNotEqualTo("Source");
                assertThat(number.matcher(section.content()).find())
                        .as("number in %s#%s", doc.docId(), section.name()).isFalse();
            });
        });
    }
}
