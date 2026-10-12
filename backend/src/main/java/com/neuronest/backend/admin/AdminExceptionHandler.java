package com.neuronest.backend.admin;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

// Admin-only error mapping, in the app's usual {"error": "..."} shape. Scoped to the admin package so
// no other route's error responses change. Runs before the global ApiExceptionHandler.
@RestControllerAdvice(basePackageClasses = AdminExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

    @ExceptionHandler(ProfessionalNotFoundException.class)
    public ResponseEntity<?> handleNotFound(ProfessionalNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    // Malformed JSON, or a value of the wrong type (e.g. "feeMin": "abc", a bad date).
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of("error",
                "Request body is missing, not valid JSON, or has a field of the wrong type "
                        + "(numbers must be numbers, dates must be YYYY-MM-DD)."));
    }

    // Bad path or query parameter, e.g. a non-UUID id or active=maybe.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid value for '" + ex.getName() + "'."));
    }
}
