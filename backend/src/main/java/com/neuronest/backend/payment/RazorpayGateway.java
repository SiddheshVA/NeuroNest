package com.neuronest.backend.payment;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Every call to Razorpay goes through here, so tests can mock it. The keys come only from the
 * RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET environment variables (not application.properties or
 * .env.properties) and are never logged. Without both, the app still starts and only the payment
 * endpoints refuse, with {@link NotConfiguredException}.
 */
@Service
public class RazorpayGateway {

    /** 503: RAZORPAY_KEY_ID or RAZORPAY_KEY_SECRET is not set. */
    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException() {
            super("Payments are not configured. Please try again later.");
        }
    }

    /** 502: Razorpay could not be reached or refused the call. */
    public static class GatewayException extends RuntimeException {
        public GatewayException() {
            super("The payment could not be started. Please try again.");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    private final String keyId;
    private final String keySecret;
    private RazorpayClient client;

    @Autowired
    public RazorpayGateway() {
        this(System.getenv("RAZORPAY_KEY_ID"), System.getenv("RAZORPAY_KEY_SECRET"));
        log.info(isConfigured() ? "Razorpay payments are configured."
                : "Razorpay payments are not configured: the payment endpoints are off.");
    }

    // With explicit keys, for tests. The app always uses the environment (above).
    public RazorpayGateway(String keyId, String keySecret) {
        this.keyId = blankToNull(keyId);
        this.keySecret = blankToNull(keySecret);
    }

    public boolean isConfigured() {
        return keyId != null && keySecret != null;
    }

    /** The public key id, which the checkout needs. Never the secret. */
    public String keyId() {
        requireConfigured();
        return keyId;
    }

    /** Creates an order and returns its id. The receipt is our own reference (at most 40 characters). */
    public String createOrder(long amountPaise, String currency, String receipt) {
        requireConfigured();
        JSONObject body = new JSONObject();
        body.put("amount", amountPaise);
        body.put("currency", currency);
        body.put("receipt", receipt);
        try {
            Order order = client().orders.create(body);
            return order.get("id");
        } catch (RazorpayException | RuntimeException e) {
            // The class only: a Razorpay message can echo request details.
            log.warn("Razorpay order creation failed ({})", e.getClass().getSimpleName());
            throw new GatewayException();
        }
    }

    /**
     * Whether {@code signature} is Razorpay's HMAC-SHA256 of "orderId|paymentId" with the key secret, as
     * hex. Compared in constant time.
     */
    public boolean isValidSignature(String orderId, String paymentId, String signature) {
        requireConfigured();
        if (orderId == null || paymentId == null || signature == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId)
                    .getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new NotConfiguredException();
    }

    // Built on first use. Request logging stays off (false), so nothing about a call is printed.
    private synchronized RazorpayClient client() throws RazorpayException {
        if (client == null) client = new RazorpayClient(keyId, keySecret, false);
        return client;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
