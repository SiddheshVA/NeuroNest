package com.neuronest.backend.securitysuite;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.professional.Professional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Reviews over HTTP with the production security chains and real repositories: who may review (only the
 * booker, only a CONFIRMED booking whose session has ended, once), validation, the rating numbers after
 * every change, admin-only hiding, that no reviewer identity is ever returned, and the directory's sort
 * orders including the smoothed "Top rated".
 */
class ReviewSecurityTest extends SecuritySuiteBase {

    private Professional asha;
    private User owner;
    private String ownerToken;
    private String otherToken;
    private String proToken;
    private Booking past;

    @BeforeEach
    void data() {
        asha = listed("Dr. Asha Rao");
        account(asha, "pro_asha");
        owner = user("user_owner", "owner@example.com");
        user("user_other", "other@example.com");
        ownerToken = SecurityTestTokens.clerk("user_owner", "owner@example.com");
        otherToken = SecurityTestTokens.clerk("user_other", "other@example.com");
        proToken = SecurityTestTokens.clerk("pro_asha", asha.getContactEmail());
        // The session (60 minutes) ended an hour ago.
        past = confirmedAt(owner, asha, Instant.now().minus(Duration.ofHours(2)));
    }

    private Booking confirmedAt(User user, Professional p, Instant scheduled) {
        return booking(user.getId(), p.getId(), BookingStatus.CONFIRMED, scheduled, null);
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request).andReturn();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String review(Integer rating, String comment) {
        return "{\"rating\":" + rating + (comment == null ? "" : ",\"comment\":" + JSON.writeValueAsString(comment)) + "}";
    }

    private int create(String token, UUID bookingId, String body) throws Exception {
        return as(token, json(post("/api/bookings/" + bookingId + "/review"), body)).getResponse().getStatus();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listing(Professional p) throws Exception {
        return body(as(null, get("/api/professionals/" + p.getId())));
    }

    private JsonNode publicReviews(Professional p) throws Exception {
        return body(as(null, get("/api/professionals/" + p.getId() + "/reviews")));
    }

    // ---- Who may review ----

    @Test
    void onlyTheBookerOfAConfirmedEndedSessionCanReviewAndOnlyOnce() throws Exception {
        String body = review(5, "Very kind and patient.");

        assertThat(create(null, past.getId(), body)).isEqualTo(401);
        assertThat(create(otherToken, past.getId(), body)).isEqualTo(404);
        assertThat(create(proToken, past.getId(), body)).as("the professional is not the booker").isEqualTo(404);
        assertThat(create(SecurityTestTokens.admin(), past.getId(), body)).isEqualTo(401);
        assertThat(create(ownerToken, UUID.randomUUID(), body)).isEqualTo(404);
        assertThat(as(ownerToken, json(post("/api/bookings/not-a-uuid/review"), body)).getResponse().getStatus())
                .isEqualTo(404);

        Instant longAgo = Instant.now().minus(Duration.ofDays(3));
        for (BookingStatus status : List.of(BookingStatus.PENDING, BookingStatus.AWAITING_PAYMENT, BookingStatus.EXPIRED,
                BookingStatus.CANCELLED, BookingStatus.DECLINED, BookingStatus.ALTERNATE_PROPOSED)) {
            Booking notConfirmed = booking(owner.getId(), asha.getId(), status, longAgo, longAgo);
            assertThat(create(ownerToken, notConfirmed.getId(), body)).as(status.name()).isEqualTo(409);
        }
        Booking inProgress = confirmedAt(owner, asha, Instant.now().minus(Duration.ofMinutes(30)));
        assertThat(create(ownerToken, inProgress.getId(), body)).as("session not over yet").isEqualTo(409);
        Booking upcoming = confirmedAt(owner, asha, Instant.now().plus(Duration.ofDays(2)));
        assertThat(create(ownerToken, upcoming.getId(), body)).isEqualTo(409);

        assertThat(create(ownerToken, past.getId(), body)).isEqualTo(201);
        MvcResult again = as(ownerToken, json(post("/api/bookings/" + past.getId() + "/review"), review(4, null)));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentAsString()).contains("already rated");
        assertThat(reviews.count()).isEqualTo(1);
    }

    @Test
    void theReviewableListHasOnlyMyEndedConfirmedSessions() throws Exception {
        confirmedAt(owner, asha, Instant.now().plus(Duration.ofDays(2)));
        booking(owner.getId(), asha.getId(), BookingStatus.CANCELLED, Instant.now().minus(Duration.ofDays(3)), null);
        User other = users.findAll().stream().filter(u -> "user_other".equals(u.getClerkUserId())).findFirst().orElseThrow();
        confirmedAt(other, asha, Instant.now().minus(Duration.ofDays(1)));

        JsonNode list = body(as(ownerToken, get("/api/bookings/reviews")));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("bookingId").asString()).isEqualTo(past.getId().toString());
        assertThat(list.get(0).get("canReview").asBoolean()).isTrue();

        create(ownerToken, past.getId(), review(4, "Helpful."));
        JsonNode after = body(as(ownerToken, get("/api/bookings/reviews"))).get(0);
        assertThat(after.get("canReview").asBoolean()).isFalse();
        assertThat(after.get("review").get("rating").asInt()).isEqualTo(4);
        assertThat(as(null, get("/api/bookings/reviews")).getResponse().getStatus()).isEqualTo(401);
    }

    // ---- Validation ----

    @Test
    void ratingCommentLengthAndContactDetailsAreChecked() throws Exception {
        for (String bad : List.of(review(0, null), review(6, null), "{\"comment\":\"no rating\"}", "{}")) {
            assertThat(create(ownerToken, past.getId(), bad)).as(bad).isEqualTo(400);
        }
        assertThat(create(ownerToken, past.getId(), review(5, "x".repeat(501)))).isEqualTo(400);
        for (String contact : List.of("Call me on 98765 43210", "Write to me at ravi.k@example.com",
                "See www.example.com", "My insta: @ravi_k_22", "+91 98765-43210")) {
            MvcResult result = as(ownerToken, json(post("/api/bookings/" + past.getId() + "/review"), review(5, contact)));
            assertThat(result.getResponse().getStatus()).as(contact).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("remove phone numbers");
        }
        assertThat(reviews.count()).isZero();

        assertThat(create(ownerToken, past.getId(), review(5, "x".repeat(500)))).isEqualTo(201);
    }

    @Test
    void aBlankCommentIsStoredAsNoComment() throws Exception {
        assertThat(create(ownerToken, past.getId(), review(3, "   "))).isEqualTo(201);
        assertThat(publicReviews(asha).get("items").get(0).get("comment").isNull()).isTrue();
    }

    // ---- The numbers ----

    @Test
    void averageAndCountFollowCreateEditDeleteHideAndUnhide() throws Exception {
        assertThat(listing(asha).get("ratingCount").asInt()).isZero();
        assertThat(listing(asha).get("ratingAvg").isNull()).isTrue();

        create(ownerToken, past.getId(), review(4, null));
        assertRating(asha, "4.00", 1);

        Booking second = confirmedAt(owner, asha, Instant.now().minus(Duration.ofDays(7)));
        create(ownerToken, second.getId(), review(2, null));
        assertRating(asha, "3.00", 2);

        as(ownerToken, json(put("/api/bookings/" + second.getId() + "/review"), review(5, "Better than I said.")));
        assertRating(asha, "4.50", 2);

        assertThat(as(ownerToken, delete("/api/bookings/" + past.getId() + "/review")).getResponse().getStatus())
                .isEqualTo(204);
        assertRating(asha, "5.00", 1);

        UUID reviewId = reviews.findByBookingId(second.getId()).orElseThrow().getId();
        assertThat(moderate(reviewId, "hide", "Mentions another client.").getResponse().getStatus()).isEqualTo(200);
        assertThat(listing(asha).get("ratingAvg").isNull()).isTrue();
        assertThat(listing(asha).get("ratingCount").asInt()).isZero();
        assertThat(publicReviews(asha).get("totalElements").asInt()).isZero();

        assertThat(moderate(reviewId, "unhide", "Checked again, fine.").getResponse().getStatus()).isEqualTo(200);
        assertRating(asha, "5.00", 1);
        assertThat(publicReviews(asha).get("items").get(0).get("comment").asString()).isEqualTo("Better than I said.");
    }

    private void assertRating(Professional p, String avg, int count) throws Exception {
        JsonNode listing = listing(p);
        assertThat(listing.get("ratingAvg").decimalValue()).isEqualByComparingTo(avg);
        assertThat(listing.get("ratingCount").asInt()).isEqualTo(count);
        JsonNode inDirectory = body(as(null, get("/api/professionals"))).get("items").get(0);
        assertThat(inDirectory.get("ratingCount").asInt()).isEqualTo(count);
    }

    // ---- Moderation is admin only; a hidden review is locked ----

    private MvcResult moderate(UUID reviewId, String action, String reason) throws Exception {
        return as(SecurityTestTokens.admin(), json(post("/api/admin/reviews/" + reviewId + "/" + action),
                "{\"reason\":" + JSON.writeValueAsString(reason) + "}"));
    }

    @Test
    void onlyAnAdminCanHideAndAHiddenReviewIsLockedForItsAuthor() throws Exception {
        create(ownerToken, past.getId(), review(1, "Did not listen."));
        UUID reviewId = reviews.findByBookingId(past.getId()).orElseThrow().getId();
        String hide = "/api/admin/reviews/" + reviewId + "/hide";

        for (String token : new String[] {null, ownerToken, otherToken, proToken}) {
            assertThat(as(token, json(post(hide), "{\"reason\":\"x\"}")).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(as(SecurityTestTokens.admin(), json(post(hide), "{}")).getResponse().getStatus()).isEqualTo(400);
        assertThat(as(SecurityTestTokens.admin(), json(post(hide), "{\"reason\":\"   \"}")).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(moderate(reviewId, "hide", "Personal details about the professional.").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(moderate(reviewId, "hide", "Again.").getResponse().getStatus()).isEqualTo(409);

        JsonNode adminList = body(as(SecurityTestTokens.admin(), get("/api/admin/professionals/" + asha.getId() + "/reviews")));
        assertThat(adminList.get("items").get(0).get("status").asString()).isEqualTo("HIDDEN");
        assertThat(adminList.get("items").get(0).get("moderationReason").asString())
                .isEqualTo("Personal details about the professional.");

        // The author cannot edit or delete it away (and so cannot write a fresh visible one).
        assertThat(as(ownerToken, json(put("/api/bookings/" + past.getId() + "/review"), review(5, null)))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(as(ownerToken, delete("/api/bookings/" + past.getId() + "/review")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(reviews.findById(reviewId).orElseThrow().getRating()).isEqualTo(1);
        assertThat(as(null, get("/api/admin/professionals/" + asha.getId() + "/reviews")).getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void nobodyButTheAuthorCanEditOrDeleteAReview() throws Exception {
        create(ownerToken, past.getId(), review(4, "Good."));
        String path = "/api/bookings/" + past.getId() + "/review";
        for (String token : new String[] {otherToken, proToken}) {
            assertThat(as(token, json(put(path), review(1, "Changed."))).getResponse().getStatus()).isEqualTo(404);
            assertThat(as(token, delete(path)).getResponse().getStatus()).isEqualTo(404);
        }
        assertThat(as(SecurityTestTokens.admin(), json(put(path), review(1, null))).getResponse().getStatus()).isEqualTo(401);
        // The professional's portal endpoint is read-only.
        for (MockHttpServletRequestBuilder write : List.of(post("/api/pro/me/reviews"), put("/api/pro/me/reviews"),
                delete("/api/pro/me/reviews"))) {
            assertThat(as(proToken, write).getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        }
        assertThat(reviews.findByBookingId(past.getId()).orElseThrow().getComment()).isEqualTo("Good.");
        assertThat(reviews.count()).isEqualTo(1);
    }

    // ---- Privacy ----

    @Test
    void noReviewResponseEverNamesTheReviewer() throws Exception {
        create(ownerToken, past.getId(), review(5, "Calm and clear."));

        List<String> bodies = new ArrayList<>();
        bodies.add(as(null, get("/api/professionals/" + asha.getId() + "/reviews")).getResponse().getContentAsString());
        bodies.add(as(proToken, get("/api/pro/me/reviews")).getResponse().getContentAsString());
        bodies.add(as(SecurityTestTokens.admin(), get("/api/admin/professionals/" + asha.getId() + "/reviews"))
                .getResponse().getContentAsString());
        bodies.add(as(null, get("/api/professionals/" + asha.getId())).getResponse().getContentAsString());
        for (String body : bodies) {
            assertThat(allKeys(body)).doesNotContain("userId", "contactName", "contactEmail", "contactPhone", "email",
                    "name", "clerkUserId", "sessionId");
            assertThat(body).doesNotContain("Ravi Kumar", "person.private@example.com", "9876543210", "owner@example.com",
                    owner.getId().toString());
        }
        JsonNode item = JSON.readTree(bodies.get(0)).get("items").get(0);
        assertThat(allKeys(bodies.get(0))).containsExactlyInAnyOrder("items", "rating", "comment", "month", "page",
                "size", "totalElements", "totalPages");
        assertThat(item.get("month").asString()).matches("\\d{4}-\\d{2}");

        JsonNode pro = JSON.readTree(bodies.get(1));
        assertThat(pro.get("ratingCount").asInt()).isEqualTo(1);
        assertThat(pro.get("reviews").get("items").get(0).get("comment").asString()).isEqualTo("Calm and clear.");
    }

    @Test
    void aProfessionalSeesOnlyTheirOwnReviewsAndOnlyVisibleOnes() throws Exception {
        create(ownerToken, past.getId(), review(2, "Kept checking the phone."));
        Booking second = confirmedAt(owner, asha, Instant.now().minus(Duration.ofDays(5)));
        create(ownerToken, second.getId(), review(5, "Very helpful."));
        moderate(reviews.findByBookingId(past.getId()).orElseThrow().getId(), "hide", "Disputed by the professional.");

        JsonNode own = body(as(proToken, get("/api/pro/me/reviews")));
        assertThat(own.get("ratingCount").asInt()).isEqualTo(1);
        assertThat(own.get("ratingAvg").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(own.get("reviews").get("items").size()).isEqualTo(1);
        assertThat(own.toString()).doesNotContain("Kept checking the phone.", "Disputed by the professional.");

        Professional ravi = listed("Dr. Ravi Menon");
        account(ravi, "pro_ravi");
        JsonNode other = body(as(SecurityTestTokens.clerk("pro_ravi", ravi.getContactEmail()), get("/api/pro/me/reviews")));
        assertThat(other.get("ratingCount").asInt()).isZero();
        assertThat(other.get("reviews").get("items").size()).isZero();

        // Signed in, but not a professional; and the admin token is not accepted on the portal.
        assertThat(as(ownerToken, get("/api/pro/me/reviews")).getResponse().getStatus()).isEqualTo(401);
        assertThat(as(SecurityTestTokens.admin(), get("/api/pro/me/reviews")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void reviewsOfAHiddenListingAreNotPublic() throws Exception {
        create(ownerToken, past.getId(), review(5, null));
        asha.setActive(false);
        professionals.saveAndFlush(asha);
        assertThat(as(null, get("/api/professionals/" + asha.getId() + "/reviews")).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void savingAProfileNeverOverwritesTheRatingNumbers() throws Exception {
        create(ownerToken, past.getId(), review(5, null));
        Professional stale = professionals.findById(asha.getId()).orElseThrow();
        stale.setBio("A new bio.");
        professionals.saveAndFlush(stale);
        assertRating(asha, "5.00", 1);
    }

    // ---- Sorting ----

    private Professional rated(String name, int count, String avg, Integer fee, Integer years, boolean sample) {
        Professional p = sample ? professional(name, com.neuronest.backend.professional.VerificationStatus.DRAFT, true, true)
                : listed(name);
        p.setConsultationFee(fee);
        p.setYearsExperience(years);
        professionals.saveAndFlush(p);
        jdbc.update("UPDATE professionals SET rating_count = ?, rating_avg = CAST(? AS numeric) WHERE id = ?",
                count, avg, p.getId());
        return p;
    }

    private List<String> order(String sort) throws Exception {
        JsonNode items = body(as(null, get("/api/professionals" + (sort == null ? "" : "?sort=" + sort))))
                .get("items");
        List<String> names = new ArrayList<>();
        items.forEach(item -> names.add(item.get("fullName").asString()));
        return names;
    }

    @Test
    void topRatedSmoothsFewReviewsPutsUnratedLastAndBreaksTiesByCountThenName() throws Exception {
        // Dr. Asha Rao (from data()) is unrated too, with no fee or years.
        rated("Aaron Unrated", 0, null, 500, 20, false);
        rated("Bina One Five", 1, "5.00", 900, 2, false);
        rated("Chitra Fifty", 50, "4.80", 1500, 10, false);
        rated("Zoya Ten", 10, "4.50", null, null, false);
        rated("Yash Ten", 10, "4.50", 700, 8, false);
        rated("Dev Twenty", 20, "4.50", 800, 15, false);
        rated("Sample Star", 80, "5.00", 100, 30, true);

        // C = 4.0 (no review rows): Chitra 4.727, Dev 4.40, Yash and Zoya 4.333 (tie: same count, so by name),
        // Bina 4.167; the unrated listing after every rated one; sample listings after real ones, always.
        assertThat(order("top_rated")).containsExactly("Chitra Fifty", "Dev Twenty", "Yash Ten", "Zoya Ten",
                "Bina One Five", "Aaron Unrated", "Dr. Asha Rao", "Sample Star");
        assertThat(order("most_reviewed")).containsExactly("Chitra Fifty", "Dev Twenty", "Yash Ten", "Zoya Ten",
                "Bina One Five", "Aaron Unrated", "Dr. Asha Rao", "Sample Star");
        assertThat(order("lowest_fee")).containsExactly("Aaron Unrated", "Yash Ten", "Dev Twenty", "Bina One Five",
                "Chitra Fifty", "Dr. Asha Rao", "Zoya Ten", "Sample Star");
        assertThat(order("most_experienced")).containsExactly("Aaron Unrated", "Dev Twenty", "Chitra Fifty", "Yash Ten",
                "Bina One Five", "Dr. Asha Rao", "Zoya Ten", "Sample Star");
        // No sort: today's order, by name.
        assertThat(order(null)).containsExactly("Aaron Unrated", "Bina One Five", "Chitra Fifty", "Dev Twenty",
                "Dr. Asha Rao", "Yash Ten", "Zoya Ten", "Sample Star");
        assertThat(as(null, get("/api/professionals?sort=best")).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void theSmoothingPriorIsTheAverageOfAllVisibleReviews() throws Exception {
        // One real 1-star review makes C = 1.0: a single 5-star review (5/6 * 1 + 1/6 * 5 = 1.67) still ranks
        // below fifty at 4.8 (4.45), and the arithmetic uses that C, not 4.0.
        create(ownerToken, past.getId(), review(1, null));
        rated("Bina One Five", 1, "5.00", null, null, false);
        rated("Chitra Fifty", 50, "4.80", null, null, false);
        assertThat(order("top_rated")).containsExactly("Chitra Fifty", "Bina One Five", "Dr. Asha Rao");
    }
}
