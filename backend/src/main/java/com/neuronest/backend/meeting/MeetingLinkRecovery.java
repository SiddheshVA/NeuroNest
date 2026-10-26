package com.neuronest.backend.meeting;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.notification.NotificationService;
import com.neuronest.backend.notification.SessionMessages;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Self-healing for a video booking whose Meet space could not be made at confirmation (flagged
 * manual_meeting_link_needed): tries again (MeetingLinkService.retryLink, up to 3 attempts) and, once the
 * space exists, emails the link to both people ("your video link is ready"), since their confirmation went
 * out without it. Used every minute by MeetingAccessJob and on demand by the admin's retry endpoint.
 */
@Service
public class MeetingLinkRecovery {

    private static final Logger log = LoggerFactory.getLogger(MeetingLinkRecovery.class);

    private final MeetingLinkService links;
    private final BookingRepository bookings;
    private final ProfessionalRepository professionals;
    private final SessionMessages messages;
    private final NotificationService notifications;

    public MeetingLinkRecovery(MeetingLinkService links, BookingRepository bookings, ProfessionalRepository professionals,
                               SessionMessages messages, NotificationService notifications) {
        this.links = links;
        this.bookings = bookings;
        this.professionals = professionals;
        this.messages = messages;
        this.notifications = notifications;
    }

    /** Tries to make the booking's link now; sends the link emails when it was made. */
    public MeetingLinkService.LinkResult retry(UUID bookingId) {
        MeetingLinkService.LinkResult result = links.retryLink(bookingId);
        if (result == MeetingLinkService.LinkResult.CREATED) {
            Booking b = bookings.findById(bookingId).orElse(null);
            Professional p = b == null ? null : professionals.findById(b.getProfessionalId()).orElse(null);
            if (b != null && p != null) {
                notifications.send(messages.linkReady(b, p));
                log.info("Meet link recovered for booking {}; link emails sent", bookingId);
            }
        }
        return result;
    }
}
