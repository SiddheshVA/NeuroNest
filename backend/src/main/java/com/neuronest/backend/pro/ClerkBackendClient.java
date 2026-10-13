package com.neuronest.backend.pro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

// Reads a user's primary email from the Clerk Backend API, so professional sign-up never trusts an
// email from the request body or an unverifiable token claim. Needs CLERK_SECRET_KEY; the app does
// not start without it.
@Component
public class ClerkBackendClient {

    private static final Logger log = LoggerFactory.getLogger(ClerkBackendClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    // Clerk user ids look like "user_2abc..."; anything else never reaches the URL.
    private static final Pattern USER_ID = Pattern.compile("^user_[A-Za-z0-9]+$");

    /** The user's primary email and whether Clerk has verified it. */
    public record PrimaryEmail(String address, boolean verified) {}

    private final String secretKey;
    private final String apiUrl;
    private final JsonMapper jsonMapper;
    private final HttpClient http;

    public ClerkBackendClient(@Value("${clerk.secret-key:}") String secretKey,
                              @Value("${clerk.api-url}") String apiUrl,
                              JsonMapper jsonMapper) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("CLERK_SECRET_KEY is not set. Professional sign-up needs it to confirm "
                    + "the user's email with Clerk. Copy the secret key (it starts with sk_) from the Clerk dashboard, "
                    + "API Keys page, into a CLERK_SECRET_KEY environment variable or backend/.env.properties.");
        }
        if (!secretKey.startsWith("sk_")) {
            throw new IllegalStateException("CLERK_SECRET_KEY does not look like a Clerk secret key (it should start "
                    + "with sk_). Check that the secret key, not the publishable key, was copied.");
        }
        this.secretKey = secretKey;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.jsonMapper = jsonMapper;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * The primary email of a Clerk user, or empty when the user has none.
     *
     * @throws ProExceptions.ClerkUnavailableException when Clerk cannot be reached or answers unexpectedly
     */
    public Optional<PrimaryEmail> primaryEmail(String clerkUserId) {
        if (clerkUserId == null || !USER_ID.matcher(clerkUserId).matches()) {
            throw new ProExceptions.ClerkUnavailableException();
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(apiUrl + "/users/" + URLEncoder.encode(clerkUserId, StandardCharsets.UTF_8)))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + secretKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Clerk Backend API request failed: {}", e.toString());
            throw new ProExceptions.ClerkUnavailableException();
        }
        if (response.statusCode() != 200) {
            // The body is not logged: it can echo user details.
            log.warn("Clerk Backend API returned status {} for a user lookup", response.statusCode());
            throw new ProExceptions.ClerkUnavailableException();
        }
        return parsePrimaryEmail(jsonMapper.readTree(response.body()));
    }

    static Optional<PrimaryEmail> parsePrimaryEmail(JsonNode user) {
        String primaryId = user.path("primary_email_address_id").asString("");
        if (primaryId.isEmpty()) {
            return Optional.empty();
        }
        for (JsonNode email : user.path("email_addresses")) {
            if (primaryId.equals(email.path("id").asString(""))) {
                String address = email.path("email_address").asString("").trim();
                if (address.isEmpty()) {
                    return Optional.empty();
                }
                boolean verified = "verified".equals(email.path("verification").path("status").asString(""));
                return Optional.of(new PrimaryEmail(address, verified));
            }
        }
        return Optional.empty();
    }
}
