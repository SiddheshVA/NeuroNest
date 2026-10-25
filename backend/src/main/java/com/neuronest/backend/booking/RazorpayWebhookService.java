package com.neuronest.backend.booking;

import com.neuronest.backend.payment.RazorpayWebhookVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Handles one Razorpay webhook delivery. The signature is checked first; then the event id is recorded in
// razorpay_webhook_events in the same transaction as the booking change, so a repeated delivery (Razorpay
// retries until it gets a 2xx) does nothing twice, and a delivery that fails is rolled back and retried.
// payment.captured and order.paid confirm the booking; payment.failed marks the order FAILED. Nothing
// here logs the body or the signature.
@Service
public class RazorpayWebhookService {

    /** 400: the signature is missing or wrong (or the webhook secret is not set). */
    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException() {
            super("Invalid webhook signature.");
        }
    }

    static final String RECORD_EVENT = """
            INSERT INTO razorpay_webhook_events (event_id, event_type) VALUES (:id, :type)
            ON CONFLICT (event_id) DO NOTHING""";

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);

    private final RazorpayWebhookVerifier verifier;
    private final BookingPaymentService payments;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public RazorpayWebhookService(RazorpayWebhookVerifier verifier, BookingPaymentService payments,
                                  NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.verifier = verifier;
        this.payments = payments;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void handle(byte[] body, String signature, String eventId) {
        if (!verifier.isValid(body, signature)) throw new InvalidSignatureException();
        JsonNode root = json.readTree(body);
        String event = root.path("event").asString("");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId = text(payment.path("order_id"));
        String paymentId = text(payment.path("id"));

        // Razorpay sends the same x-razorpay-event-id on every retry of one event.
        if (eventId != null && !eventId.isBlank() && jdbc.update(RECORD_EVENT, new MapSqlParameterSource()
                .addValue("id", eventId.trim()).addValue("type", event)) == 0) {
            log.info("Razorpay webhook {}: already handled, skipped.", event);
            return;
        }
        switch (event) {
            case "payment.captured", "order.paid" -> payments.confirmFromWebhook(orderId, paymentId);
            case "payment.failed" -> payments.recordFailedFromWebhook(orderId);
            default -> log.info("Razorpay webhook {}: not used, ignored.", event);
        }
    }

    private static String text(JsonNode node) {
        String value = node.asString(null);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
