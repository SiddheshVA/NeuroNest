package com.neuronest.backend.notification;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

// Sends either via Google Apps Script Webhook (when MAIL_WEBHOOK_URL is set, bypassing cloud SMTP port blocks),
// or through the JavaMailSender Spring Boot builds from spring.mail.* (SMTP with STARTTLS).
@Component
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailSettings settings;
    private final String webhookUrl;
    private final HttpClient httpClient;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSender,
                           MailSettings settings,
                           @Value("${MAIL_WEBHOOK_URL:}") String webhookUrl) {
        this.mailSender = mailSender;
        this.settings = settings;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public void send(OutgoingEmail email) throws Exception {
        if (!webhookUrl.isBlank()) {
            sendViaWebhook(email);
            return;
        }
        sendViaSmtp(email);
    }

    private void sendViaWebhook(OutgoingEmail email) throws Exception {
        Map<String, String> payload = Map.of(
                "to", email.to(),
                "subject", email.subject(),
                "text", email.text() != null ? email.text() : "",
                "html", email.html() != null ? email.html() : ""
        );

        String json = JSON_MAPPER.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("Webhook mail failed with HTTP " + response.statusCode() + ": " + response.body());
        }
        log.info("Email sent successfully via Google Webhook to {}", email.to());
    }

    private void sendViaSmtp(OutgoingEmail email) throws Exception {
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
