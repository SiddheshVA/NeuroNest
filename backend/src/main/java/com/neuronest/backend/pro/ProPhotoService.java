package com.neuronest.backend.pro;

import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.professional.Professional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

// The professional's profile photo: checked here (JPG, PNG or WebP by its leading bytes, at most 2 MB),
// then uploaded to Cloudinary. Only the URL is returned; the form stores it in photo_url when the
// professional saves, like any other field.
@Service
public class ProPhotoService {

    static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024;
    static final String TYPE_MESSAGE = "Please choose a JPG, PNG or WebP image.";
    static final String SIZE_MESSAGE = "The photo must be 2 MB or smaller. Please choose a smaller image.";

    public record PhotoUploaded(String photoUrl) {}

    private final ProProfileService profiles;
    private final CloudinaryClient cloudinary;

    public ProPhotoService(ProProfileService profiles, CloudinaryClient cloudinary) {
        this.profiles = profiles;
        this.cloudinary = cloudinary;
    }

    public PhotoUploaded upload(ProPrincipal principal, MultipartFile file) {
        Professional p = profiles.professional(profiles.account(principal));
        if (!cloudinary.isConfigured()) throw new CloudinaryClient.NotConfiguredException();
        if (file == null || file.isEmpty()) throw new ApiValidationException("Please choose a photo to upload.");
        if (file.getSize() > MAX_PHOTO_BYTES) throw new ApiValidationException(SIZE_MESSAGE);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // The declared content type and the file name are never trusted on their own.
        String contentType = imageType(content).orElseThrow(() -> new ApiValidationException(TYPE_MESSAGE));
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.isEmpty() && !name.matches(".*\\.(jpe?g|png|webp)$")) throw new ApiValidationException(TYPE_MESSAGE);
        return new PhotoUploaded(cloudinary.uploadImage(content, contentType, p.getId().toString()));
    }

    static Optional<String> imageType(byte[] c) {
        if (startsWith(c, 0, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF)) return Optional.of("image/jpeg");
        if (startsWith(c, 0, (byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A)) {
            return Optional.of("image/png");
        }
        if (startsWith(c, 0, (byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F')
                && startsWith(c, 8, (byte) 'W', (byte) 'E', (byte) 'B', (byte) 'P')) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, int offset, byte... magic) {
        return content.length >= offset + magic.length
                && Arrays.equals(content, offset, offset + magic.length, magic, 0, magic.length);
    }
}
