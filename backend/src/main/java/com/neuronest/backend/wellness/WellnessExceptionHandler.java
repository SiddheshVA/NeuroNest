package com.neuronest.backend.wellness;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Error mapping for WellnessController in the app's usual {"error": "..."} shape, plus a stable "code" where
// the frontend needs to branch (as the chat's 503 does) and "fields" for invalid answers. Never a stack trace,
// never the user's answers, never details of the ML service. Page/size validation (400) goes through the
// global ApiExceptionHandler.
@RestControllerAdvice(assignableTypes = WellnessController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WellnessExceptionHandler {

    @ExceptionHandler(WellnessExceptions.NotSignedInException.class)
    public ResponseEntity<?> handleNotSignedIn(WellnessExceptions.NotSignedInException ex) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage(), null, null);
    }

    @ExceptionHandler(WellnessExceptions.NotFoundException.class)
    public ResponseEntity<?> handleNotFound(WellnessExceptions.NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), null, null);
    }

    @ExceptionHandler(WellnessExceptions.UnavailableException.class)
    public ResponseEntity<?> handleUnavailable(WellnessExceptions.UnavailableException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), WellnessExceptions.CODE_UNAVAILABLE, null);
    }

    @ExceptionHandler(WellnessExceptions.InvalidInputException.class)
    public ResponseEntity<?> handleInvalid(WellnessExceptions.InvalidInputException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), WellnessExceptions.CODE_INVALID_INPUT, ex.fields());
    }

    // Unknown fields and wrong JSON types. Names the field, never echoes the value.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException ex) {
        WellnessDtos.FieldError field = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof UnrecognizedPropertyException u) {
                field = new WellnessDtos.FieldError(u.getPropertyName(), "is not an accepted field");
                break;
            }
            if (t instanceof JacksonException j && !j.getPath().isEmpty()) {
                String name = j.getPath().getLast().getPropertyName();
                if (name != null) {
                    field = new WellnessDtos.FieldError(name, "has the wrong type");
                    break;
                }
            }
        }
        if (field == null) {
            return error(HttpStatus.BAD_REQUEST, "Request body is missing or not valid JSON.",
                    WellnessExceptions.CODE_INVALID_INPUT, null);
        }
        return error(HttpStatus.BAD_REQUEST, "Some check-in answers are invalid: " + field.field() + ": " + field.message(),
                WellnessExceptions.CODE_INVALID_INPUT, List.of(field));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'.", null, null);
    }

    private static ResponseEntity<?> error(HttpStatus status, String message, String code,
                                           List<WellnessDtos.FieldError> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (code != null) body.put("code", code);
        if (fields != null) body.put("fields", fields);
        return ResponseEntity.status(status).body(body);
    }
}
