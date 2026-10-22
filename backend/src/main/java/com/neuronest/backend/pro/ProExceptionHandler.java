package com.neuronest.backend.pro;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.LinkedHashMap;
import java.util.Map;

// Error mapping for the professional area only, in the app's usual {"error": "..."} shape (with a
// "missing" list for an incomplete submit). Plain validation and conflict errors go through the
// global ApiExceptionHandler.
@RestControllerAdvice(basePackageClasses = ProExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProExceptionHandler {

    @ExceptionHandler({ProExceptions.NotRegisteredException.class, ProExceptions.DocumentNotFoundException.class,
            ProExceptions.BookingNotFoundException.class})
    public ResponseEntity<?> handleNotFound(RuntimeException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ProExceptions.EmailNotVerifiedException.class)
    public ResponseEntity<?> handleEmailNotVerified(ProExceptions.EmailNotVerifiedException ex) {
        return error(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ProExceptions.ClerkUnavailableException.class)
    public ResponseEntity<?> handleClerkUnavailable(ProExceptions.ClerkUnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(ProExceptions.RateLimitedException.class)
    public ResponseEntity<?> handleRateLimited(ProExceptions.RateLimitedException ex) {
        long seconds = Math.max(1, (ex.retryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ProExceptions.SubmissionIncompleteException.class)
    public ResponseEntity<?> handleIncomplete(ProExceptions.SubmissionIncompleteException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("missing", ex.missing());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({ProExceptions.FileTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<?> handleTooLarge(Exception ex) {
        return error(HttpStatus.CONTENT_TOO_LARGE, "The file is larger than 5 MB. Please upload a smaller file.");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleMultipart(MultipartException ex) {
        return error(HttpStatus.BAD_REQUEST, "The upload could not be read. Send it as multipart/form-data with "
                + "the fields docType and file.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This request has the wrong content type.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'.");
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
