package com.neuronest.backend.booking;

import com.neuronest.backend.payment.RazorpayWebhookVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RazorpayWebhookServiceTest {

    private static final String SECRET = "webhook_secret_for_unit_tests";
    private static final String CAPTURED = """
            {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_1","order_id":"order_1"}}}}""";
    private static final String FAILED = """
            {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_2","order_id":"order_1"}}}}""";

    private final BookingPaymentService payments = mock(BookingPaymentService.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final RazorpayWebhookService service = new RazorpayWebhookService(new RazorpayWebhookVerifier(SECRET),
            payments, jdbc, JsonMapper.builder().build());
    // Stands in for razorpay_webhook_events: the INSERT ... ON CONFLICT DO NOTHING adds a row once per id.
    private final Set<String> seen = new HashSet<>();

    RazorpayWebhookServiceTest() {
        when(jdbc.update(eq(RazorpayWebhookService.RECORD_EVENT), any(SqlParameterSource.class)))
                .thenAnswer(inv -> seen.add((String) ((SqlParameterSource) inv.getArgument(1)).getValue("id")) ? 1 : 0);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aCapturedPaymentConfirmsAndARepeatedDeliveryIsSkipped() throws Exception {
        byte[] body = CAPTURED.getBytes(StandardCharsets.UTF_8);

        service.handle(body, sign(CAPTURED), "evt_1");
        service.handle(body, sign(CAPTURED), "evt_1");

        verify(payments, times(1)).confirmFromWebhook("order_1", "pay_1");
    }

    @Test
    void aFailedPaymentIsRecorded() throws Exception {
        service.handle(FAILED.getBytes(StandardCharsets.UTF_8), sign(FAILED), "evt_2");
        verify(payments).recordFailedFromWebhook("order_1");
    }

    @Test
    void aWrongMissingOrTamperedSignatureIsRefusedAndChangesNothing() throws Exception {
        byte[] body = CAPTURED.getBytes(StandardCharsets.UTF_8);
        byte[] tampered = CAPTURED.replace("order_1", "order_9").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.handle(body, "deadbeef", "evt_3"))
                .isInstanceOf(RazorpayWebhookService.InvalidSignatureException.class);
        assertThatThrownBy(() -> service.handle(body, null, "evt_3"))
                .isInstanceOf(RazorpayWebhookService.InvalidSignatureException.class);
        assertThatThrownBy(() -> service.handle(tampered, sign(CAPTURED), "evt_3"))
                .isInstanceOf(RazorpayWebhookService.InvalidSignatureException.class);
        // Without a configured secret nothing is ever accepted.
        RazorpayWebhookService unconfigured = new RazorpayWebhookService(new RazorpayWebhookVerifier(null), payments,
                jdbc, JsonMapper.builder().build());
        assertThatThrownBy(() -> unconfigured.handle(body, sign(CAPTURED), "evt_3"))
                .isInstanceOf(RazorpayWebhookService.InvalidSignatureException.class);
        verifyNoInteractions(payments, jdbc);
    }
}
