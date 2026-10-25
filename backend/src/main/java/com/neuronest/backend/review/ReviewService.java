package com.neuronest.backend.review;

import com.neuronest.backend.booking.Booking;
import com.neuronest.backend.booking.BookingRepository;
import com.neuronest.backend.booking.BookingStatus;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.professional.AvailabilityService;
import com.neuronest.backend.professional.ContactDetailsGuard;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.PublicProfessionalRepository;
import com.neuronest.backend.professional.SessionGrid;
import com.neuronest.backend.review.ReviewDtos.AdminReview;
import com.neuronest.backend.review.ReviewDtos.AdminReviewPage;
import com.neuronest.backend.review.ReviewDtos.MyReview;
import com.neuronest.backend.review.ReviewDtos.PublicReview;
import com.neuronest.backend.review.ReviewDtos.ReviewPage;
import com.neuronest.backend.review.ReviewDtos.ReviewProfessional;
import com.neuronest.backend.review.ReviewDtos.ReviewRequest;
import com.neuronest.backend.review.ReviewDtos.ReviewableBooking;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reviews of professionals. Every rule is checked here, never only in the UI:
 * <ul>
 *   <li>Only the booking's own user, only for a CONFIRMED booking whose session has ended (its scheduled
 *       time plus the professional's session length), and one review per booking.</li>
 *   <li>Rating 1-5, an optional comment of at most 500 characters without contact details.</li>
 *   <li>The author may edit or delete their review, unless an admin has hidden it.</li>
 *   <li>Only an admin hides or unhides a review (with a reason); nothing is ever hard-deleted by an admin.</li>
 * </ul>
 * Every change locks the professional row first and then recomputes rating_avg and rating_count from the
 * VISIBLE reviews in the same transaction.
 */
@Service
public class ReviewService {

    public static final int MAX_COMMENT_LENGTH = 500;
    public static final int MAX_REASON_LENGTH = 500;
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 20;

    static final String BOOKING_NOT_FOUND = "We couldn't find that appointment.";
    static final String REVIEW_NOT_FOUND = "There is no review for this appointment yet.";
    static final String NOT_ELIGIBLE = "You can rate a session once a confirmed appointment has taken place.";
    static final String ALREADY_REVIEWED = "You have already rated this session. You can edit your review instead.";
    static final String HIDDEN_LOCKED = "This review was hidden by NeuroNest, so it can no longer be changed.";
    static final String CONTACT_DETAILS =
            "Please remove phone numbers, email addresses and links from your comment.";

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProfessionalReviewRepository reviews;
    private final BookingRepository bookings;
    private final ProfessionalRepository professionals;
    private final PublicProfessionalRepository publicProfessionals;
    private final Clock clock;

    @Autowired
    public ReviewService(ProfessionalReviewRepository reviews, BookingRepository bookings,
                         ProfessionalRepository professionals, PublicProfessionalRepository publicProfessionals) {
        this(reviews, bookings, professionals, publicProfessionals, Clock.system(AvailabilityService.ZONE));
    }

    ReviewService(ProfessionalReviewRepository reviews, BookingRepository bookings,
                  ProfessionalRepository professionals, PublicProfessionalRepository publicProfessionals,
                  Clock clock) {
        this.reviews = reviews;
        this.bookings = bookings;
        this.professionals = professionals;
        this.publicProfessionals = publicProfessionals;
        this.clock = clock;
    }

    // ---- The user's own reviews ----

    @Transactional
    public MyReview create(UUID userId, String bookingIdParam, ReviewRequest request) {
        Booking booking = ownBooking(userId, bookingIdParam);
        Professional professional = lockProfessional(booking.getProfessionalId());
        if (!sessionEnded(booking, professional, clock.instant())) throw new ConflictException(NOT_ELIGIBLE);
        if (reviews.findByBookingId(booking.getId()).isPresent()) throw new ConflictException(ALREADY_REVIEWED);

        int rating = validRating(request);
        String comment = validComment(request);
        ProfessionalReview saved = reviews.saveAndFlush(
                new ProfessionalReview(booking.getId(), booking.getProfessionalId(), userId, rating, comment));
        reviews.refreshRating(booking.getProfessionalId());
        return MyReview.from(saved);
    }

    @Transactional
    public MyReview update(UUID userId, String bookingIdParam, ReviewRequest request) {
        ProfessionalReview review = ownEditableReview(userId, bookingIdParam);
        review.setRating(validRating(request));
        review.setComment(validComment(request));
        ProfessionalReview saved = reviews.saveAndFlush(review);
        reviews.refreshRating(saved.getProfessionalId());
        return MyReview.from(saved);
    }

    @Transactional
    public void delete(UUID userId, String bookingIdParam) {
        ProfessionalReview review = ownEditableReview(userId, bookingIdParam);
        reviews.delete(review);
        reviews.flush();
        reviews.refreshRating(review.getProfessionalId());
    }

    /** The user's CONFIRMED bookings whose session has ended, newest first, each with their review if any. */
    @Transactional(readOnly = true)
    public List<ReviewableBooking> reviewable(UUID userId) {
        Instant now = clock.instant();
        List<Booking> confirmed = reviews.findBookingsOfUser(userId, BookingStatus.CONFIRMED);
        Map<UUID, Professional> byId = professionals.findAllById(
                        confirmed.stream().map(Booking::getProfessionalId).distinct().toList()).stream()
                .collect(Collectors.toMap(Professional::getId, Function.identity()));
        List<Booking> ended = confirmed.stream()
                .filter(b -> byId.containsKey(b.getProfessionalId()) && sessionEnded(b, byId.get(b.getProfessionalId()), now))
                .toList();
        Map<UUID, ProfessionalReview> reviewByBooking = reviews.findByBookingIdIn(ended.stream().map(Booking::getId).toList())
                .stream().collect(Collectors.toMap(ProfessionalReview::getBookingId, Function.identity()));

        return ended.stream().map(b -> {
            Professional p = byId.get(b.getProfessionalId());
            ProfessionalReview review = reviewByBooking.get(b.getId());
            return new ReviewableBooking(b.getId(),
                    new ReviewProfessional(p.getId(), p.getFullName(), p.getCredentialType().name(), p.getCity()),
                    b.scheduledTime().atZone(AvailabilityService.ZONE).toOffsetDateTime(),
                    review == null, review == null ? null : MyReview.from(review));
        }).toList();
    }

    // ---- Public ----

    /** Visible reviews of a publicly listed professional, newest first. Hidden listings give the same 404. */
    @Transactional(readOnly = true)
    public ReviewPage publicReviews(String professionalIdParam, int page, int size) {
        UUID professionalId = parseId(professionalIdParam, "We couldn't find that professional.");
        if (publicProfessionals.findVisibleById(professionalId, LocalDate.now(clock)).isEmpty()) {
            throw new ReviewExceptions.NotFoundException("We couldn't find that professional.");
        }
        return visiblePage(professionalId, page, size);
    }

    /** The professional's own visible reviews (read-only), for the professional portal. */
    @Transactional(readOnly = true)
    public ReviewDtos.ProReviews forProfessional(UUID professionalId, int page, int size) {
        Professional p = professionals.findById(professionalId)
                .orElseThrow(() -> new ReviewExceptions.NotFoundException("We couldn't find that professional."));
        return new ReviewDtos.ProReviews(p.getRatingAvg(), p.getRatingCount(), visiblePage(professionalId, page, size));
    }

    // ---- Admin ----

    @Transactional(readOnly = true)
    public AdminReviewPage adminReviews(UUID professionalId, int page, int size) {
        checkPage(page, size);
        Page<ProfessionalReview> result = reviews.findByProfessionalId(professionalId,
                PageRequest.of(page, size, NEWEST_FIRST));
        return new AdminReviewPage(result.getContent().stream().map(AdminReview::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public AdminReview moderate(String reviewIdParam, ReviewStatus status, String reasonParam, String admin) {
        UUID reviewId = parseId(reviewIdParam, "We couldn't find that review.");
        String reason = reasonParam == null ? "" : reasonParam.trim();
        if (reason.isEmpty()) throw new ApiValidationException("Please give a reason.");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ApiValidationException("The reason can be at most " + MAX_REASON_LENGTH + " characters.");
        }
        // Only the id first, so the review itself is read after the lock and a concurrent edit or delete is seen.
        UUID professionalId = reviews.findProfessionalIdById(reviewId)
                .orElseThrow(() -> new ReviewExceptions.NotFoundException("We couldn't find that review."));
        lockProfessional(professionalId);
        ProfessionalReview review = reviews.findById(reviewId)
                .orElseThrow(() -> new ReviewExceptions.NotFoundException("We couldn't find that review."));
        if (review.getStatus() == status) {
            throw new ConflictException(status == ReviewStatus.HIDDEN
                    ? "This review is already hidden." : "This review is already visible.");
        }
        review.moderate(status, reason, admin, clock.instant());
        ProfessionalReview saved = reviews.saveAndFlush(review);
        reviews.refreshRating(professionalId);
        return AdminReview.from(saved);
    }

    // ---- Rules ----

    /**
     * A booking can be reviewed once it is CONFIRMED (paid, or free and confirmed) and its session has ended:
     * its meeting end (ends_at) has passed, or for an older booking without one, its start plus the session
     * length the professional had then (one grid session if they never chose one).
     */
    static boolean sessionEnded(Booking booking, Professional professional, Instant now) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) return false;
        Instant start = booking.scheduledTime();
        if (start == null) return false;
        int minutes = professional.getSessionMinutes() != null ? professional.getSessionMinutes()
                : SessionGrid.current().sessionMinutes();
        Instant end = booking.getEndsAt() != null ? booking.getEndsAt() : start.plus(Duration.ofMinutes(minutes));
        return !end.isAfter(now);
    }

    static int validRating(ReviewRequest request) {
        Integer rating = request == null ? null : request.rating();
        if (rating == null || rating < 1 || rating > 5) throw new ApiValidationException("Choose a rating from 1 to 5 stars.");
        return rating;
    }

    /** The trimmed comment, or null when empty. */
    static String validComment(ReviewRequest request) {
        String comment = request == null || request.comment() == null ? null : request.comment().trim();
        if (comment == null || comment.isEmpty()) return null;
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw new ApiValidationException("Your comment can be at most " + MAX_COMMENT_LENGTH + " characters.");
        }
        // The same detection that keeps contact details out of professional profiles.
        if (ContactDetailsGuard.check("Comment", comment).isPresent()) throw new ApiValidationException(CONTACT_DETAILS);
        return comment;
    }

    // ---- Helpers ----

    /** The user's own booking; someone else's or a missing one is the same 404. */
    private Booking ownBooking(UUID userId, String bookingIdParam) {
        UUID bookingId = parseId(bookingIdParam, BOOKING_NOT_FOUND);
        return bookings.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ReviewExceptions.NotFoundException(BOOKING_NOT_FOUND));
    }

    private ProfessionalReview ownEditableReview(UUID userId, String bookingIdParam) {
        Booking booking = ownBooking(userId, bookingIdParam);
        lockProfessional(booking.getProfessionalId());
        ProfessionalReview review = reviews.findByBookingId(booking.getId())
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new ReviewExceptions.NotFoundException(REVIEW_NOT_FOUND));
        if (review.getStatus() == ReviewStatus.HIDDEN) throw new ConflictException(HIDDEN_LOCKED);
        return review;
    }

    private Professional lockProfessional(UUID professionalId) {
        return professionals.findByIdForUpdate(professionalId)
                .orElseThrow(() -> new ReviewExceptions.NotFoundException("We couldn't find that professional."));
    }

    private ReviewPage visiblePage(UUID professionalId, int page, int size) {
        checkPage(page, size);
        Page<ProfessionalReview> result = reviews.findByProfessionalIdAndStatus(professionalId, ReviewStatus.VISIBLE,
                PageRequest.of(page, size, NEWEST_FIRST));
        return new ReviewPage(result.getContent().stream().map(PublicReview::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    private static void checkPage(int page, int size) {
        if (page < 0) throw new ApiValidationException("page must be 0 or greater.");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiValidationException("size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
    }

    private static UUID parseId(String value, String notFoundMessage) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ReviewExceptions.NotFoundException(notFoundMessage);
        }
    }
}
