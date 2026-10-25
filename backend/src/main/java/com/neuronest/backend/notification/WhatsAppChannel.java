package com.neuronest.backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WhatsApp: off (notifications.whatsapp.enabled=false) and not connected to any provider yet. Only people
 * who ticked "Send me updates on WhatsApp" when booking are ever reached here.
 */
@Component
public class WhatsAppChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppChannel.class);

    private final boolean enabled;
    // TODO(whatsapp): read when a provider is connected (WHATSAPP_API_URL, WHATSAPP_API_TOKEN, WHATSAPP_SENDER).
    @SuppressWarnings("unused")
    private final String apiUrl;
    @SuppressWarnings("unused")
    private final String apiToken;
    @SuppressWarnings("unused")
    private final String sender;

    public WhatsAppChannel(@Value("${notifications.whatsapp.enabled:false}") boolean enabled,
                           @Value("${notifications.whatsapp.api-url:}") String apiUrl,
                           @Value("${notifications.whatsapp.api-token:}") String apiToken,
                           @Value("${notifications.whatsapp.sender:}") String sender) {
        this.enabled = enabled;
        this.apiUrl = apiUrl;
        this.apiToken = apiToken;
        this.sender = sender;
    }

    @Override
    public String name() {
        return "whatsapp";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public boolean canReach(Notification n) {
        return n.whatsappOptIn() && n.phone() != null && !n.phone().isBlank();
    }

    @Override
    public void send(Notification n) {
        // TODO(whatsapp): send n.text() to n.phone() through the chosen provider (an approved template
        // message for confirmations and reminders). Until then nothing is sent.
        log.info("WhatsApp skipped: no provider connected yet ({})", n.kind());
    }
}
