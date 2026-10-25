package com.neuronest.backend.notification;

import org.springframework.stereotype.Component;

/** Email through the existing SMTP sender. On when notifications.email.enabled and the mail account is set. */
@Component
public class EmailChannel implements NotificationChannel {

    private final EmailSender sender;
    private final MailSettings settings;

    public EmailChannel(EmailSender sender, MailSettings settings) {
        this.sender = sender;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean enabled() {
        return settings.enabled();
    }

    @Override
    public boolean canReach(Notification n) {
        return n.email() != null && !n.email().isBlank();
    }

    @Override
    public void send(Notification n) throws Exception {
        // The email's text part ends with the same footer as every other booking email (the HTML has it).
        sender.send(new OutgoingEmail(n.email(), n.subject(), n.text() + "\n\n--\n" + BookingEmails.FOOTER + "\n",
                n.html()));
    }
}
