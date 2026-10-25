package com.neuronest.backend.pro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Signed image uploads to Cloudinary (free tier). The keys come from CLOUDINARY_CLOUD_NAME,
 * CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET; the secret only signs the request here and never
 * reaches the browser or the logs. Without all three the app still starts and only the photo upload
 * answers 503.
 */
@Component
public class CloudinaryClient {

    /** 503: the Cloudinary keys are not set. */
    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException() {
            super("Photo upload is not configured. Please try again later.");
        }
    }

    /** 502: Cloudinary could not be reached or refused the upload. */
    public static class UploadFailedException extends RuntimeException {
        public UploadFailedException() {
            super("The photo could not be uploaded. Please try again.");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CloudinaryClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final String folder;
    private final JsonMapper jsonMapper;
    private final HttpClient http;
    private final Clock clock = Clock.systemUTC();

    public CloudinaryClient(@Value("${cloudinary.cloud-name:}") String cloudName,
                            @Value("${cloudinary.api-key:}") String apiKey,
                            @Value("${cloudinary.api-secret:}") String apiSecret,
                            @Value("${cloudinary.folder:neuronest/professionals}") String folder,
                            JsonMapper jsonMapper) {
        this.cloudName = blankToNull(cloudName);
        this.apiKey = blankToNull(apiKey);
        this.apiSecret = blankToNull(apiSecret);
        this.folder = folder;
        this.jsonMapper = jsonMapper;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        log.info(isConfigured() ? "Cloudinary photo upload is configured."
                : "Cloudinary photo upload is not configured: the photo upload endpoint is off.");
    }

    public boolean isConfigured() {
        return cloudName != null && apiKey != null && apiSecret != null;
    }

    /** Uploads one image and returns its https URL (Cloudinary's secure_url). */
    public String uploadImage(byte[] content, String contentType, String subFolder) {
        if (!isConfigured()) throw new NotConfiguredException();
        // Signed parameters, in alphabetical order as Cloudinary requires.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("folder", folder + "/" + subFolder);
        params.put("timestamp", String.valueOf(clock.instant().getEpochSecond()));
        StringBuilder toSign = new StringBuilder();
        params.forEach((k, v) -> toSign.append(toSign.isEmpty() ? "" : "&").append(k).append('=').append(v));
        params.put("signature", sha1Hex(toSign + apiSecret));
        params.put("api_key", apiKey);

        String boundary = "neuronest-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.cloudinary.com/v1_1/" + cloudName + "/image/upload"))
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, params, content, contentType)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Cloudinary upload failed with status {}", response.statusCode());
                throw new UploadFailedException();
            }
            JsonNode body = jsonMapper.readTree(response.body());
            String url = body.path("secure_url").asString(null);
            if (url == null || !url.startsWith("https://")) throw new UploadFailedException();
            return url;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UploadFailedException();
        } catch (UploadFailedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Cloudinary upload failed: {}", e.getClass().getName());
            throw new UploadFailedException();
        }
    }

    private static byte[] multipart(String boundary, Map<String, String> fields, byte[] file, String contentType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 1024);
        fields.forEach((name, value) -> out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8)));
        out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(file);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static String sha1Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
