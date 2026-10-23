package com.neuronest.backend.booking;

import com.neuronest.backend.payment.RazorpayGateway;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

// Error mapping for /api/bookings only, in the app's usual {"error": "..."} shape. Validation (400) and
// conflicts (409) go through the global ApiExceptionHandler.
@RestControllerAdvice(assignableTypes = BookingController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BookingExceptionHandler {

    @ExceptionHandler(BookingExceptions.NotFoundException.class)
    public ResponseEntity<?> handleNotFound(BookingExceptions.NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BookingExceptions.TooManyRequestsException.class)
    public ResponseEntity<?> handleTooMany(BookingExceptions.TooManyRequestsException ex) {
        return error(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(BookingExceptions.NotSignedInException.class)
    public ResponseEntity<?> handleNotSignedIn(BookingExceptions.NotSignedInException ex) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(BookingExceptions.PaidButNotConfirmedException.class)
    public ResponseEntity<?> handlePaidButNotConfirmed(BookingExceptions.PaidButNotConfirmedException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RazorpayGateway.NotConfiguredException.class)
    public ResponseEntity<?> handlePaymentsNotConfigured(RazorpayGateway.NotConfiguredException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(RazorpayGateway.GatewayException.class)
    public ResponseEntity<?> handleGateway(RazorpayGateway.GatewayException ex) {
        return error(HttpStatus.BAD_GATEWAY, ex.getMessage());
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
