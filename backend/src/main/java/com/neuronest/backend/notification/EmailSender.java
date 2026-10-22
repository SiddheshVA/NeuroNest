package com.neuronest.backend.notification;

/** Sends one email. The SMTP implementation is SmtpEmailSender; tests use a fake. */
public interface EmailSender {

    void send(OutgoingEmail email) throws Exception;
}
