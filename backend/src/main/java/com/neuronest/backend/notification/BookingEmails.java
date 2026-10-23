package com.neuronest.backend.notification;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.safety.CrisisResource;
import com.neuronest.backend.safety.CrisisResources;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the booking emails. Subjects are neutral ("NeuroNest: ..."). No email ever contains a request
 * note, the professional's note, a shared assessment, or the other party's phone number or email
 * address; the only private detail is the meeting link or address in the user's confirmation. Every
 * value in the HTML version is escaped, and every NeuroNest link starts with APP_BASE_URL.
 */
@Component
public class BookingEmails {

    /** Who an email is for. */
    public enum Recipient { USER, PROFESSIONAL }

    public record BookingEmail(Recipient recipient, OutgoingEmail email) {}

    static final String FOOTER = "This message was sent by NeuroNest. NeuroNest is not an emergency service.";
    static final String PHONE_SENTENCE = "The professional will call you on the phone number you gave.";
    static final String SUPPORT_INTRO = "If you need support right now:";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(AvailabilityService.ZONE);

    private final MailSettings settings;

    public BookingEmails(MailSettings settings) {
        this.settings = settings;
    }

    // ---- The emails per event ----

    public List<BookingEmail> build(BookingEvent.Type type, Booking b, Professional p) {
        return build(type, b, p, List.of());
    }

    public List<BookingEmail> build(BookingEvent.Type type, Booking b, Professional p, List<Professional> similar) {
        return build(type, b, p, similar, null);
    }

    /**
     * {@code similar}: other professionals to suggest in the user's expiry email (names and profile links
     * only). {@code amountPaidPaise}: the verified payment, shown in the user's confirmation; null if none.
     */
    public List<BookingEmail> build(BookingEvent.Type type, Booking b, Professional p, List<Professional> similar,
                                    Long amountPaidPaise) {
        return switch (type) {
            case REQUEST_CREATED -> List.of(
                    toProfessional(p, new Message("NeuroNest: a new request is waiting")
                            .paragraph("You have a new appointment request from " + b.getContactName()
                                    + ". Open NeuroNest to read it and reply within 72 hours.")
                            .link("Open the request", settings.link("/pro/requests/" + b.getId()))),
                    toUser(b, new Message("NeuroNest: your request was sent")
                            .paragraph("Your request was sent to " + p.getFullName() + ". They have 72 hours to reply.")
                            .paragraph("Requested time: " + time(b.getPreferredDatetime()) + ".")
                            .link("See your requests", settings.link("/appointments"))));
            // Before payment: no meeting link or address for the user, no contact details for the
            // professional, and nothing says the appointment is confirmed.
            case AWAITING_PAYMENT -> {
                Instant at = b.scheduledTime() != null ? b.scheduledTime() : b.getPreferredDatetime();
                Message user = new Message("NeuroNest: your request was accepted")
                        .paragraph(p.getFullName() + " accepted your request for " + time(at) + ".")
                        .paragraph("Please pay by " + time(b.getPaymentDueAt()) + " to confirm your slot. If the payment "
                                + "is not made by then, the time is released.");
                Integer fee = p.getConsultationFee();
                if (fee != null && fee > 0) user.paragraph("Amount to pay: " + rupees(fee * 100L) + ".");
                user.link("Pay now", settings.link("/appointments"));
                yield List.of(
                        toUser(b, user),
                        toProfessional(p, new Message("NeuroNest: a request was accepted")
                                .paragraph("You accepted the request from " + b.getContactName() + " for " + time(at)
                                        + ". It is waiting for their payment, due by " + time(b.getPaymentDueAt())
                                        + ". NeuroNest will email you again once it is paid.")
                                .link("Open the request", settings.link("/pro/requests/" + b.getId()))));
            }
            case CONFIRMED -> {
                Instant at = b.scheduledTime() != null ? b.scheduledTime() : b.getPreferredDatetime();
                Message user = new Message("NeuroNest: your appointment is confirmed")
                        .paragraph("Your appointment with " + p.getFullName() + " is confirmed.")
                        .paragraph("Time: " + time(at) + ".");
                if (amountPaidPaise != null) user.paragraph("Amount paid: " + rupees(amountPaidPaise) + ".");
                switch (b.getMeetingMethod() == null ? "" : b.getMeetingMethod()) {
                    case "PHONE" -> user.paragraph(PHONE_SENTENCE);
                    case "VIDEO" -> user.link("Video call link", b.getMeetingLink());
                    case "IN_PERSON" -> user.paragraph("Address: " + b.getMeetingAddress());
                    default -> { }
                }
                user.link("See your appointments", settings.link("/appointments"));
                yield List.of(
                        toUser(b, user),
                        toProfessional(p, new Message("NeuroNest: an appointment is confirmed")
                                .paragraph("The appointment with " + b.getContactName() + " on " + time(at)
                                        + " is confirmed. Open NeuroNest to see the contact details.")
                                .link("Open the appointment", settings.link("/pro/requests/" + b.getId()))));
            }
            // The meeting link or address is not included until the person accepts.
            case PROPOSED -> List.of(toUser(b, new Message("NeuroNest: an update on your request")
                    .paragraph(p.getFullName() + " suggested another time: " + time(b.getProposedDatetime())
                            + ". Please accept or cancel within 72 hours.")
                    .link("See your requests", settings.link("/appointments"))));
            // The professional's note is never included.
            case DECLINED -> List.of(toUser(b, new Message("NeuroNest: an update on your request")
                    .paragraph(p.getFullName() + " could not take this appointment. You can look for another professional.")
                    .link("Find another professional", settings.link("/professionals"))
                    .lines(SUPPORT_INTRO, CrisisResources.INDIA_RESOURCES.stream().map(BookingEmails::supportLine).toList())));
            case CANCELLED_BY_USER -> List.of(toProfessional(p, new Message("NeuroNest: a request was cancelled")
                    .paragraph(b.getContactName() + " cancelled the appointment request for "
                            + time(b.getPreferredDatetime()) + ".")
                    .link("See your appointment requests", settings.link("/pro/requests"))));
            case EXPIRED -> List.of(
                    expiredToUser(b, p, similar),
                    toProfessional(p, new Message("NeuroNest: a request expired")
                            .paragraph(unpaid(b)
                                    ? "A request from " + b.getContactName() + " for " + time(b.getPreferredDatetime())
                                            + " expired because the payment was not made in time. The time is free "
                                            + "again. Open NeuroNest to see your requests."
                                    : "A request from " + b.getContactName()
                                            + " expired because it was not answered. Open NeuroNest to see your requests.")
                            .link("See your appointment requests", settings.link("/pro/requests"))));
            case EXPIRY_RETRY -> List.of(expiredToUser(b, p, similar));
            // The admin's note is never included.
            case CLOSED_BY_ADMIN -> List.of(
                    expiredToUser(b, p, similar),
                    toProfessional(p, new Message("NeuroNest: a request was closed")
                            .paragraph("A request from " + b.getContactName()
                                    + " was closed by NeuroNest. Open NeuroNest to see your requests.")
                            .link("See your appointment requests", settings.link("/pro/requests"))));
            case REMINDER -> List.of(toProfessional(p, new Message("NeuroNest: a request is still waiting")
                    .paragraph("A request from " + b.getContactName() + " is still waiting. Please reply before "
                            + time(b.getExpiresAt()) + ".")
                    .link("Open the request", settings.link("/pro/requests/" + b.getId()))));
        };
    }

    // A booking that was agreed and then expired: its payment was not made by payment_due_at.
    private static boolean unpaid(Booking b) {
        return b.getPaymentDueAt() != null;
    }

    // No note and nothing about the professional's reasons: only that it was not answered, or not paid, in time.
    private BookingEmail expiredToUser(Booking b, Professional p, List<Professional> similar) {
        Message m = new Message("NeuroNest: an update on your request")
                .paragraph(unpaid(b)
                        ? "Your booking with " + p.getFullName() + " for " + time(b.getPreferredDatetime())
                                + " was released because the payment was not made by " + time(b.getPaymentDueAt()) + "."
                        : "Your request to " + p.getFullName() + " expired because it was not answered in time. "
                                + "This is not a reflection on you.")
                .link("Find another professional", settings.link("/professionals"));
        List<Link> others = similar.stream()
                .filter(other -> other.getId() != null && !other.getId().equals(p.getId()))
                .limit(3)
                .map(other -> new Link(other.getFullName(), settings.link("/professionals/" + other.getId())))
                .toList();
        if (!others.isEmpty()) m.links("Other professionals you could contact:", others);
        m.lines(SUPPORT_INTRO, CrisisResources.INDIA_RESOURCES.stream().map(BookingEmails::supportLine).toList());
        return toUser(b, m);
    }

    /** "KIRAN Mental Health Helpline: 1800-599-0019 (Toll-free, 24/7, available in 13 languages)". */
    static String supportLine(CrisisResource r) {
        return r.name() + ": " + r.phone() + " (" + r.availability() + ")";
    }

    /** 150000 paise: "₹1,500.00" (whole rupees grouped in thousands). */
    static String rupees(long paise) {
        return String.format(Locale.ENGLISH, "\u20B9%,d.%02d", paise / 100, paise % 100);
    }

    static String time(Instant instant) {
        return instant == null ? "a time to be agreed" : TIME.format(instant) + " IST";
    }

    private static BookingEmail toUser(Booking b, Message m) {
        return new BookingEmail(Recipient.USER, m.render(b.getContactEmail()));
    }

    private static BookingEmail toProfessional(Professional p, Message m) {
        return new BookingEmail(Recipient.PROFESSIONAL, m.render(p.getContactEmail()));
    }

    // ---- A message as text blocks, rendered as plain text and as single-column HTML ----

    private sealed interface Block permits Paragraph, Link, Lines, LinkList {}

    private record Paragraph(String text) implements Block {}

    private record Link(String label, String url) implements Block {}

    private record Lines(String intro, List<String> lines) implements Block {}

    private record LinkList(String intro, List<Link> links) implements Block {}

    private static final class Message {
        private final String subject;
        private final List<Block> blocks = new ArrayList<>();

        Message(String subject) {
            this.subject = subject;
        }

        Message paragraph(String text) {
            blocks.add(new Paragraph(text));
            return this;
        }

        Message link(String label, String url) {
            if (url != null && !url.isBlank()) blocks.add(new Link(label, url));
            return this;
        }

        Message lines(String intro, List<String> lines) {
            blocks.add(new Lines(intro, lines));
            return this;
        }

        Message links(String intro, List<Link> links) {
            blocks.add(new LinkList(intro, links));
            return this;
        }

        OutgoingEmail render(String to) {
            return new OutgoingEmail(to, subject, text(), html());
        }

        private String text() {
            StringBuilder out = new StringBuilder();
            for (Block block : blocks) {
                switch (block) {
                    case Paragraph p -> out.append(p.text());
                    case Link l -> out.append(l.label()).append(": ").append(l.url());
                    case Lines l -> {
                        out.append(l.intro());
                        l.lines().forEach(line -> out.append("\n- ").append(line));
                    }
                    case LinkList l -> {
                        out.append(l.intro());
                        l.links().forEach(link -> out.append("\n- ").append(link.label()).append(": ").append(link.url()));
                    }
                }
                out.append("\n\n");
            }
            return out.append("--\n").append(FOOTER).append('\n').toString();
        }

        private String html() {
            StringBuilder body = new StringBuilder();
            for (Block block : blocks) {
                switch (block) {
                    case Paragraph p -> body.append("<p style=\"margin:0 0 16px;font-size:15px;line-height:1.5\">")
                            .append(escape(p.text())).append("</p>");
                    case Link l -> body.append("<p style=\"margin:0 0 16px;font-size:15px;line-height:1.5\">")
                            .append(escape(l.label())).append(": <a href=\"").append(escape(l.url()))
                            .append("\" style=\"color:#4f46e5;word-break:break-all\">").append(escape(l.url()))
                            .append("</a></p>");
                    case Lines l -> {
                        body.append("<p style=\"margin:0 0 8px;font-size:15px;line-height:1.5;font-weight:bold\">")
                                .append(escape(l.intro())).append("</p><ul style=\"margin:0 0 16px;padding-left:20px\">");
                        l.lines().forEach(line -> body.append("<li style=\"font-size:15px;line-height:1.5\">")
                                .append(escape(line)).append("</li>"));
                        body.append("</ul>");
                    }
                    case LinkList l -> {
                        body.append("<p style=\"margin:0 0 8px;font-size:15px;line-height:1.5;font-weight:bold\">")
                                .append(escape(l.intro())).append("</p><ul style=\"margin:0 0 16px;padding-left:20px\">");
                        l.links().forEach(link -> body.append("<li style=\"font-size:15px;line-height:1.5\"><a href=\"")
                                .append(escape(link.url())).append("\" style=\"color:#4f46e5\">")
                                .append(escape(link.label())).append("</a></li>"));
                        body.append("</ul>");
                    }
                }
            }
            return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>" + escape(subject) + "</title></head>"
                    + "<body style=\"margin:0;padding:24px 12px;background:#f5f5f7;font-family:Arial,Helvetica,sans-serif;color:#1f2937\">"
                    + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\">"
                    + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                    + "style=\"max-width:560px;background:#ffffff;border-radius:12px\"><tr><td style=\"padding:24px\">"
                    + "<p style=\"margin:0 0 20px;font-size:18px;font-weight:bold\">NeuroNest</p>"
                    + body
                    + "<p style=\"margin:24px 0 0;padding-top:16px;border-top:1px solid #e5e7eb;font-size:12px;color:#6b7280\">"
                    + escape(FOOTER) + "</p>"
                    + "</td></tr></table></td></tr></table></body></html>";
        }
    }

    static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
