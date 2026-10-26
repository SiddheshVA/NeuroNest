package com.neuronest.backend.meeting;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** GoogleMeetService against a mock HTTP server: Google itself is never called. */
class GoogleMeetServiceTest {

    private static final String SPACE = "spaces/jQCFfuBOdN5z";
    private static final String TOKEN_JSON = "{\"access_token\":\"ya29.test-access\",\"expires_in\":3599,\"token_type\":\"Bearer\"}";
    private static final String SPACE_JSON = "{\"name\":\"" + SPACE + "\",\"meetingUri\":\"https://meet.google.com/abc-mnop-xyz\","
            + "\"meetingCode\":\"abc-mnop-xyz\",\"config\":{\"accessType\":\"RESTRICTED\"}}";

    /** A clock the test can move forward. */
    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-29T10:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final MovableClock clock = new MovableClock();

    private GoogleMeetService configured() {
        return new GoogleMeetService("client-id-test", "client-secret-test", "refresh-token-test", builder, clock);
    }

    private void expectToken() {
        server.expect(once(), requestTo(GoogleMeetService.TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(CollectionUtils.toMultiValueMap(Map.of(
                        "grant_type", List.of("refresh_token"),
                        "client_id", List.of("client-id-test"),
                        "client_secret", List.of("client-secret-test"),
                        "refresh_token", List.of("refresh-token-test")))))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));
    }

    @Test
    void createsARestrictedSpaceAndReturnsItsNameAndLink() {
        GoogleMeetService google = configured();
        expectToken();
        server.expect(once(), requestTo("https://meet.googleapis.com/v2/spaces"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer ya29.test-access"))
                .andExpect(jsonPath("$.config.accessType").value("RESTRICTED"))
                .andRespond(withSuccess(SPACE_JSON, MediaType.APPLICATION_JSON));

        assertThat(google.createSpace()).isEqualTo(new GoogleMeetService.Space(SPACE, "https://meet.google.com/abc-mnop-xyz"));
        server.verify();
    }

    @Test
    void aSpaceWithoutANameOrLinkIsAFailure() {
        GoogleMeetService google = configured();
        expectToken();
        server.expect(once(), requestTo("https://meet.googleapis.com/v2/spaces"))
                .andRespond(withSuccess("{\"name\":\"" + SPACE + "\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(google::createSpace).isInstanceOf(GoogleMeetService.GoogleMeetException.class)
                .hasMessage("Google Meet step 'create space' failed: the response had no space name or meeting link.");
    }

    @Test
    void theAccessTypeIsPatchedWithItsUpdateMask() {
        GoogleMeetService google = configured();
        expectToken();
        for (String type : List.of("OPEN", "RESTRICTED")) {
            server.expect(once(), requestTo("https://meet.googleapis.com/v2/" + SPACE + "?updateMask=config.accessType"))
                    .andExpect(method(HttpMethod.PATCH))
                    .andExpect(header("Authorization", "Bearer ya29.test-access"))
                    .andExpect(jsonPath("$.config.accessType").value(type))
                    .andRespond(withSuccess(SPACE_JSON, MediaType.APPLICATION_JSON));
        }

        google.setAccessType(SPACE, GoogleMeetService.AccessType.OPEN);
        google.setAccessType(SPACE, GoogleMeetService.AccessType.RESTRICTED);

        // One token for both calls: it is cached.
        server.verify();
    }

    @Test
    void endingTheCallTreatsNoActiveConferenceAsDone() {
        GoogleMeetService google = configured();
        expectToken();
        String end = "https://meet.googleapis.com/v2/" + SPACE + ":endActiveConference";
        server.expect(once(), requestTo(end)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer ya29.test-access"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(end)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":400,\"message\":\"No active conference.\",\"status\":\"FAILED_PRECONDITION\"}}"));
        server.expect(once(), requestTo(end)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        google.endActiveConference(SPACE);
        google.endActiveConference(SPACE);
        assertThatThrownBy(() -> google.endActiveConference(SPACE)).isInstanceOf(GoogleMeetService.GoogleMeetException.class)
                .hasMessage("Google Meet step 'end conference' failed: HTTP 500.");
        server.verify();
    }

    @Test
    void theAccessTokenIsCachedUntilShortlyBeforeItExpires() {
        GoogleMeetService google = configured();
        expectToken();
        assertThat(google.accessToken()).isEqualTo("ya29.test-access");
        clock.now = clock.now.plus(Duration.ofMinutes(55));
        assertThat(google.accessToken()).isEqualTo("ya29.test-access");
        server.verify();

        server.reset();
        expectToken();
        clock.now = clock.now.plus(Duration.ofMinutes(4));
        assertThat(google.accessToken()).isEqualTo("ya29.test-access");
        server.verify();
    }

    @Test
    void aRefusedRefreshTokenIsAFailureThatNeverShowsTheSecrets() {
        GoogleMeetService google = configured();
        server.expect(once(), requestTo(GoogleMeetService.TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(google::createSpace)
                .isInstanceOf(GoogleMeetService.GoogleMeetException.class)
                .hasMessageContaining("Google Meet step 'token refresh' failed: HTTP 400 invalid_grant")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("client-secret-test", "refresh-token-test"));
        // The Meet API was never called.
        server.verify();
    }

    @Test
    void googlesOwnErrorStatusAndMessageSayWhichStepFailedAndWhy() {
        GoogleMeetService google = configured();
        expectToken();
        server.expect(once(), requestTo("https://meet.googleapis.com/v2/spaces"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error": {"code": 403, "status": "PERMISSION_DENIED",
                         "message": "Google Meet REST API has not been used in project 1234 before or it is disabled."}}"""));

        assertThatThrownBy(google::createSpace).isInstanceOf(GoogleMeetService.GoogleMeetException.class)
                .hasMessage("Google Meet step 'create space' failed: HTTP 403 PERMISSION_DENIED: Google Meet REST API "
                        + "has not been used in project 1234 before or it is disabled.");
    }

    @Test
    void anInsufficientScopeOnPatchIsNamed() {
        GoogleMeetService google = configured();
        expectToken();
        server.expect(once(), requestTo("https://meet.googleapis.com/v2/" + SPACE + "?updateMask=config.accessType"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"error\":{\"code\":403,\"message\":\"Request had insufficient authentication scopes.\","
                                + "\"status\":\"PERMISSION_DENIED\"}}"));

        assertThatThrownBy(() -> google.setAccessType(SPACE, GoogleMeetService.AccessType.OPEN))
                .hasMessage("Google Meet step 'set access type' failed: HTTP 403 PERMISSION_DENIED: Request had "
                        + "insufficient authentication scopes.");
    }

    @Test
    void ourSecretsAreBlankedOutEvenIfGoogleEchoedThem() {
        GoogleMeetService google = configured();
        server.expect(once(), requestTo(GoogleMeetService.TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"error\":\"invalid_client\",\"error_description\":\"client-secret-test does not match "
                                + "refresh-token-test\"}"));

        assertThatThrownBy(google::createSpace)
                .hasMessageContaining("HTTP 400 invalid_client: [redacted] does not match [redacted]")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("client-secret-test", "refresh-token-test"));
    }

    @Test
    void startupLogsWhichSettingsArePresentButNeverTheirValues() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GoogleMeetService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            new GoogleMeetService("cid-VALUE-123", "", "rt-VALUE-456");
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage()).contains("google.client-id present=true",
                    "google.client-secret present=false", "google.refresh-token present=true")
                    .doesNotContain("VALUE");
        });
    }

    @Test
    void blankConfigurationFailsClearlyWithoutCallingGoogle() {
        for (String[] config : new String[][]{{"", "secret", "refresh"}, {"id", " ", "refresh"}, {"id", "secret", null}}) {
            GoogleMeetService google = new GoogleMeetService(config[0], config[1], config[2], builder, clock);
            assertThat(google.isConfigured()).isFalse();
            for (Runnable call : List.<Runnable>of(google::createSpace,
                    () -> google.setAccessType(SPACE, GoogleMeetService.AccessType.OPEN),
                    () -> google.endActiveConference(SPACE))) {
                assertThatThrownBy(call::run).isInstanceOf(GoogleMeetService.NotConfiguredException.class)
                        .hasMessage("Google Meet is not configured: set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and "
                                + "GOOGLE_REFRESH_TOKEN.");
            }
        }
        server.verify();
    }

    @Test
    void anythingButASpaceNameIsRefusedBeforeItReachesAUrl() {
        GoogleMeetService google = configured();
        for (String bad : new String[]{null, "spaces/../../other", "conferenceRecords/abc", "spaces/abc?x=1"}) {
            assertThatThrownBy(() -> google.setAccessType(bad, GoogleMeetService.AccessType.OPEN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        server.verify();
    }
}
