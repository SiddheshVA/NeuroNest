package com.neuronest.backend.admin;

import com.neuronest.backend.review.ReviewDtos.AdminReview;
import com.neuronest.backend.review.ReviewDtos.AdminReviewPage;
import com.neuronest.backend.review.ReviewDtos.ModerationRequest;
import com.neuronest.backend.review.ReviewService;
import com.neuronest.backend.review.ReviewStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// Admin moderation of reviews (admin token only, see SecurityConfig): every review of a professional, hidden
// ones included, and hide or unhide with a reason. Reviews are never deleted here.
@RestController
@RequestMapping("/api/admin")
public class AdminReviewController {

    private final ReviewService service;

    public AdminReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping("/professionals/{id}/reviews")
    public AdminReviewPage list(@PathVariable UUID id,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "" + ReviewService.MAX_PAGE_SIZE) int size) {
        return service.adminReviews(id, page, size);
    }

    @PostMapping("/reviews/{id}/hide")
    public AdminReview hide(@PathVariable String id, @RequestBody(required = false) ModerationRequest request,
                            Authentication authentication) {
        return service.moderate(id, ReviewStatus.HIDDEN, request == null ? null : request.reason(), authentication.getName());
    }

    @PostMapping("/reviews/{id}/unhide")
    public AdminReview unhide(@PathVariable String id, @RequestBody(required = false) ModerationRequest request,
                              Authentication authentication) {
        return service.moderate(id, ReviewStatus.VISIBLE, request == null ? null : request.reason(), authentication.getName());
    }
}
