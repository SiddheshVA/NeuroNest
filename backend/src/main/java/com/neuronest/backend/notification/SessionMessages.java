package com.neuronest.backend.notification;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.MeetingTimes;
import com.neuronest.backend.professional.Professional;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The confirmation and the reminder, to the client and to the professional: who it is with, when (the
 * meeting and the number of sessions, from MeetingTimes, never the break after it) and one link, which
 * starts with APP_BASE_URL. Never a note, an assessment or any other health detail. Each comes as plain
 * text (for every channel) and as a simple HTML email with one button.
 */
@Component
public class SessionMessages {

    static final String CONFIRMED = "CONFIRMED";
    static final String REMINDER = "REMINDER";

    private final MailSettings settings;

    public SessionMessages(MailSettings settings) {
        this.settings = settings;
    }

    /** "Hi Ravi, your session with Dr. Asha Rao is confirmed for Thu, 29 Oct 2026, 7:45 PM to 9:45 PM IST (2 sessions, 2 hours)." */
    public List<Notification> confirmed(Booking b, Professional p) {
        String when = MeetingTimes.describe(b, p);
        return List.of(
                confirmedTo(b.getContactName(), p.getFullName(), when, settings.link("/appointments"),
                        b.getContactEmail(), b.getContactPhone(), b.isWhatsappOptIn()),
                confirmedTo(p.getFullName(), b.getContactName(), when, settings.link("/pro/requests/" + b.getId()),
                        p.getContactEmail(), p.getContactPhone(), false));
    }

    /** "Hi Ravi, a gentle reminder: your session with Dr. Asha Rao starts in 10 minutes, 7:45 PM to 9:45 PM IST (2 sessions, 2 hours)." */
    public List<Notification> reminder(Booking b, Professional p, long minutesBefore) {
        String when = MeetingTimes.timeRange(b, p);
        return List.of(
                reminderTo(b.getContactName(), p.getFullName(), when, minutesBefore, settings.link("/appointments"),
                        b.getContactEmail(), b.getContactPhone(), b.isWhatsappOptIn()),
                reminderTo(p.getFullName(), b.getContactName(), when, minutesBefore,
                        settings.link("/pro/requests/" + b.getId()), p.getContactEmail(), p.getContactPhone(), false));
    }

    private static Notification confirmedTo(String name, String otherParty, String when, String link, String email,
                                            String phone, boolean whatsapp) {
        String greeting = "Hi " + name + ",";
        String body = "your session with " + otherParty + " is confirmed for " + when + ".";
        String linkIntro = "View your appointment and contact details";
        String text = greeting + " " + body + " " + linkIntro + ": " + link + ". Take care, Team NeuroNest.";
        return new Notification(CONFIRMED, email, phone, whatsapp, "NeuroNest: your session is confirmed", text,
                html("Your session is confirmed", greeting, capitalized(body), "View your appointment", link,
                        "Take care,"));
    }

    private static Notification reminderTo(String name, String otherParty, String when, long minutes, String link,
                                           String email, String phone, boolean whatsapp) {
        String greeting = "Hi " + name + ",";
        String body = "a gentle reminder: your session with " + otherParty + " starts in " + minutes + " minutes, "
                + when + ".";
        String text = greeting + " " + body + " Open it here: " + link + ". Team NeuroNest.";
        return new Notification(REMINDER, email, phone, whatsapp,
                "NeuroNest: your session starts in " + minutes + " minutes", text,
                html("Your session starts soon", greeting, capitalized(body), "Open your appointment", link, null));
    }

    private static String capitalized(String sentence) {
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1);
    }

    // A single column: heading, greeting, the message, one button, the sign-off and the usual footer.
    private static String html(String heading, String greeting, String body, String button, String link,
                               String signOff) {
        String p = "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.5\">";
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>" + BookingEmails.escape(heading)
                + "</title></head>"
                + "<body style=\"margin:0;padding:24px 12px;background:#f5f5f7;font-family:Arial,Helvetica,sans-serif;color:#1f2937\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:560px;background:#ffffff;border-radius:12px\"><tr><td style=\"padding:24px\">"
                + "<p style=\"margin:0 0 20px;font-size:18px;font-weight:bold\">NeuroNest</p>"
                + p + BookingEmails.escape(greeting) + "</p>"
                + p + BookingEmails.escape(body) + "</p>"
                + "<p style=\"margin:24px 0\"><a href=\"" + BookingEmails.escape(link) + "\" style=\"display:inline-block;"
                + "padding:12px 20px;border-radius:8px;background:#1C352D;color:#ffffff;font-size:15px;font-weight:bold;"
                + "text-decoration:none\">" + BookingEmails.escape(button) + "</a></p>"
                + p + (signOff == null ? "" : BookingEmails.escape(signOff) + "<br>") + "Team NeuroNest</p>"
                + "<p style=\"margin:24px 0 0;padding-top:16px;border-top:1px solid #e5e7eb;font-size:12px;color:#6b7280\">"
                + BookingEmails.escape(BookingEmails.FOOTER) + "</p>"
                + "</td></tr></table></td></tr></table></body></html>";
    }
}
