package com.neuronest.backend.pro;

import com.neuronest.backend.pro.ProBookingDtos.ProAppointmentPage;
import com.neuronest.backend.pro.ProHomeDtos.DayOffCreated;
import com.neuronest.backend.pro.ProHomeDtos.DayOffRequest;
import com.neuronest.backend.pro.ProHomeDtos.DayOffView;
import com.neuronest.backend.pro.ProHomeDtos.ProDashboard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// The professional's home summary, confirmed appointments and days off, behind the /api/pro/** security
// chain (SecurityConfig). The professional is always the one linked to the verified token.
@RestController
@RequestMapping("/api/pro")
public class ProHomeController {

    private final ProHomeService service;

    public ProHomeController(ProHomeService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ProDashboard dashboard(@AuthenticationPrincipal ProPrincipal principal) {
        return service.dashboard(principal);
    }

    @GetMapping("/appointments")
    public ProAppointmentPage appointments(@AuthenticationPrincipal ProPrincipal principal,
                                           @RequestParam(defaultValue = "upcoming") String when,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "" + ProHomeService.DEFAULT_PAGE_SIZE) int size) {
        return service.appointments(principal, when, page, size);
    }

    @GetMapping("/days-off")
    public List<DayOffView> daysOff(@AuthenticationPrincipal ProPrincipal principal) {
        return service.daysOff(principal);
    }

    @PostMapping("/days-off")
    public ResponseEntity<DayOffCreated> addDayOff(@AuthenticationPrincipal ProPrincipal principal,
                                                   @RequestBody(required = false) DayOffRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addDayOff(principal, request));
    }

    // The id is taken as text so a malformed one gets the same 404 as a missing one.
    @DeleteMapping("/days-off/{id}")
    public ResponseEntity<Void> deleteDayOff(@AuthenticationPrincipal ProPrincipal principal, @PathVariable String id) {
        service.deleteDayOff(principal, id);
        return ResponseEntity.noContent().build();
    }
}
