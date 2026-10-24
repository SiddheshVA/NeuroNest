package com.neuronest.backend.review;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.review.ReviewDtos.ReviewRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRulesTest {

    private static final Instant START = Instant.parse("2026-10-20T12:30:00Z");

    private static Booking booking(BookingStatus status, Instant scheduled) {
        Booking b = new Booking();
        b.setStatus(status);
        b.setPreferredDatetime(scheduled);
        return b;
    }

    private static Professional sessionOf(Integer minutes) {
        Professional p = new Professional();
        p.setSessionMinutes(minutes);
        return p;
    }

    @Test
    void aConfirmedSessionCanBeReviewedFromTheMomentItEnds() {
        Booking b = booking(BookingStatus.CONFIRMED, START);
        Professional fifty = sessionOf(50);
        assertThat(ReviewService.sessionEnded(b, fifty, START.plus(Duration.ofMinutes(49)))).isFalse();
        assertThat(ReviewService.sessionEnded(b, fifty, START.plus(Duration.ofMinutes(50)))).isTrue();
        assertThat(ReviewService.sessionEnded(b, fifty, START.plus(Duration.ofDays(30)))).isTrue();
        // No session length: from the start time.
        assertThat(ReviewService.sessionEnded(b, sessionOf(null), START)).isTrue();
    }

    @Test
    void onlyConfirmedBookingsCount() {
        Instant later = START.plus(Duration.ofDays(1));
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(ReviewService.sessionEnded(booking(status, START), sessionOf(50), later))
                    .as(status.name()).isEqualTo(status == BookingStatus.CONFIRMED);
        }
        assertThat(ReviewService.sessionEnded(booking(BookingStatus.CONFIRMED, null), sessionOf(50), later)).isFalse();
    }

    @Test
    void ratingMustBeOneToFive() {
        for (Integer bad : new Integer[] {null, 0, 6, -1}) {
            assertThatThrownBy(() -> ReviewService.validRating(new ReviewRequest(bad, null)))
                    .isInstanceOf(ApiValidationException.class).hasMessageContaining("1 to 5");
        }
        assertThatThrownBy(() -> ReviewService.validRating(null)).isInstanceOf(ApiValidationException.class);
        assertThat(ReviewService.validRating(new ReviewRequest(1, null))).isEqualTo(1);
        assertThat(ReviewService.validRating(new ReviewRequest(5, null))).isEqualTo(5);
    }

    @Test
    void commentIsOptionalTrimmedLimitedAndFreeOfContactDetails() {
        assertThat(ReviewService.validComment(new ReviewRequest(5, null))).isNull();
        assertThat(ReviewService.validComment(new ReviewRequest(5, "  \n "))).isNull();
        assertThat(ReviewService.validComment(new ReviewRequest(5, "  Kind and calm.  "))).isEqualTo("Kind and calm.");
        assertThat(ReviewService.validComment(new ReviewRequest(5, "y".repeat(500)))).hasSize(500);
        assertThatThrownBy(() -> ReviewService.validComment(new ReviewRequest(5, "y".repeat(501))))
                .isInstanceOf(ApiValidationException.class).hasMessageContaining("500");
        for (String contact : new String[] {"ring 9876543210", "me@example.com", "https://x.example", "whatsapp: 98765"}) {
            assertThatThrownBy(() -> ReviewService.validComment(new ReviewRequest(5, contact)))
                    .as(contact).isInstanceOf(ApiValidationException.class).hasMessage(ReviewService.CONTACT_DETAILS);
        }
        // Ordinary text with numbers is fine.
        assertThat(ReviewService.validComment(new ReviewRequest(5, "3 sessions in, B.Com student, 10/10"))).isNotNull();
    }

    @Test
    void theMonthIsInIndiasCalendar() {
        // 31 Oct 2026 20:00 UTC is already 1 Nov in India.
        assertThat(ReviewDtos.monthOf(Instant.parse("2026-10-31T20:00:00Z"))).isEqualTo("2026-11");
    }
}
