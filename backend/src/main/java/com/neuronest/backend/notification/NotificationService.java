package com.neuronest.backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Sends each notification through every enabled channel that can reach the person. A channel that fails
 * never stops the others and is retried once, 30 seconds later. Logs carry the message kind, the channel
 * and the exception class only: never an address, a phone number or any text.
 */
@Service
public class NotificationService {

    static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final List<NotificationChannel> channels;
    private final MailRetryScheduler retries;

    public NotificationService(List<NotificationChannel> channels, MailRetryScheduler retries) {
        this.channels = List.copyOf(channels);
        this.retries = retries;
    }

    public void send(List<Notification> notifications) {
        for (Notification n : notifications) {
            for (NotificationChannel channel : channels) {
                if (!channel.enabled()) continue;
                if (!channel.canReach(n)) {
                    log.debug("{} skipped: recipient not reachable here ({})", channel.name(), n.kind());
                    continue;
                }
                attempt(channel, n, true);
            }
        }
    }

    private void attempt(NotificationChannel channel, Notification n, boolean retryOnFailure) {
        try {
            channel.send(n);
        } catch (Exception e) {
            log.warn("{} failed ({}): {}{}", channel.name(), n.kind(), e.getClass().getName(),
                    retryOnFailure ? "; retrying once in 30 seconds" : "");
            if (!retryOnFailure) return;
            try {
                retries.schedule(() -> attempt(channel, n, false), RETRY_AFTER);
            } catch (RuntimeException scheduleFailure) {
                log.warn("{} retry not scheduled ({}): {}", channel.name(), n.kind(), scheduleFailure.getClass().getName());
            }
        }
    }
}
