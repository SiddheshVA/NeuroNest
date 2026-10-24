package com.neuronest.backend.pro;

import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.review.ReviewDtos.ProReviews;
import com.neuronest.backend.review.ReviewService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// The professional's own reviews and average, read-only: there is no way for a professional to edit, hide or
// delete a review. The professional comes from the signed-in account, never from the request.
@RestController
@RequestMapping("/api/pro/me")
public class ProReviewController {

    private final ReviewService service;
    private final ProfessionalAccountRepository accounts;

    public ProReviewController(ReviewService service, ProfessionalAccountRepository accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    @GetMapping("/reviews")
    public ProReviews reviews(@AuthenticationPrincipal ProPrincipal principal,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "" + ReviewService.MAX_PAGE_SIZE) int size) {
        UUID professionalId = accounts.findByClerkUserId(principal.clerkUserId())
                .orElseThrow(ProExceptions.NotAProfessionalException::new)
                .getProfessionalId();
        return service.forProfessional(professionalId, page, size);
    }
}
