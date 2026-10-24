package com.neuronest.backend.review;

import com.neuronest.backend.admin.AdminReviewController;
import com.neuronest.backend.booking.BookingExceptions;
import com.neuronest.backend.pro.ProReviewController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

// Error mapping for the review controllers, in the app's usual {"error": "..."} shape. Validation (400) and
// conflicts (409) go through the global ApiExceptionHandler; the admin and professional handlers still apply
// to their own controllers for everything else.
@RestControllerAdvice(assignableTypes = {ReviewController.class, PublicReviewController.class,
        AdminReviewController.class, ProReviewController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReviewExceptionHandler {

    @ExceptionHandler(ReviewExceptions.NotFoundException.class)
    public ResponseEntity<?> handleNotFound(ReviewExceptions.NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BookingExceptions.NotSignedInException.class)
    public ResponseEntity<?> handleNotSignedIn(BookingExceptions.NotSignedInException ex) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing, not valid JSON, or has a field of the wrong type.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'.");
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
