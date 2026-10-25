package com.neuronest.backend.notification;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationServiceTest {

    private static final Notification CLIENT = new Notification("CONFIRMED", "ravi@example.com", "+919876543210", false,
            "Subject", "Hi Ravi", "<p>Hi Ravi</p>");
    private static final Notification CLIENT_ON_WHATSAPP = new Notification("CONFIRMED", "ravi@example.com",
            "+919876543210", true, "Subject", "Hi Ravi", "<p>Hi Ravi</p>");

    /** A channel that records what it was asked to send, and can fail. */
    private static final class Recording implements NotificationChannel {
        final String name;
        final boolean enabled;
        final List<Notification> sent = new ArrayList<>();
        int failuresLeft;

        Recording(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }

        public String name() { return name; }
        public boolean enabled() { return enabled; }
        public boolean canReach(Notification n) { return n.email() != null; }

        public void send(Notification n) throws Exception {
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new java.io.IOException("down");
            }
            sent.add(n);
        }
    }

    @Test
    void everyEnabledChannelGetsItAndADisabledOneNothing() {
        Recording email = new Recording("email", true);
        Recording sms = new Recording("sms", false);

        new NotificationService(List.of(email, sms), mock(MailRetryScheduler.class)).send(List.of(CLIENT));

        assertThat(email.sent).containsExactly(CLIENT);
        assertThat(sms.sent).isEmpty();
    }

    @Test
    void aFailingChannelNeverStopsTheOthersAndIsRetriedOnce() {
        Recording email = new Recording("email", true);
        email.failuresLeft = 1;
        Recording other = new Recording("other", true);
        MailRetryScheduler retries = mock(MailRetryScheduler.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(retries).schedule(any(), eq(NotificationService.RETRY_AFTER));

        new NotificationService(List.of(email, other), retries).send(List.of(CLIENT));

        assertThat(other.sent).containsExactly(CLIENT);
        assertThat(email.sent).containsExactly(CLIENT);
        verify(retries).schedule(any(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void whatsAppAndSmsAreOffByDefaultAndWhatsAppNeedsTheClientsConsent() {
        WhatsAppChannel whatsapp = new WhatsAppChannel(false, "", "", "");
        SmsChannel sms = new SmsChannel(false, "", "", "");
        assertThat(whatsapp.enabled()).isFalse();
        assertThat(sms.enabled()).isFalse();

        assertThat(whatsapp.canReach(CLIENT)).isFalse();
        assertThat(whatsapp.canReach(CLIENT_ON_WHATSAPP)).isTrue();
        assertThat(sms.canReach(CLIENT)).isTrue();
        // Turned on before a provider is connected: nothing is sent and nothing fails.
        new WhatsAppChannel(true, "", "", "").send(CLIENT_ON_WHATSAPP);
    }

    @Test
    void notificationsEmailEnabledFalseTurnsEmailOff() {
        MailSettings off = new MailSettings("noreply@neuronest.example", "app-password", "NeuroNest", "https://app", false);
        assertThat(new EmailChannel(n -> { }, off).enabled()).isFalse();
        MailSettings on = new MailSettings("noreply@neuronest.example", "app-password", "NeuroNest", "https://app");
        assertThat(new EmailChannel(n -> { }, on).enabled()).isTrue();
    }
}
