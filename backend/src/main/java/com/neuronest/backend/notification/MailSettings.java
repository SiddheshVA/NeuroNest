package com.neuronest.backend.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The mail settings booking emails need, from the same spring.mail.* properties the OTP mail uses
 * (MAIL_USERNAME, MAIL_PASSWORD), plus MAIL_FROM_NAME and APP_BASE_URL. Mail is enabled only when
 * notifications.email.enabled is true (the default) and both the username and the password are set.
 */
@Component
public class MailSettings {

    private final String username;
    private final String password;
    private final String fromName;
    private final String baseUrl;
    private final boolean emailNotifications;

    public MailSettings(String username, String password, String fromName, String baseUrl) {
        this(username, password, fromName, baseUrl, true);
    }

    @Autowired
    public MailSettings(@Value("${spring.mail.username:}") String username,
                        @Value("${spring.mail.password:}") String password,
                        @Value("${app.mail.from-name:NeuroNest}") String fromName,
                        @Value("${app.base-url:http://localhost:5173}") String baseUrl,
                        @Value("${notifications.email.enabled:true}") boolean emailNotifications) {
        this.emailNotifications = emailNotifications;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.fromName = fromName == null || fromName.isBlank() ? "NeuroNest" : fromName.trim();
        String base = baseUrl == null || baseUrl.isBlank() ? "http://localhost:5173" : baseUrl.trim();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public boolean enabled() {
        return emailNotifications && !username.isEmpty() && !password.isBlank();
    }

    /** The sender address (the SMTP account). */
    public String fromAddress() {
        return username;
    }

    public String fromName() {
        return fromName;
    }

    /** A link into the web app: APP_BASE_URL followed by the path (which starts with "/"). */
    public String link(String path) {
        return baseUrl + path;
    }
}
