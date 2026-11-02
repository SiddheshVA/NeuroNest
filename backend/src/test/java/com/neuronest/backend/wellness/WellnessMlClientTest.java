package com.neuronest.backend.wellness;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.neuronest.backend.wellness.WellnessMlDtos.MlCheckinResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * WellnessMlClient against a mock server (status mapping) and a real local HTTP server (timeouts, a closed
 * port, the exact JSON on the wire). The ML service itself is never called.
 */
class WellnessMlClientTest {

    private static final String BASE = "http://ml.test";
    private static final String URL = BASE + WellnessMlClient.CHECKIN_PATH;
    private static final String KEY = "test-internal-key-0123456789";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private HttpServer realServer;

    @AfterEach
    void stopRealServer() {
        if (realServer != null) realServer.stop(0);
    }

    private WellnessMlClient client() {
        return new WellnessMlClient(BASE + "/", KEY, builder);
    }

    private static WellnessMlDtos.MlCheckinRequest mlRequest() {
        return WellnessInputs.toMl(WellnessTestData.request());
    }

    @Test
    void sendsOnlyTheAllowedFieldsAsTypedJsonWithTheKeyAndParsesTheResult() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(WellnessMlClient.KEY_HEADER, KEY))
                .andExpect(header(WellnessMlClient.REQUEST_ID_HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.segment").value("working"))
                .andExpect(jsonPath("$.age").value(34))
                .andExpect(jsonPath("$.sleep_duration").value(6.5))
                .andExpect(jsonPath("$.physical_activity_minutes").value(30))
                .andExpect(jsonPath("$.heart_rate").value(WellnessTestData.SECRET_HEART_RATE))
                .andExpect(jsonPath("$.family_history").doesNotExist())
                .andExpect(jsonPath("$.familyHistory").doesNotExist())
                .andRespond(withSuccess(WellnessTestData.mlResponse("Moderate", true, "insomnia_like"),
                        MediaType.APPLICATION_JSON));

        MlCheckinResult result = client().checkin(mlRequest());

        assertThat(result.mood().riskBand()).isEqualTo("Moderate");
        assertThat(result.sleep().patternCode()).isEqualTo("insomnia_like");
        assertThat(result.modelVersions()).containsKey("mood_working");
        server.verify();
    }

    @Test
    void numbersGoOnTheWireAsJsonNumbersNotStrings() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        realServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        realServer.createContext(WellnessMlClient.CHECKIN_PATH, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = WellnessTestData.mlResponse("Low", false, null).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        realServer.start();

        realClient(Duration.ofSeconds(3)).checkin(mlRequest());

        assertThat(body.get()).contains("\"age\":34", "\"pressure\":4", "\"sleep_duration\":6.5",
                "\"daily_steps\":" + WellnessTestData.SECRET_STEPS).doesNotContain("\"4\"", "\"34\"", "null");
    }

    @Test
    void a422BecomesFieldErrorsInThisApisNames() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
                        .body(WellnessTestData.ML_422));

        assertThatThrownBy(() -> client().checkin(mlRequest()))
                .isInstanceOfSatisfying(WellnessExceptions.InvalidInputException.class, e -> assertThat(e.fields())
                        .containsExactly(new WellnessDtos.FieldError("pressure", "Input should be less than or equal to 5"),
                                new WellnessDtos.FieldError("sleepDuration", "Field required")));
    }

    @Test
    void aRejectedKeyIsUnavailableAndLoggedAsAConfigurationProblem() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"unauthorized\",\"message\":\"Missing or invalid X-Internal-Key header.\"}}"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            assertThatThrownBy(() -> client().checkin(mlRequest())).isInstanceOf(WellnessExceptions.UnavailableException.class);
        } finally {
            detach(logs);
        }
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("refused the internal key"));
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(KEY));
    }

    @Test
    void serverErrorsAndOtherStatusesAreUnavailable() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY,
                HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN}) {
            server.reset();
            server.expect(once(), requestTo(URL)).andRespond(withStatus(status).body("Traceback (most recent call last)"));
            assertThatThrownBy(() -> client().checkin(mlRequest()))
                    .isInstanceOf(WellnessExceptions.UnavailableException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Traceback"));
        }
    }

    @Test
    void anIncompleteOrMalformed200IsUnavailable() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess("{\"mood\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client().checkin(mlRequest())).isInstanceOf(WellnessExceptions.UnavailableException.class);
        server.reset();
        server.expect(once(), requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client().checkin(mlRequest())).isInstanceOf(WellnessExceptions.UnavailableException.class);
        server.reset();
        server.expect(once(), requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> client().checkin(mlRequest())).isInstanceOf(WellnessExceptions.UnavailableException.class);
    }

    @Test
    void noKeyConfiguredSendsNoKeyHeader() {
        server.expect(once(), requestTo(URL))
                .andExpect(headerDoesNotExist(WellnessMlClient.KEY_HEADER))
                .andRespond(withSuccess(WellnessTestData.mlResponse("Low", false, null), MediaType.APPLICATION_JSON));
        new WellnessMlClient(BASE, "  ", builder).checkin(mlRequest());
        server.verify();
    }

    @Test
    void aSlowServiceTimesOutAsUnavailable() throws IOException {
        realServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        realServer.createContext(WellnessMlClient.CHECKIN_PATH, exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        realServer.start();

        long start = System.nanoTime();
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            assertThatThrownBy(() -> realClient(Duration.ofMillis(300)).checkin(mlRequest()))
                    .isInstanceOf(WellnessExceptions.UnavailableException.class);
        } finally {
            detach(logs);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_800));
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("status=timeout"));
    }

    @Test
    void anUnreachableServiceIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        WellnessMlClient client = new WellnessMlClient("http://127.0.0.1:" + closedPort, KEY,
                RestClient.builder().requestFactory(WellnessMlClient.requestFactory(Duration.ofSeconds(2), Duration.ofSeconds(3))));
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            assertThatThrownBy(() -> client.checkin(mlRequest())).isInstanceOf(WellnessExceptions.UnavailableException.class);
        } finally {
            detach(logs);
        }
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("status=unreachable"));
    }

    @Test
    void logsCarryOnlyRequestIdStatusAndLatencyNeverAnswersResultsOrKey() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(WellnessTestData.mlResponse("High", true, "apnea_like"), MediaType.APPLICATION_JSON));
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            client().checkin(mlRequest());
        } finally {
            detach(logs);
        }
        assertThat(logs.list).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .matches("wellness ml call request_id=[0-9a-f]{32} status=200 latency_ms=\\d+"));
    }

    private WellnessMlClient realClient(Duration readTimeout) {
        return new WellnessMlClient("http://127.0.0.1:" + realServer.getAddress().getPort(), KEY,
                RestClient.builder().requestFactory(WellnessMlClient.requestFactory(Duration.ofSeconds(2), readTimeout)));
    }

    static ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(WellnessMlClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        return logs;
    }

    static void detach(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(WellnessMlClient.class)).detachAppender(logs);
    }
}
