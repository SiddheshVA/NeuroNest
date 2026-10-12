package com.neuronest.backend.professional;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

// Error mapping for the public directory only, in the app's usual {"error": "..."} shape. Bad enum
// values and paging limits are ApiValidationExceptions, handled by the global ApiExceptionHandler.
@RestControllerAdvice(assignableTypes = PublicProfessionalController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicProfessionalExceptionHandler {

    @ExceptionHandler(PublicProfessionalNotFoundException.class)
    public ResponseEntity<?> handleNotFound(PublicProfessionalNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    // A non-numeric page or size.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid value for '" + ex.getName() + "'."));
    }
}
