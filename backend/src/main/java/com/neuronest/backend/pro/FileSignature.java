package com.neuronest.backend.pro;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

// The file types professionals may upload, recognised by their leading bytes (magic numbers). The
// declared content type and the extension are never trusted on their own.
public enum FileSignature {

    PDF("application/pdf", "pdf", Set.of("pdf"), new byte[] {'%', 'P', 'D', 'F', '-'}),
    PNG("image/png", "png", Set.of("png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
    JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg"), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    private final String contentType;
    private final String storedExtension;
    private final Set<String> acceptedExtensions;
    private final byte[] magic;

    FileSignature(String contentType, String storedExtension, Set<String> acceptedExtensions, byte[] magic) {
        this.contentType = contentType;
        this.storedExtension = storedExtension;
        this.acceptedExtensions = acceptedExtensions;
        this.magic = magic;
    }

    public String contentType() {
        return contentType;
    }

    public String storedExtension() {
        return storedExtension;
    }

    public static Optional<FileSignature> detect(byte[] content) {
        for (FileSignature signature : values()) {
            if (content.length >= signature.magic.length
                    && Arrays.equals(content, 0, signature.magic.length, signature.magic, 0, signature.magic.length)) {
                return Optional.of(signature);
            }
        }
        return Optional.empty();
    }

    // A file without an extension is judged by its contents alone; one with an extension must agree.
    public boolean matchesExtensionOf(String filename) {
        if (filename == null) {
            return true;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return true;
        }
        return acceptedExtensions.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
