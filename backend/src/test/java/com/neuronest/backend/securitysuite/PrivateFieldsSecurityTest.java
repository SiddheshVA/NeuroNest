package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalDayOff;
import com.neuronest.backend.professional.ProfessionalDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Private fields: what each kind of caller may see. A failure means a response carries a field or a
 * value that its caller must never receive (a professional's contact details or internal verification
 * data in the public directory, a person's contact details or notes in the admin list, or a person's
 * contact details shown to a professional outside the confirmed-appointment window).
 */
class PrivateFieldsSecurityTest extends SecuritySuiteBase {

    private static final Set<String> NEVER_PUBLIC_KEYS = Set.of("contactEmail", "contactPhone", "registrationNumber",
            "registrationCouncil", "dateOfBirth", "socialLinks", "verificationNotes", "verifiedBy", "verifiedAt",
            "documents", "storagePath", "daysOff", "adminNote", "bufferMinutes", "userId", "clerkUserId", "passwordHash",
            "inviteToken");

    private Professional asha;
    private User person;
    private ProfessionalDocument document;

    @BeforeEach
    void data() {
        asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
        document = documents.saveAndFlush(new ProfessionalDocument(asha.getId(), DocumentType.REGISTRATION_CERTIFICATE,
                "dr-asha-rao__" + asha.getId().toString().substring(0, 8) + "/registration-certificate__0a1b2c3d.pdf",
                "certificate.pdf", "application/pdf", 10));
        daysOff.saveAndFlush(new ProfessionalDayOff(asha.getId(), LocalDate.now().plusDays(40), LocalDate.now().plusDays(41),
                "DAYOFF-REASON-SECRET"));
        person = user("user_person", "person.account@example.com");
    }

    private String getOk(String path, String token) throws Exception {
        var request = get(path);
        if (token != null) request.header("Authorization", bearer(token));
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private List<String> professionalSecrets() {
        return List.of(asha.getContactEmail(), "+919812345678", "9812345678", "REGNO-SECRET-4711", "COUNCIL-SECRET",
                "1980-02-29", "secret-profile", "VERIFICATION-NOTE-SECRET", "ADMIN-SECRET-NAME", "pro_asha",
                document.getStoragePath(), "certificate.pdf", "DAYOFF-REASON-SECRET");
    }

    // ---- The public directory ----

    @Test
    void thePublicDirectoryResponsesCarryNoPrivateFieldOrValue() throws Exception {
        String from = LocalDate.now().plusDays(2).toString();
        for (String path : List.of("/api/professionals", "/api/professionals/" + asha.getId(),
                "/api/professionals/" + asha.getId() + "/availability?from=" + from + "&to=" + from, "/api/professionals/meta")) {
            String body = getOk(path, null);
            assertThat(allKeys(body)).as(path).doesNotContainAnyElementsOf(NEVER_PUBLIC_KEYS);
            assertThat(body).as(path).doesNotContain(professionalSecrets());
        }
    }

    // ---- The person's own bookings ----

    @Test
    void thePersonsBookingResponsesCarryNoProfessionalContactOrAdminNote() throws Exception {
        Instant future = Instant.now().plus(Duration.ofDays(3));
        Booking b = booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED, future, null);
        String token = SecurityTestTokens.clerk("user_person", "person.account@example.com");

        for (String path : List.of("/api/bookings/mine", "/api/bookings/" + b.getId(), "/api/bookings/summary")) {
            String body = getOk(path, token);
            assertThat(allKeys(body)).as(path).doesNotContain("adminNote", "contactEmail", "contactPhone", "userId",
                    "sessionId", "clerkUserId");
            assertThat(body).as(path).doesNotContain(asha.getContactEmail(), "+919812345678", "ADMIN-NOTE-SECRET",
                    "REGNO-SECRET-4711", "VERIFICATION-NOTE-SECRET");
        }
    }

    // ---- The admin booking list ----

    @Test
    void theAdminBookingListCarriesNoContactNoteAssessmentMeetingOrUserData() throws Exception {
        Instant future = Instant.now().plus(Duration.ofDays(3));
        Booking b = booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED, future, null);

        String body = getOk("/api/admin/bookings", SecurityTestTokens.admin());

        assertThat(JSON.readTree(body).get("items").size()).isEqualTo(1);
        assertThat(allKeys(body)).doesNotContain("contactEmail", "contactPhone", "note", "professionalNote",
                "sharedAssessment", "meetingLink", "meetingAddress", "userId", "sessionId");
        assertThat(body).doesNotContain("person.private@example.com", "9876543210", "REQUEST-NOTE-SECRET",
                "PROFESSIONAL-NOTE-TEXT", "PHQ", "moderately severe", "MEETING-LINK-SECRET", person.getId().toString(),
                b.getSessionId().toString());
    }

    // ---- The professional's view of requests ----

    @Test
    void theProfessionalSeesContactDetailsOnlyForAConfirmedAppointmentWithinSevenDays() throws Exception {
        Instant future = Instant.now().plus(Duration.ofDays(3));
        Instant open = Instant.now().plus(Duration.ofDays(2));
        record Case(Booking booking, boolean contactVisible) {}
        List<Case> cases = List.of(
                new Case(booking(person.getId(), asha.getId(), BookingStatus.PENDING, future, open), false),
                new Case(booking(person.getId(), asha.getId(), BookingStatus.DECLINED, future, open), false),
                new Case(booking(person.getId(), asha.getId(), BookingStatus.ALTERNATE_PROPOSED, future, open), false),
                new Case(booking(person.getId(), asha.getId(), BookingStatus.CANCELLED, future, open), false),
                new Case(booking(person.getId(), asha.getId(), BookingStatus.EXPIRED, future, open), false),
                new Case(booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED,
                        Instant.now().minus(Duration.ofDays(8)), null), false),
                new Case(booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED,
                        Instant.now().minus(Duration.ofDays(6)), null), true),
                new Case(booking(person.getId(), asha.getId(), BookingStatus.CONFIRMED, future, null), true));
        String token = SecurityTestTokens.clerk("pro_asha", "asha@example.com");

        for (Case c : cases) {
            String body = getOk("/api/pro/bookings/" + c.booking().getId(), token);
            JsonNode json = JSON.readTree(body);
            String label = c.booking().getStatus() + " at " + c.booking().getPreferredDatetime();
            if (c.contactVisible()) {
                assertThat(json.get("contactEmail").asString()).as(label).isEqualTo("person.private@example.com");
                assertThat(json.get("contactPhone").asString()).as(label).isEqualTo("+919876543210");
            } else {
                assertThat(json.get("contactEmail").isNull()).as(label).isTrue();
                assertThat(json.get("contactPhone").isNull()).as(label).isTrue();
                assertThat(body).as(label).doesNotContain("person.private@example.com", "9876543210");
            }
            assertThat(allKeys(body)).as(label).doesNotContain("userId", "sessionId", "adminNote", "clerkUserId");
            assertThat(body).as(label).doesNotContain("ADMIN-NOTE-SECRET", person.getId().toString(),
                    c.booking().getSessionId().toString());
        }

        String list = getOk("/api/pro/bookings?size=50", token);
        assertThat(JSON.readTree(list).get("items").size()).isEqualTo(cases.size());
        assertThat(allKeys(list)).doesNotContain("userId", "sessionId", "adminNote");
        assertThat(list).doesNotContain("ADMIN-NOTE-SECRET", person.getId().toString());
    }
}
