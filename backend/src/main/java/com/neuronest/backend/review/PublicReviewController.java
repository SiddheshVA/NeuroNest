package com.neuronest.backend.review;

import com.neuronest.backend.review.ReviewDtos.ReviewPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Public: the visible reviews of a publicly listed professional, newest first, as "Verified client" and month.
@RestController
@RequestMapping("/api/professionals")
public class PublicReviewController {

    private final ReviewService service;

    public PublicReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping("/{id}/reviews")
    public ReviewPage reviews(@PathVariable String id,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "" + ReviewService.DEFAULT_PAGE_SIZE) int size) {
        return service.publicReviews(id, page, size);
    }
}
