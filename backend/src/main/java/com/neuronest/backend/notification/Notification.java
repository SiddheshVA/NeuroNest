package com.neuronest.backend.notification;

/**
 * One message to one person, ready for every channel: {@code text} is the plain version (email text part,
 * and later WhatsApp and SMS), {@code html} the email version of the same words. {@code kind} names the
 * message in logs ("CONFIRMED", "REMINDER"); nothing else from it is ever logged.
 *
 * @param phone          an Indian mobile in +91 form, or null
 * @param whatsappOptIn  the person asked for updates on WhatsApp (only clients can, when booking)
 */
public record Notification(String kind, String email, String phone, boolean whatsappOptIn, String subject,
                           String text, String html) {}
