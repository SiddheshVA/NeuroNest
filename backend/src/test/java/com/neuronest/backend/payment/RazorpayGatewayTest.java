package com.neuronest.backend.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayGatewayTest {

    private static final String KEY_ID = "rzp_test_ABCDEFGHIJKLMN";
    private static final String SECRET = "unit-test-secret-value-1";

    private final RazorpayGateway gateway = new RazorpayGateway(KEY_ID, SECRET);

    @Test
    void theLoggedRazorpayMessageKeepsTheReasonButNeverTheKeys() {
        String message = gateway.safeMessage(new RuntimeException(
                "BAD_REQUEST_ERROR:Authentication failed for " + KEY_ID + ":" + SECRET + "\nretry"));

        assertThat(message).contains("BAD_REQUEST_ERROR:Authentication failed", "[key id]", "[secret]")
                .doesNotContain(KEY_ID, SECRET, "\n");
        assertThat(gateway.safeMessage(new RuntimeException((String) null))).isEqualTo("(no message)");
        assertThat(gateway.safeMessage(new RuntimeException("x".repeat(500)))).hasSize(303);
    }
}
