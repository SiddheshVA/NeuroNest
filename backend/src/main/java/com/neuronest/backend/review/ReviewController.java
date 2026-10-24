package com.neuronest.backend.review;

import com.neuronest.backend.auth.AuthUtil;
import com.neuronest.backend.booking.BookingExceptions;
import com.neuronest.backend.review.ReviewDtos.MyReview;
import com.neuronest.backend.review.ReviewDtos.ReviewRequest;
import com.neuronest.backend.review.ReviewDtos.ReviewableBooking;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// A signed-in user's reviews of their own appointments. These paths sit under /api/bookings, so the booking
// security chain applies: only a verified Clerk token, and the user comes from that, never from the request.
@RestController
@RequestMapping("/api/bookings")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    // The user's past confirmed appointments, each with their review or canReview.
    @GetMapping("/reviews")
    public List<ReviewableBooking> reviewable(Authentication authentication) {
        return service.reviewable(userId(authentication));
    }

    // The booking id is taken as text so a malformed one gets the same 404 as a missing one.
    @PostMapping("/{bookingId}/review")
    public ResponseEntity<MyReview> create(Authentication authentication, @PathVariable String bookingId,
                                           @RequestBody(required = false) ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId(authentication), bookingId, request));
    }

    @PutMapping("/{bookingId}/review")
    public MyReview update(Authentication authentication, @PathVariable String bookingId,
                           @RequestBody(required = false) ReviewRequest request) {
        return service.update(userId(authentication), bookingId, request);
    }

    @DeleteMapping("/{bookingId}/review")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String bookingId) {
        service.delete(userId(authentication), bookingId);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) throw new BookingExceptions.NotSignedInException();
        return userId;
    }
}
