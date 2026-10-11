package com.neuronest.backend.kb;

import com.neuronest.backend.kb.KbChunk.SectionKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns one knowledge-base Markdown file (YAML front matter + H2 sections) into retrievable
 * chunks: one per H2 section, prefixed with "Title - Section". The Source section is kept as
 * metadata only, and helpline/phone numbers never reach a chunk (replies must not contain them).
 */
final class KbDocumentParser {

    /** Bump when chunking or cleaning changes, so every doc is re-embedded once. */
    static final int CHUNKER_REVISION = 1;

    record KbSection(String name, SectionKind kind, String content) {}

    record KbDocument(String docId, String title, String topic, String docType, String safetyLevel,
                      String sourceName, String version, List<KbSection> sections) {

        /** Crisis-adjacent docs (helpline directories) stay application-controlled: never retrieved. */
        boolean retrievable() {
            return !"crisis-adjacent".equals(safetyLevel);
        }
    }

    /** Sections that are never chunked: attribution (kept as metadata) and emergency instructions. */
    private static final Set<String> EXCLUDED_SECTIONS = Set.of("source", "getting urgent help", "in an emergency");

    private static final List<String> PRACTICAL_PREFIXES = List.of("what you can try", "how to do it", "tips",
            "making it easier", "practical guidance", "what this means in practice", "a simple", "if you",
            "after ", "getting the most", "before the appointment", "during the appointment", "how to take");

    // "(14416 in India)", "(112 in India)": a bracketed aside carrying a long number reads fine without it.
    private static final Pattern BRACKETED_NUMBER = Pattern.compile("\\s*\\([^()]*\\d{3,}[^()]*\\)");
    // A section still carrying 3+ consecutive digits after that (e.g. "call 1-800-891-4416") is
    // dropped, not patched: cutting a number out of a sentence would leave it broken.
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{3,}");

    private KbDocumentParser() {}

    /** Empty when the file has no front matter or no id (e.g. the folder's README). */
    static Optional<KbDocument> parse(String markdown) {
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.startsWith("---\n")) {
            return Optional.empty();
        }
        int end = text.indexOf("\n---\n", 3);
        if (end < 0) {
            return Optional.empty();
        }
        Map<String, String> meta = frontMatter(text.substring(4, end));
        String docId = meta.get("id");
        String title = meta.get("title");
        if (docId == null || docId.isBlank() || title == null || title.isBlank()) {
            return Optional.empty();
        }

        List<KbSection> sections = new ArrayList<>();
        String sectionName = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.substring(end + 5).split("\n", -1)) {
            if (line.startsWith("## ")) {
                addSection(sections, title, sectionName, body);
                sectionName = line.substring(3).trim();
                body.setLength(0);
            } else if (sectionName != null) {
                body.append(line).append('\n');
            }
        }
        addSection(sections, title, sectionName, body);

        return Optional.of(new KbDocument(docId.trim(), title.trim(), meta.getOrDefault("topic", ""),
                meta.get("doc_type"), meta.get("safety_level"), meta.get("source_name"),
                meta.getOrDefault("version", "1"), List.copyOf(sections)));
    }

    private static void addSection(List<KbSection> sections, String title, String name, StringBuilder body) {
        if (name == null || EXCLUDED_SECTIONS.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        String cleaned = clean(body.toString());
        if (cleaned.isEmpty() || LONG_NUMBER.matcher(cleaned).find()) {
            return; // empty, or a number we could not safely remove
        }
        sections.add(new KbSection(name, kindOf(name), title.trim() + " - " + name + "\n" + cleaned));
    }

    static String clean(String body) {
        String text = body.replace("**", "");
        text = BRACKETED_NUMBER.matcher(text).replaceAll("");
        return text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    static SectionKind kindOf(String sectionName) {
        String name = sectionName.toLowerCase(Locale.ROOT);
        if (name.equals("overview")) {
            return SectionKind.OVERVIEW;
        }
        if (name.equals("safety and limitations") || name.equals("cautions")) {
            return SectionKind.SAFETY;
        }
        if (name.equals("when professional support may help")) {
            return SectionKind.PROFESSIONAL;
        }
        return PRACTICAL_PREFIXES.stream().anyMatch(name::startsWith) ? SectionKind.PRACTICAL : SectionKind.OTHER;
    }

    // Flat "key: value" lines only; lists ("tags: [a, b]") are kept as raw text and not used.
    private static Map<String, String> frontMatter(String block) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            String value = line.substring(colon + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            meta.put(line.substring(0, colon).trim(), value);
        }
        return meta;
    }
}
