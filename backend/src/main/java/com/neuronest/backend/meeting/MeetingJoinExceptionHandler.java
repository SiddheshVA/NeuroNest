package com.neuronest.backend.meeting;

import com.neuronest.backend.booking.BookingController;
import com.neuronest.backend.pro.ProBookingController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The join endpoints' answers when the link is not given out. Never the link itself.
 * TOO_EARLY / ENDED: 409 {"code", "opensAt", "endsAt", "error"}; NOT_AVAILABLE: 409; Google failing: 503.
 */
@RestControllerAdvice(assignableTypes = {BookingController.class, ProBookingController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MeetingJoinExceptionHandler {

    @ExceptionHandler(MeetingJoinService.NotOpenException.class)
    ResponseEntity<Map<String, Object>> notOpen(MeetingJoinService.NotOpenException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("opensAt", e.opensAt());
        body.put("endsAt", e.endsAt());
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MeetingJoinService.UnavailableException.class)
    ResponseEntity<Map<String, Object>> unavailable(MeetingJoinService.UnavailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "NOT_AVAILABLE", "error", e.getMessage()));
    }

    @ExceptionHandler(MeetingJoinService.OpenFailedException.class)
    ResponseEntity<Map<String, Object>> openFailed(MeetingJoinService.OpenFailedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "UNAVAILABLE", "error", e.getMessage()));
    }
}
