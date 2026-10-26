package com.neuronest.backend.notification;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.MeetingTimes;
import com.neuronest.backend.meeting.MeetingWindow;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.safety.CrisisResources;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The confirmation, the "your video link is ready" follow-up and the reminder, to the client and to the
 * professional. When (the meeting and the number of sessions, from MeetingTimes, never the break after it),
 * who with, and for a video session the shared Meet link (both people get the same link, and nobody has to
 * admit anyone). Every message also links to the NeuroNest booking page, which starts with APP_BASE_URL.
 * Never a note, an assessment, a phone number or any other health detail. Each comes as plain text (for
 * every channel) and as a simple HTML email.
 */
@Component
public class SessionMessages {

    static final String CONFIRMED = "CONFIRMED";
    static final String LINK_READY = "LINK_READY";
    static final String REMINDER = "REMINDER";

    private final MailSettings settings;
    private final MeetingWindow window;

    public SessionMessages(MailSettings settings, MeetingWindow window) {
        this.settings = settings;
        this.window = window;
    }

    // ---- The confirmation ----

    /**
     * Both confirmations. For a video session with its link, the link is in them; if the link could not be
     * made yet, they say so and {@link #linkReady} follows once it exists.
     */
    public List<Notification> confirmed(Booking b, Professional p) {
        return List.of(confirmedToClient(b, p), confirmedToProfessional(b, p));
    }

    private Notification confirmedToClient(Booking b, Professional p) {
        String pro = p.getFullName();
        Email m = new Email("Your session with " + pro + " is confirmed")
                .paragraph("Hi " + firstName(b.getContactName()) + ", thank you for taking this step.")
                .paragraph("Your session with " + pro + " is booked for " + MeetingTimes.describe(b, p) + ".");
        if (videoLink(b) != null) {
            m.paragraph("Your private video link is below. " + clientTiming(b)).join(videoLink(b));
        } else if (b.isVideo()) {
            m.paragraph("We're setting up your private video link. It usually takes a minute, and we'll email it to "
                    + "you as soon as it's ready. It will also be on your booking page.");
        } else if ("PHONE".equals(method(b))) {
            m.paragraph(pro + " will call you on the phone number you gave.");
        }
        return m.paragraph("You don't need to prepare anything. If you'd like to share something with " + pro
                        + ", you can just say it at the start.")
                .backup("View your booking", settings.link("/appointments"))
                .signOff("Take care,", "NeuroNest")
                .crisisLines()
                .to(CONFIRMED, b.getContactEmail(), b.getContactPhone(), b.isWhatsappOptIn());
    }

    private Notification confirmedToProfessional(Booking b, Professional p) {
        String client = firstName(b.getContactName());
        Email m = new Email("New session at " + MeetingTimes.startForSubject(b.getPreferredDatetime()) + ": " + client)
                .paragraph("Hi " + p.getFullName() + ", a new session is booked for " + MeetingTimes.describe(b, p) + ".");
        if (videoLink(b) != null) {
            m.paragraph("Your video link is below. " + professionalTiming(b)).join(videoLink(b));
        } else if (b.isVideo()) {
            m.paragraph("We're setting up the video link. You'll get it by email as soon as it's ready, usually "
                    + "within a minute.");
        } else if ("PHONE".equals(method(b))) {
            m.paragraph("Please call " + client + " on the phone number on your NeuroNest booking page.");
        }
        return m.paragraph("The client's contact details and note are available on your NeuroNest booking page.")
                .paragraph("Thank you for the care you give.")
                .backup("Open your booking page", settings.link("/pro/requests/" + b.getId()))
                .signOff("NeuroNest")
                .to(CONFIRMED, p.getContactEmail(), p.getContactPhone(), false);
    }

    // ---- The link, when it could not be made at confirmation ----

    /** "Your video link is ready", to both, once a link that failed at confirmation has been made. */
    public List<Notification> linkReady(Booking b, Professional p) {
        String pro = p.getFullName();
        String client = firstName(b.getContactName());
        String when = MeetingTimes.describe(b, p);
        Notification toClient = new Email("Your video link for your session with " + pro + " is ready")
                .paragraph("Hi " + client + ", your private video link for your session with " + pro + " on " + when
                        + " is ready.")
                .paragraph(clientTiming(b))
                .join(videoLink(b))
                .backup("View your booking", settings.link("/appointments"))
                .signOff("Take care,", "NeuroNest")
                .crisisLines()
                .to(LINK_READY, b.getContactEmail(), b.getContactPhone(), b.isWhatsappOptIn());
        Notification toProfessional = new Email("Video link ready: session at "
                + MeetingTimes.startForSubject(b.getPreferredDatetime()) + ": " + client)
                .paragraph("Hi " + pro + ", the video link for your session with " + client + " on " + when + " is ready.")
                .paragraph(professionalTiming(b))
                .join(videoLink(b))
                .backup("Open your booking page", settings.link("/pro/requests/" + b.getId()))
                .signOff("NeuroNest")
                .to(LINK_READY, p.getContactEmail(), p.getContactPhone(), false);
        return List.of(toClient, toProfessional);
    }

    // The window, in words. Inside it (a last-minute booking): "You can join now".
    private String clientTiming(Booking b) {
        if (window.isOpenNow(b)) return "You can join now. The link closes when the session ends.";
        return "It becomes active " + openEarlyMinutes() + " minutes before your session and closes when the session "
                + "ends, so if you open it earlier it won't let you in yet. You can join a few minutes early, find a "
                + "quiet spot, and take a breath.";
    }

    private String professionalTiming(Booking b) {
        if (window.isOpenNow(b)) return "You can join now. The link closes at the end of the session.";
        return "It becomes active " + openEarlyMinutes() + " minutes before the start and closes at the end of the "
                + "session; opened earlier, it won't let you in yet.";
    }

    private long openEarlyMinutes() {
        return window.openEarly().toMinutes();
    }

    // The Meet link both people share, for a video session that has one.
    private static String videoLink(Booking b) {
        return b.isVideo() ? b.getMeetingLink() : null;
    }

    private static String method(Booking b) {
        return b.getMeetingMethod() != null ? b.getMeetingMethod() : b.getPreferredMethod();
    }

    /** "Ravi" from "Ravi Kumar". */
    static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "there";
        return fullName.trim().split("\\s+")[0];
    }

    // ---- The reminder ----

    /** "Hi Ravi, a gentle reminder: your session with Dr. Asha Rao starts in 10 minutes, 7:45 PM to 9:45 PM IST (2 sessions, 2 hours)." */
    public List<Notification> reminder(Booking b, Professional p, long minutesBefore) {
        String when = MeetingTimes.timeRange(b, p);
        String joinNote = joinNote(b);
        return List.of(
                reminderTo(b.getContactName(), p.getFullName(), when, joinNote, minutesBefore,
                        settings.link("/appointments"), b.getContactEmail(), b.getContactPhone(), b.isWhatsappOptIn()),
                reminderTo(p.getFullName(), b.getContactName(), when, joinNote, minutesBefore,
                        settings.link("/pro/requests/" + b.getId()), p.getContactEmail(), p.getContactPhone(), false));
    }

    /**
     * For a video session: "Your join button becomes active at Thu, 29 Oct 2026, 7:40 PM (IST).", or "You can
     * join now." when its window is already open (a last-minute booking). Null otherwise.
     */
    private String joinNote(Booking b) {
        if (!b.isVideo() || window.opensAt(b) == null) return null;
        if (window.isOpenNow(b)) return "You can join now.";
        return "Your join button becomes active at " + MeetingTimes.dayAndTime(window.opensAt(b)) + " (IST).";
    }

    private static Notification reminderTo(String name, String otherParty, String when, String joinNote, long minutes,
                                           String link, String email, String phone, boolean whatsapp) {
        String greeting = "Hi " + name + ",";
        String body = "a gentle reminder: your session with " + otherParty + " starts in " + minutes + " minutes, "
                + when + ".";
        String text = greeting + " " + body + (joinNote == null ? "" : " " + joinNote) + " Open it here: " + link
                + ". Team NeuroNest.";
        Email m = new Email("NeuroNest: your session starts in " + minutes + " minutes")
                .paragraph(greeting)
                .paragraph(Character.toUpperCase(body.charAt(0)) + body.substring(1));
        if (joinNote != null) m.strong(joinNote);
        return m.primary("Open your appointment", link)
                .signOff("Team NeuroNest")
                .to(REMINDER, email, phone, whatsapp, text);
    }

    // ---- One message: paragraphs, a big button, the link as text, a backup button, sign-off, support lines ----

    private static final String P = "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.5\">";

    private static final class Email {
        private final String subject;
        private final List<String[]> paragraphs = new ArrayList<>();
        private String primaryLabel;
        private String primaryUrl;
        private boolean showUrl;
        private String backupLabel;
        private String backupUrl;
        private String[] signOff = new String[0];
        private boolean crisisLines;

        Email(String subject) {
            this.subject = subject;
        }

        Email paragraph(String text) {
            paragraphs.add(new String[]{text, "plain"});
            return this;
        }

        Email strong(String text) {
            paragraphs.add(new String[]{text, "strong"});
            return this;
        }

        /** The "Join your session" button and, under it, the same link as text. */
        Email join(String url) {
            this.primaryLabel = "Join your session";
            this.primaryUrl = url;
            this.showUrl = true;
            return this;
        }

        Email primary(String label, String url) {
            this.primaryLabel = label;
            this.primaryUrl = url;
            return this;
        }

        Email backup(String label, String url) {
            this.backupLabel = label;
            this.backupUrl = url;
            return this;
        }

        Email signOff(String... lines) {
            this.signOff = lines;
            return this;
        }

        Email crisisLines() {
            this.crisisLines = true;
            return this;
        }

        Notification to(String kind, String email, String phone, boolean whatsapp) {
            return to(kind, email, phone, whatsapp, text());
        }

        Notification to(String kind, String email, String phone, boolean whatsapp, String text) {
            return new Notification(kind, email, phone, whatsapp, subject, text, html());
        }

        private static List<String> supportLines() {
            return CrisisResources.INDIA_RESOURCES.stream().map(BookingEmails::supportLine).toList();
        }

        // Paragraphs, then the links, then the sign-off: readable on its own (and later as WhatsApp or SMS).
        private String text() {
            StringBuilder out = new StringBuilder();
            for (String[] p : paragraphs) out.append(p[0]).append("\n\n");
            if (primaryUrl != null) out.append(primaryLabel).append(": ").append(primaryUrl).append("\n\n");
            if (backupUrl != null) out.append(backupLabel).append(": ").append(backupUrl).append("\n\n");
            out.append(String.join("\n", signOff));
            if (crisisLines) {
                out.append("\n\n").append(BookingEmails.SUPPORT_INTRO);
                supportLines().forEach(line -> out.append("\n- ").append(line));
            }
            return out.toString();
        }

        private String html() {
            StringBuilder body = new StringBuilder();
            for (String[] p : paragraphs) {
                String text = BookingEmails.escape(p[0]);
                body.append(P).append("strong".equals(p[1]) ? "<strong>" + text + "</strong>" : text).append("</p>");
            }
            if (primaryUrl != null) {
                body.append("<p style=\"margin:24px 0 8px\"><a href=\"").append(BookingEmails.escape(primaryUrl))
                        .append("\" style=\"display:inline-block;padding:14px 24px;border-radius:8px;background:#1C352D;"
                                + "color:#ffffff;font-size:16px;font-weight:bold;text-decoration:none\">")
                        .append(BookingEmails.escape(primaryLabel)).append("</a></p>");
                if (showUrl) {
                    body.append("<p style=\"margin:0 0 16px;font-size:13px;line-height:1.5;color:#4b5563\">Or open this "
                                    + "link: <a href=\"").append(BookingEmails.escape(primaryUrl))
                            .append("\" style=\"color:#1C352D;word-break:break-all\">")
                            .append(BookingEmails.escape(primaryUrl)).append("</a></p>");
                }
            }
            if (backupUrl != null) {
                body.append("<p style=\"margin:16px 0 24px\"><a href=\"").append(BookingEmails.escape(backupUrl))
                        .append("\" style=\"display:inline-block;padding:10px 18px;border-radius:8px;border:1px solid "
                                + "#1C352D;color:#1C352D;font-size:14px;font-weight:bold;text-decoration:none\">")
                        .append(BookingEmails.escape(backupLabel)).append("</a></p>");
            }
            body.append(P).append(String.join("<br>",
                    Arrays.stream(signOff).map(BookingEmails::escape).toArray(String[]::new))).append("</p>");
            if (crisisLines) {
                body.append("<p style=\"margin:16px 0 8px;font-size:13px;font-weight:bold\">")
                        .append(BookingEmails.escape(BookingEmails.SUPPORT_INTRO))
                        .append("</p><ul style=\"margin:0 0 8px;padding-left:20px;font-size:13px;line-height:1.5\">");
                supportLines().forEach(line -> body.append("<li>").append(BookingEmails.escape(line)).append("</li>"));
                body.append("</ul>");
            }
            return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>" + BookingEmails.escape(subject)
                    + "</title></head>"
                    + "<body style=\"margin:0;padding:24px 12px;background:#f5f5f7;font-family:Arial,Helvetica,sans-serif;color:#1f2937\">"
                    + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\">"
                    + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                    + "style=\"max-width:560px;background:#ffffff;border-radius:12px\"><tr><td style=\"padding:24px\">"
                    + "<p style=\"margin:0 0 20px;font-size:18px;font-weight:bold\">NeuroNest</p>"
                    + body
                    + "<p style=\"margin:24px 0 0;padding-top:16px;border-top:1px solid #e5e7eb;font-size:12px;color:#6b7280\">"
                    + BookingEmails.escape(BookingEmails.FOOTER) + "</p>"
                    + "</td></tr></table></td></tr></table></body></html>";
        }
    }
}
