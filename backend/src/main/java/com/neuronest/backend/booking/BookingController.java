package com.neuronest.backend.booking;

import com.neuronest.backend.auth.AuthUtil;
import com.neuronest.backend.booking.BookingDtos.BookingPage;
import com.neuronest.backend.booking.BookingDtos.BookingSummary;
import com.neuronest.backend.booking.BookingDtos.BookingView;
import com.neuronest.backend.booking.BookingDtos.CreateBookingRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// A signed-in user's booking requests. The /api/bookings/** security chain (SecurityConfig) only lets
// through a verified Clerk token, mapped to the user's own users row; the user is always taken from
// that, never from the request.
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BookingView> create(Authentication authentication,
                                              @RequestBody(required = false) CreateBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId(authentication), request));
    }

    @GetMapping("/mine")
    public BookingPage mine(Authentication authentication,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "" + BookingService.DEFAULT_PAGE_SIZE) int size) {
        return service.mine(userId(authentication), page, size);
    }

    // The user's next appointment and open requests, for their dashboard.
    @GetMapping("/summary")
    public BookingSummary summary(Authentication authentication) {
        return service.summary(userId(authentication));
    }

    // The id is taken as text so a malformed one gets the same 404 as a missing one.
    @GetMapping("/{id}")
    public BookingView get(Authentication authentication, @PathVariable String id) {
        return service.get(userId(authentication), id);
    }

    @PostMapping("/{id}/cancel")
    public BookingView cancel(Authentication authentication, @PathVariable String id) {
        return service.cancel(userId(authentication), id);
    }

    // Accepts the time the professional suggested. Declining it is a cancel.
    @PostMapping("/{id}/accept-proposal")
    public BookingView acceptProposal(Authentication authentication, @PathVariable String id) {
        return service.acceptProposal(userId(authentication), id);
    }

    private static UUID userId(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) throw new BookingExceptions.NotSignedInException();
        return userId;
    }
}
