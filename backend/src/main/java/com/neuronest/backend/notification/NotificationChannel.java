package com.neuronest.backend.notification;

/**
 * A way to reach a person (email, WhatsApp, SMS). NotificationService sends every notification through
 * every enabled channel, so turning a channel on is a config change (notifications.&lt;name&gt;.enabled) plus
 * its own send(), never a change to the booking code.
 */
public interface NotificationChannel {

    /** "email", "whatsapp", "sms": used in logs. */
    String name();

    boolean enabled();

    /** Whether this person can be reached here (an address, a phone number, consent where needed). */
    boolean canReach(Notification n);

    void send(Notification n) throws Exception;
}
