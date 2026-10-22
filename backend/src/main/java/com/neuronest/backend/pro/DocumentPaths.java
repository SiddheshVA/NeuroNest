package com.neuronest.backend.pro;

import com.neuronest.backend.professional.DocumentType;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * How new professional documents are named under APP_STORAGE_DIR, so an admin browsing the folder can
 * tell whose files they are:
 * {@code <slug of the name>__<first 8 characters of the id>/<document type>__<8 random hex>.<pdf|jpg|png>}.
 * The only user-supplied text in a path is the name, and only through {@link #slug}; the extension comes
 * from the detected file type, never from the uploaded file name. Older documents keep their UUID-based
 * paths, and a professional's later uploads reuse the folder of their earlier ones.
 */
final class DocumentPaths {

    static final int MAX_SLUG = 40;
    static final String FALLBACK_SLUG = "professional";

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");
    // One folder name with nothing that could step out of it: a new-style folder or an older UUID one.
    private static final Pattern SAFE_FOLDER = Pattern.compile("[a-z0-9-]{1,40}__[0-9a-f]{8}|[0-9a-f-]{36}");
    // A path that already follows the new naming: <slug>__<8 hex>/<doc-type>__<8 hex>.<pdf|jpg|png>.
    private static final Pattern NEW_STYLE_PATH =
            Pattern.compile("[a-z0-9-]{1,40}__[0-9a-f]{8}/[a-z-]+__[0-9a-f]{8}\\.(pdf|jpg|png)");
    private static final Set<String> STORED_EXTENSIONS = Set.of("pdf", "jpg", "png");
    private static final SecureRandom RANDOM = new SecureRandom();

    private DocumentPaths() {}

    /**
     * Lowercase a to z, 0 to 9 and single hyphens, at most 40 characters. Accents are dropped first
     * ("José" becomes "jose"); every other run of characters becomes one hyphen. A name with no Latin
     * letters or digits (for example in Devanagari) gives "professional".
     */
    static String slug(String name) {
        if (name == null) return FALLBACK_SLUG;
        String plain = COMBINING_MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFD)).replaceAll("");
        String slug = trimHyphens(NOT_SLUG.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll("-"));
        if (slug.length() > MAX_SLUG) slug = trimHyphens(slug.substring(0, MAX_SLUG));
        return slug.isEmpty() ? FALLBACK_SLUG : slug;
    }

    /** The folder for a professional's first document. */
    static String newFolder(String fullName, UUID professionalId) {
        return slug(fullName) + "__" + professionalId.toString().substring(0, 8);
    }

    /**
     * The folder part of an existing storage path, when it is one safe folder name (new style or the older
     * UUID style); empty otherwise, so a malformed stored path is never reused.
     */
    static Optional<String> folderOf(String storagePath) {
        if (storagePath == null) return Optional.empty();
        int slash = storagePath.indexOf('/');
        if (slash <= 0) return Optional.empty();
        String folder = storagePath.substring(0, slash);
        return SAFE_FOLDER.matcher(folder).matches() ? Optional.of(folder) : Optional.empty();
    }

    static boolean isSafeFolder(String folder) {
        return folder != null && SAFE_FOLDER.matcher(folder).matches();
    }

    /** For example "registration-certificate__3f9a0c1b.pdf". */
    static String newFileName(DocumentType docType, FileSignature signature) {
        return newFileName(docType, signature.storedExtension());
    }

    /** The same name with a stored extension (pdf, jpg or png) taken from an existing file. */
    static String newFileName(DocumentType docType, String storedExtension) {
        if (!STORED_EXTENSIONS.contains(storedExtension)) {
            throw new IllegalArgumentException("Not a stored document extension.");
        }
        byte[] random = new byte[4];
        RANDOM.nextBytes(random);
        return docType.name().toLowerCase(Locale.ROOT).replace('_', '-') + "__" + HexFormat.of().formatHex(random)
                + "." + storedExtension;
    }

    /** Whether a stored path already follows the new naming (folder and file name). */
    static boolean isNewStyle(String storagePath) {
        return storagePath != null && NEW_STYLE_PATH.matcher(storagePath).matches();
    }

    /**
     * The stored extension (pdf, jpg or png) of an existing file name, the one the detected type gave it
     * when it was uploaded; empty when it is none of those.
     */
    static Optional<String> storedExtensionOf(String storagePath) {
        if (storagePath == null) return Optional.empty();
        int dot = storagePath.lastIndexOf('.');
        if (dot < 0 || dot < storagePath.lastIndexOf('/')) return Optional.empty();
        String extension = storagePath.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("jpeg")) extension = "jpg";
        return STORED_EXTENSIONS.contains(extension) ? Optional.of(extension) : Optional.empty();
    }

    private static String trimHyphens(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') start++;
        while (end > start && value.charAt(end - 1) == '-') end--;
        return value.substring(start, end);
    }
}
