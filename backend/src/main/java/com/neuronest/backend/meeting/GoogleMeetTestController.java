package com.neuronest.backend.meeting;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// =====================================================================================================
// TEMPORARY, FOR A MANUAL TEST ONLY. REMOVE THIS FILE once the two-account Google Meet test is done.
// POST /api/admin/google-meet-test creates a real Meet space on the NeuroNest Google account (no booking
// involved), sets it OPEN straight away so both test accounts can walk in without knocking, and returns its
// link and space name. Under /api/admin/**, so the admin security chain (SecurityConfig) lets only a valid
// admin token in.
// =====================================================================================================
@RestController
@RequestMapping("/api/admin/google-meet-test")
public class GoogleMeetTestController {

    public record TestSpace(String meetingUri, String spaceName) {}

    private final GoogleMeetService google;

    public GoogleMeetTestController(GoogleMeetService google) {
        this.google = google;
    }

    @PostMapping
    public TestSpace create() {
        GoogleMeetService.Space space = google.createSpace();
        google.setAccessType(space.name(), GoogleMeetService.AccessType.OPEN);
        return new TestSpace(space.meetingUri(), space.name());
    }

    @ExceptionHandler(GoogleMeetService.NotConfiguredException.class)
    ResponseEntity<Map<String, String>> notConfigured(GoogleMeetService.NotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GoogleMeetService.GoogleMeetException.class)
    ResponseEntity<Map<String, String>> failed(GoogleMeetService.GoogleMeetException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
