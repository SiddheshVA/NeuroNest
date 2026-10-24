package com.neuronest.backend.review;

import com.neuronest.backend.professional.AvailabilityService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

// Request and response bodies for reviews. Nothing here ever carries the reviewer's identity (user id, name,
// email or phone): the public and professional views only say "Verified client" and the month.
public final class ReviewDtos {

    private ReviewDtos() {}

    /** POST and PUT /api/bookings/{bookingId}/review. The comment is optional. */
    public record ReviewRequest(Integer rating, String comment) {}

    /** POST /api/admin/reviews/{id}/hide and /unhide. */
    public record ModerationRequest(String reason) {}

    /** A review as the public and the professional see it. month is "YYYY-MM" in India's calendar. */
    public record PublicReview(int rating, String comment, String month) {
        static PublicReview from(ProfessionalReview r) {
            return new PublicReview(r.getRating(), r.getComment(), monthOf(r.getCreatedAt()));
        }
    }

    public record ReviewPage(List<PublicReview> items, int page, int size, long totalElements, int totalPages) {}

    /** The author's own review. A HIDDEN one can no longer be edited or deleted. */
    public record MyReview(int rating, String comment, String status, Instant createdAt, Instant updatedAt) {
        static MyReview from(ProfessionalReview r) {
            return new MyReview(r.getRating(), r.getComment(), r.getStatus().name(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    public record ReviewProfessional(UUID id, String fullName, String credentialType, String city) {}

    /**
     * GET /api/bookings/reviews: one of the user's CONFIRMED bookings whose session has ended, with their
     * review if they wrote one. canReview is true when there is none yet.
     */
    public record ReviewableBooking(UUID bookingId, ReviewProfessional professional, OffsetDateTime scheduledTime,
                                    boolean canReview, MyReview review) {}

    /** A review on the admin's screen: everything except who wrote it. */
    public record AdminReview(UUID id, UUID bookingId, int rating, String comment, String status,
                              String moderationReason, Instant moderatedAt, String moderatedBy,
                              Instant createdAt, Instant updatedAt) {
        static AdminReview from(ProfessionalReview r) {
            return new AdminReview(r.getId(), r.getBookingId(), r.getRating(), r.getComment(), r.getStatus().name(),
                    r.getModerationReason(), r.getModeratedAt(), r.getModeratedBy(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    public record AdminReviewPage(List<AdminReview> items, int page, int size, long totalElements, int totalPages) {}

    /** GET /api/pro/me/reviews: the professional's own numbers and visible reviews, read-only. */
    public record ProReviews(BigDecimal ratingAvg, int ratingCount, ReviewPage reviews) {}

    static String monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(AvailabilityService.ZONE)).toString();
    }
}
