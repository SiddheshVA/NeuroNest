package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalDayOff;
import com.neuronest.backend.professional.ProfessionalDocument;
import com.neuronest.backend.professional.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Cross-account access. A failure means one professional or one user could read or act on another's
 * data, or that a professional or the admin could change verification fields through a profile form.
 */
class CrossAccountSecurityTest extends SecuritySuiteBase {

    private Professional proA;
    private Professional proB;
    private String tokenA;
    private User userB;
    private Booking pendingOfB;
    private Booking confirmedOfB;
    private String notFoundBody;

    @BeforeEach
    void data() throws Exception {
        proA = listed("Dr. Asha Rao");
        account(proA, "pro_a");
        proB = listed("Dr. Ravi Shah");
        account(proB, "pro_b");
        tokenA = SecurityTestTokens.clerk("pro_a", "pro.a@example.com");
        userB = user("user_b", "user.b@example.com");
        Instant future = Instant.now().plus(Duration.ofDays(3));
        pendingOfB = booking(userB.getId(), proB.getId(), BookingStatus.PENDING, future, Instant.now().plus(Duration.ofDays(2)));
        pendingOfB.setAlternateDatetime(future.plus(Duration.ofHours(1)));
        pendingOfB = bookings.saveAndFlush(pendingOfB);
        confirmedOfB = booking(userB.getId(), proB.getId(), BookingStatus.CONFIRMED, future, null);
        notFoundBody = mvc.perform(get("/api/pro/bookings/" + UUID.randomUUID()).header("Authorization", bearer(tokenA)))
                .andReturn().getResponse().getContentAsString();
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(token))).andReturn();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---- Professional A and professional B ----

    @Test
    void professionalACannotReadOrActOnProfessionalBsRequests() throws Exception {
        String id = pendingOfB.getId().toString();
        String slot = Instant.now().plus(Duration.ofDays(4)).atZone(ZoneId.of("Asia/Kolkata")).withHour(10)
                .truncatedTo(ChronoUnit.HOURS).toOffsetDateTime().toString();
        List<MockHttpServletRequestBuilder> attempts = List.of(
                get("/api/pro/bookings/" + id),
                json(post("/api/pro/bookings/" + id + "/confirm"),
                        "{\"chosenSlot\":\"PREFERRED\",\"meetingMethod\":\"VIDEO\",\"meetingLink\":\"https://meet.example.com/a\"}"),
                json(post("/api/pro/bookings/" + id + "/decline"), "{}"),
                json(post("/api/pro/bookings/" + id + "/propose"), "{\"proposedSlot\":\"" + slot
                        + "\",\"meetingMethod\":\"VIDEO\",\"meetingLink\":\"https://meet.example.com/a\"}"));
        for (MockHttpServletRequestBuilder attempt : attempts) {
            MvcResult result = as(tokenA, attempt);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEqualTo(notFoundBody);
        }
        assertThat(bookings.findById(pendingOfB.getId())).get().extracting(Booking::getStatus).isEqualTo(BookingStatus.PENDING);

        String list = as(tokenA, get("/api/pro/bookings?size=50")).getResponse().getContentAsString();
        assertThat(list).doesNotContain(pendingOfB.getId().toString(), confirmedOfB.getId().toString());
        String appointments = as(tokenA, get("/api/pro/appointments")).getResponse().getContentAsString();
        assertThat(appointments).doesNotContain(confirmedOfB.getId().toString());
        // B does see them, so the lists above are not empty by accident.
        String tokenB = SecurityTestTokens.clerk("pro_b", "pro.b@example.com");
        assertThat(as(tokenB, get("/api/pro/appointments")).getResponse().getContentAsString())
                .contains(confirmedOfB.getId().toString());
    }

    @Test
    void professionalACannotSeeOrRemoveProfessionalBsDaysOff() throws Exception {
        ProfessionalDayOff ofB = daysOff.saveAndFlush(new ProfessionalDayOff(proB.getId(), LocalDate.now().plusDays(10),
                LocalDate.now().plusDays(11), "B private reason"));

        MvcResult removal = as(tokenA, delete("/api/pro/days-off/" + ofB.getId()));
        MvcResult missing = as(tokenA, delete("/api/pro/days-off/" + UUID.randomUUID()));
        assertThat(removal.getResponse().getStatus()).isEqualTo(404);
        assertThat(removal.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(daysOff.findById(ofB.getId())).isPresent();
        assertThat(as(tokenA, get("/api/pro/days-off")).getResponse().getContentAsString())
                .doesNotContain(ofB.getId().toString(), "B private reason");
    }

    @Test
    void professionalCannotSeeOrRemoveAnotherProfessionalsDocument() throws Exception {
        // Documents can only be changed while a profile is a draft, so this professional is a draft.
        Professional draft = professional("Dr. Draft Person", VerificationStatus.DRAFT, false, false);
        account(draft, "pro_draft");
        String token = SecurityTestTokens.clerk("pro_draft", "draft@example.com");
        ProfessionalDocument ofB = documents.saveAndFlush(new ProfessionalDocument(proB.getId(), DocumentType.ID_PROOF,
                "dr-ravi-shah__" + proB.getId().toString().substring(0, 8) + "/id-proof__0a1b2c3d.pdf", "b-id.pdf",
                "application/pdf", 10));

        MvcResult removal = as(token, delete("/api/pro/me/documents/" + ofB.getId()));
        MvcResult missing = as(token, delete("/api/pro/me/documents/" + UUID.randomUUID()));
        assertThat(removal.getResponse().getStatus()).isEqualTo(404);
        assertThat(removal.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(documents.findById(ofB.getId())).isPresent();
        assertThat(as(token, get("/api/pro/me")).getResponse().getContentAsString())
                .doesNotContain(ofB.getId().toString(), "b-id.pdf");
    }

    // ---- User A and user B ----

    @Test
    void userACannotReadCancelOrAcceptUserBsBooking() throws Exception {
        String tokenUserA = SecurityTestTokens.clerk("user_a", "user.a@example.com");
        Booking proposal = booking(userB.getId(), proB.getId(), BookingStatus.ALTERNATE_PROPOSED,
                Instant.now().plus(Duration.ofDays(3)), Instant.now().plus(Duration.ofDays(2)));
        proposal.setProposedDatetime(Instant.now().plus(Duration.ofDays(4)));
        proposal = bookings.saveAndFlush(proposal);
        String missing = as(tokenUserA, get("/api/bookings/" + UUID.randomUUID())).getResponse().getContentAsString();

        for (MockHttpServletRequestBuilder attempt : List.of(get("/api/bookings/" + pendingOfB.getId()),
                post("/api/bookings/" + pendingOfB.getId() + "/cancel"),
                post("/api/bookings/" + proposal.getId() + "/accept-proposal"))) {
            MvcResult result = as(tokenUserA, attempt);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEqualTo(missing);
        }
        assertThat(bookings.findById(pendingOfB.getId())).get().extracting(Booking::getStatus).isEqualTo(BookingStatus.PENDING);
        assertThat(bookings.findById(proposal.getId())).get().extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.ALTERNATE_PROPOSED);
        assertThat(as(tokenUserA, get("/api/bookings/mine")).getResponse().getContentAsString())
                .doesNotContain(pendingOfB.getId().toString());
    }

    // ---- Verification fields are the admin's decisions, never a form field ----

    private static final String VERIFICATION_FIELDS = "\"verificationStatus\":\"VERIFIED\",\"verified\":true,\"active\":true,"
            + "\"isSampleData\":true,\"sampleData\":true,\"verifiedAt\":\"2020-01-01T00:00:00Z\",\"verifiedBy\":\"me\"";

    @Test
    void aProfessionalsOwnProfileFormCannotChangeVerificationFieldsOrTheContactEmail() throws Exception {
        Professional draft = professional("Dr. Draft Person", VerificationStatus.DRAFT, false, false);
        account(draft, "pro_draft");
        Instant verifiedAtBefore = professionals.findById(draft.getId()).orElseThrow().getVerifiedAt();
        String token = SecurityTestTokens.clerk("pro_draft", "draft@example.com");

        MvcResult result = as(token, json(put("/api/pro/me"), "{" + VERIFICATION_FIELDS
                + ",\"contactEmail\":\"evil@example.com\",\"bio\":\"An updated bio for this test.\"}"));

        assertThat(result.getResponse().getStatus()).isIn(200, 400);
        Professional after = professionals.findById(draft.getId()).orElseThrow();
        assertThat(after.getVerificationStatus()).isEqualTo(VerificationStatus.DRAFT);
        assertThat(after.isVerified()).isFalse();
        assertThat(after.isActive()).isFalse();
        assertThat(after.isSampleData()).isFalse();
        assertThat(after.getVerifiedAt()).isEqualTo(verifiedAtBefore);
        assertThat(after.getVerifiedBy()).isEqualTo("ADMIN-SECRET-NAME");
        assertThat(after.getContactEmail()).isEqualTo(draft.getContactEmail());
    }

    private static String adminProfessionalBody(String name, String email) {
        return "{\"fullName\":\"" + name + "\",\"credentialType\":\"PSYCHOLOGIST\",\"qualifications\":\"M.Phil\","
                + "\"registrationBody\":\"RCI\",\"registrationNumber\":\"A123\",\"registrationYear\":2015,"
                + "\"specialties\":[\"Anxiety\"],\"city\":\"Pune\",\"consultationMode\":\"ONLINE\",\"consultationFee\":1000,"
                + "\"yearsExperience\":5,\"contactEmail\":\"" + email + "\"," + VERIFICATION_FIELDS.replace(
                        "\"isSampleData\":true,", "") + "}";
    }

    @Test
    void theAdminCreateAndUpdateFormsCannotSetVerificationStatusVerifiedOrActive() throws Exception {
        String admin = SecurityTestTokens.admin();
        MvcResult created = as(admin, json(post("/api/admin/professionals"),
                adminProfessionalBody("Dr. New Person", "new.person@example.com")));
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        UUID id = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString()).get("id").asString());
        Professional stored = professionals.findById(id).orElseThrow();
        assertThat(stored.getVerificationStatus()).isEqualTo(VerificationStatus.DRAFT);
        assertThat(stored.isVerified()).isFalse();
        assertThat(stored.isActive()).isFalse();

        Professional draft = professional("Dr. Draft Person", VerificationStatus.DRAFT, false, false);
        MvcResult updated = as(admin, json(put("/api/admin/professionals/" + draft.getId()),
                adminProfessionalBody("Dr. Draft Person", draft.getContactEmail())));
        assertThat(updated.getResponse().getStatus()).as(updated.getResponse().getContentAsString()).isEqualTo(200);
        Professional after = professionals.findById(draft.getId()).orElseThrow();
        assertThat(after.getVerificationStatus()).isEqualTo(VerificationStatus.DRAFT);
        assertThat(after.isVerified()).isFalse();
        assertThat(after.isActive()).isFalse();
    }
}
