package com.neuronest.backend.booking;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Razorpay's webhook (add it in the Razorpay dashboard with the events payment.captured, order.paid and
// payment.failed, and the same secret as RAZORPAY_WEBHOOK_SECRET). It is open to anyone (no sign-in), so
// nothing is trusted until the signature over the raw body checks out. Any 2xx tells Razorpay to stop
// retrying; a 5xx (an unexpected error) makes it retry later.
@RestController
public class RazorpayWebhookController {

    private final RazorpayWebhookService service;

    public RazorpayWebhookController(RazorpayWebhookService service) {
        this.service = service;
    }

    @PostMapping("/api/payments/razorpay/webhook")
    public ResponseEntity<Map<String, String>> webhook(@RequestBody(required = false) byte[] body,
                                                       @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
                                                       @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId) {
        service.handle(body, signature, eventId);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @ExceptionHandler(RazorpayWebhookService.InvalidSignatureException.class)
    public ResponseEntity<Map<String, String>> invalidSignature(RazorpayWebhookService.InvalidSignatureException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
