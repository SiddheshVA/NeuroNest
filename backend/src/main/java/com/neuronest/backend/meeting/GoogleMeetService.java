package com.neuronest.backend.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every call to the Google Meet REST API goes through here, so tests can mock the HTTP. Creates a meeting
 * space on the NeuroNest Google account, switches who may enter it (OPEN: anyone with the link joins
 * without knocking; RESTRICTED: only invited people, so nobody else gets in) and ends a running call.
 * Google cannot limit a link to a time window, so NeuroNest does it with the access type (MeetingWindow,
 * MeetingJoinService, MeetingAccessJob).
 *
 * <p>Access tokens come from the OAuth refresh token (scope
 * https://www.googleapis.com/auth/meetings.space.created) and are cached in memory until shortly before they
 * expire. The client id, secret and refresh token come from google.client-id, google.client-secret and
 * google.refresh-token (GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REFRESH_TOKEN). Without all three the
 * app still starts and only these calls fail, with {@link NotConfiguredException}. No secret, token or
 * Google response body is logged or put in an exception message.
 */
@Service
public class GoogleMeetService {

    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String API = "https://meet.googleapis.com/v2/";
    static final String SCOPE = "https://www.googleapis.com/auth/meetings.space.created";
    /** A cached access token is renewed this long before Google says it expires. */
    static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);
    /** What Google calls a space: "spaces/" and its id. Checked before it is put in a URL. */
    private static final Pattern SPACE_NAME = Pattern.compile("spaces/[A-Za-z0-9_-]+");

    private static final Logger log = LoggerFactory.getLogger(GoogleMeetService.class);
    private static final JsonMapper ERROR_JSON = JsonMapper.builder().build();
    /** Google's error text in a log line is cut to this length. */
    static final int MAX_DETAILS = 300;

    public enum AccessType { OPEN, RESTRICTED }

    /** A meeting space: its resource name ("spaces/abc123") and its link ("https://meet.google.com/..."). */
    public record Space(String name, String meetingUri) {}

    /** One of GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET or GOOGLE_REFRESH_TOKEN is not set. */
    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException() {
            super("Google Meet is not configured: set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and GOOGLE_REFRESH_TOKEN.");
        }
    }

    /** Google refused or could not be reached, or answered without what was asked for. Never carries a token. */
    public static class GoogleMeetException extends RuntimeException {
        public GoogleMeetException(String message) {
            super(message);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {}

    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private final RestClient http;
    private final Clock clock;
    private CachedToken token;

    @Autowired
    public GoogleMeetService(@Value("${google.client-id:}") String clientId,
                             @Value("${google.client-secret:}") String clientSecret,
                             @Value("${google.refresh-token:}") String refreshToken) {
        this(clientId, clientSecret, refreshToken, RestClient.builder().requestFactory(requestFactory()),
                Clock.systemUTC());
        // Once, at startup: which settings are present (true/false only, never a value).
        String presence = "Google Meet configuration: google.client-id present=" + (this.clientId != null)
                + ", google.client-secret present=" + (this.clientSecret != null)
                + ", google.refresh-token present=" + (this.refreshToken != null);
        if (isConfigured()) {
            log.info(presence);
        } else {
            log.warn(presence + ". Video bookings will be flagged for a link until all three are set.");
        }
    }

    // With an explicit RestClient builder (tests bind a mock server to it) and clock.
    GoogleMeetService(String clientId, String clientSecret, String refreshToken, RestClient.Builder http, Clock clock) {
        this.clientId = blankToNull(clientId);
        this.clientSecret = blankToNull(clientSecret);
        this.refreshToken = blankToNull(refreshToken);
        this.http = http.build();
        this.clock = clock;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    public boolean isConfigured() {
        return clientId != null && clientSecret != null && refreshToken != null;
    }

    /** Creates a RESTRICTED space: nobody can enter until it is opened. */
    public Space createSpace() {
        requireConfigured();
        JsonNode created = call("create space", () -> http.post()
                .uri(API + "spaces")
                .header("Authorization", "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("config", Map.of("accessType", AccessType.RESTRICTED.name())))
                .retrieve()
                .body(JsonNode.class));
        String name = text(created, "name");
        String meetingUri = text(created, "meetingUri");
        if (name == null || meetingUri == null || !SPACE_NAME.matcher(name).matches()) {
            throw new GoogleMeetException("Google Meet step 'create space' failed: the response had no space name or meeting link.");
        }
        return new Space(name, meetingUri);
    }

    /** Sets who may enter the space: OPEN (anyone with the link, no knocking) or RESTRICTED. */
    public void setAccessType(String spaceName, AccessType type) {
        requireConfigured();
        String name = checked(spaceName);
        call("set access type", () -> http.patch()
                .uri(API + name + "?updateMask=config.accessType")
                .header("Authorization", "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("config", Map.of("accessType", type.name())))
                .retrieve()
                .toBodilessEntity());
    }

    /** Ends the call running in the space, if there is one; a space with no call is not an error. */
    public void endActiveConference(String spaceName) {
        requireConfigured();
        String name = checked(spaceName);
        try {
            http.post()
                    .uri(API + name + ":endActiveConference")
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Google answers FAILED_PRECONDITION (or NOT_FOUND) when nothing is running: already ended.
            if (noActiveConference(e)) return;
            throw failure("end conference", e);
        } catch (RestClientException e) {
            throw unreachable("end conference", e);
        }
    }

    // The cached access token, or a new one from the refresh token.
    synchronized String accessToken() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(token.expiresAt())) return token.value();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        JsonNode response;
        try {
            response = http.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new GoogleMeetException("Google Meet step 'token refresh' failed: HTTP " + e.getStatusCode().value()
                    + details(e) + ". Check GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and GOOGLE_REFRESH_TOKEN (and that the"
                    + " refresh token has the " + SCOPE + " scope).");
        } catch (RestClientException e) {
            throw unreachable("token refresh", e);
        }
        String accessToken = text(response, "access_token");
        if (accessToken == null) {
            throw new GoogleMeetException("Google Meet step 'token refresh' failed: the response had no access token.");
        }
        long expiresIn = response.path("expires_in").asLong(3600);
        token = new CachedToken(accessToken, now.plusSeconds(expiresIn).minus(EXPIRY_MARGIN));
        return accessToken;
    }

    private <T> T call(String step, java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw failure(step, e);
        } catch (RestClientException e) {
            throw unreachable(step, e);
        }
    }

    // "Google Meet step 'create space' failed: HTTP 403 PERMISSION_DENIED: Google Meet REST API has not been
    // used in project 123 before or it is disabled." Google's own error status and message, never the request.
    private GoogleMeetException failure(String step, RestClientResponseException e) {
        if (e.getStatusCode().value() == 401) clearToken();
        return new GoogleMeetException("Google Meet step '" + step + "' failed: HTTP " + e.getStatusCode().value()
                + details(e) + ".");
    }

    private static GoogleMeetException unreachable(String step, RestClientException e) {
        return new GoogleMeetException("Google Meet step '" + step + "' failed: Google could not be reached ("
                + e.getClass().getSimpleName() + ").");
    }

    /**
     * " PERMISSION_DENIED: <Google's message>" from a Meet API error ({"error": {"status", "message"}}) or
     * " invalid_grant: <description>" from the token endpoint ({"error", "error_description"}); empty when the
     * body says nothing readable. Any of our own secrets or the access token is blanked out, and the text is
     * kept short and on one line.
     */
    String details(RestClientResponseException e) {
        String status = "";
        String message = "";
        try {
            JsonNode body = ERROR_JSON.readTree(e.getResponseBodyAsString());
            JsonNode error = body == null ? null : body.get("error");
            if (error != null && error.isObject()) {
                status = error.path("status").asString("");
                message = error.path("message").asString("");
            } else if (error != null && error.isString()) {
                status = error.asString();
                message = body.path("error_description").asString("");
            }
        } catch (RuntimeException notJson) {
            return "";
        }
        String text = (status + (message.isBlank() ? "" : ": " + message)).trim();
        if (text.isEmpty()) return "";
        for (String secret : new String[]{clientSecret, refreshToken, token == null ? null : token.value()}) {
            if (secret != null && !secret.isBlank()) text = text.replace(secret, "[redacted]");
        }
        // One line, and without Google's own full stop (the message adds one).
        text = text.replaceAll("\\s+", " ").replaceAll("[.\\s]+$", "");
        return " " + (text.length() > MAX_DETAILS ? text.substring(0, MAX_DETAILS) + "..." : text);
    }

    private static boolean noActiveConference(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        return status == 404 || (status == 400 && body.contains("FAILED_PRECONDITION"))
                || (status == 409 && body.contains("FAILED_PRECONDITION"));
    }

    private synchronized void clearToken() {
        token = null;
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new NotConfiguredException();
    }

    private static String checked(String spaceName) {
        if (spaceName == null || !SPACE_NAME.matcher(spaceName).matches()) {
            throw new IllegalArgumentException("Not a meeting space name.");
        }
        return spaceName;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asString().isBlank() ? null : value.asString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
