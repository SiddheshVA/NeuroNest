package com.neuronest.backend.notification;

/** One email: a plain-text body and an HTML version of the same content. */
public record OutgoingEmail(String to, String subject, String text, String html) {}
