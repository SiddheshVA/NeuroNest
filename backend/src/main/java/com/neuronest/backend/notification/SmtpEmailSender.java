package com.neuronest.backend.notification;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

// Sends through the JavaMailSender Spring Boot builds from spring.mail.* (the same one OtpService
// uses): SMTP with STARTTLS and 10-second timeouts. A multipart message with the plain-text and HTML
// versions; the sender is MAIL_FROM_NAME <MAIL_USERNAME>.
@Component
public class SmtpEmailSender implements EmailSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailSettings settings;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSender, MailSettings settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    @Override
    public void send(OutgoingEmail email) throws Exception {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) throw new IllegalStateException("No mail sender is configured.");
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(settings.fromAddress(), settings.fromName());
        helper.setTo(email.to());
        helper.setSubject(email.subject());
        helper.setText(email.text(), email.html());
        sender.send(message);
    }
}
