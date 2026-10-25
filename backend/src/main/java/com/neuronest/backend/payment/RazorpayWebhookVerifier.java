package com.neuronest.backend.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Checks the X-Razorpay-Signature header of a webhook delivery: Razorpay's HMAC-SHA256 of the raw request
 * body with the webhook secret (set in the Razorpay dashboard when the webhook is added), as hex, compared
 * in constant time. The secret comes only from the RAZORPAY_WEBHOOK_SECRET environment variable and is
 * never logged. Without it every delivery is refused.
 */
@Component
public class RazorpayWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookVerifier.class);

    private final String secret;

    @Autowired
    public RazorpayWebhookVerifier() {
        this(System.getenv("RAZORPAY_WEBHOOK_SECRET"));
        log.info(isConfigured() ? "Razorpay webhook is configured."
                : "Razorpay webhook is not configured (RAZORPAY_WEBHOOK_SECRET): webhook deliveries are refused.");
    }

    // With an explicit secret, for tests.
    public RazorpayWebhookVerifier(String secret) {
        this.secret = secret == null || secret.isBlank() ? null : secret.trim();
    }

    public boolean isConfigured() {
        return secret != null;
    }

    public boolean isValid(byte[] body, String signature) {
        if (secret == null || body == null || signature == null || signature.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, signature.trim().getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
