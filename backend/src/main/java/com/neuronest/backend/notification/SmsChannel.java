package com.neuronest.backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SMS: off (notifications.sms.enabled=false) and not connected to any provider yet. */
@Component
public class SmsChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SmsChannel.class);

    private final boolean enabled;
    // TODO(sms): read when a provider is connected (SMS_API_URL, SMS_API_KEY, SMS_SENDER_ID).
    @SuppressWarnings("unused")
    private final String apiUrl;
    @SuppressWarnings("unused")
    private final String apiKey;
    @SuppressWarnings("unused")
    private final String senderId;

    public SmsChannel(@Value("${notifications.sms.enabled:false}") boolean enabled,
                      @Value("${notifications.sms.api-url:}") String apiUrl,
                      @Value("${notifications.sms.api-key:}") String apiKey,
                      @Value("${notifications.sms.sender-id:}") String senderId) {
        this.enabled = enabled;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.senderId = senderId;
    }

    @Override
    public String name() {
        return "sms";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean canReach(Notification n) {
        return n.phone() != null && !n.phone().isBlank();
    }

    @Override
    public void send(Notification n) {
        // TODO(sms): send n.text() to n.phone() through the chosen provider (Indian DLT-registered sender id
        // and template). Until then nothing is sent.
        log.info("SMS skipped: no provider connected yet ({})", n.kind());
    }
}
