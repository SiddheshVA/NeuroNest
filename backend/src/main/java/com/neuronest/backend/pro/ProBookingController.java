package com.neuronest.backend.pro;

import com.neuronest.backend.pro.ProBookingDtos.ConfirmRequest;
import com.neuronest.backend.pro.ProBookingDtos.DeclineRequest;
import com.neuronest.backend.pro.ProBookingDtos.MeetingLinkRequest;
import com.neuronest.backend.pro.ProBookingDtos.ProBookingPage;
import com.neuronest.backend.pro.ProBookingDtos.ProBookingSummary;
import com.neuronest.backend.pro.ProBookingDtos.ProBookingView;
import com.neuronest.backend.pro.ProBookingDtos.ProposeRequest;
import com.neuronest.backend.meeting.MeetingJoinService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// A professional's own booking requests, behind the /api/pro/** security chain (SecurityConfig). The
// professional is always the one linked to the verified token; no route takes a professional id.
// Booking ids are taken as text so a malformed one gets the same 404 as a missing one.
@RestController
@RequestMapping("/api/pro/bookings")
public class ProBookingController {

    private final ProBookingService service;
    private final MeetingJoinService meetings;

    public ProBookingController(ProBookingService service, MeetingJoinService meetings) {
        this.service = service;
        this.meetings = meetings;
    }

    @GetMapping
    public ProBookingPage list(@AuthenticationPrincipal ProPrincipal principal,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "" + ProBookingService.DEFAULT_PAGE_SIZE) int size) {
        return service.list(principal, status, page, size);
    }

    @GetMapping("/summary")
    public ProBookingSummary summary(@AuthenticationPrincipal ProPrincipal principal) {
        return service.summary(principal);
    }

    @GetMapping("/{id}")
    public ProBookingView get(@AuthenticationPrincipal ProPrincipal principal, @PathVariable String id) {
        return service.get(principal, id);
    }

    @PostMapping("/{id}/confirm")
    public ProBookingView confirm(@AuthenticationPrincipal ProPrincipal principal, @PathVariable String id,
                                  @RequestBody(required = false) ConfirmRequest request) {
        return service.confirm(principal, id, request);
    }

    // The body is optional: {"note": "..."}.
    @PostMapping("/{id}/decline")
    public ProBookingView decline(@AuthenticationPrincipal ProPrincipal principal, @PathVariable String id,
                                  @RequestBody(required = false) DeclineRequest request) {
        return service.decline(principal, id, request);
    }

    // The video link of the professional's own confirmed video session, only inside its time window (409
    // otherwise, without the link). The only place the professional gets the raw link.
    @GetMapping("/{id}/join")
    public MeetingJoinService.JoinLink join(@AuthenticationPrincipal ProPrincipal principal, @PathVariable String id) {
        return meetings.join(service.findOwn(principal, id));
    }

    // A video link added by hand when the automatic Google Meet link could not be created.
    @PostMapping("/{id}/meeting-link")
    public ProBookingView addMeetingLink(@AuthenticationPrincipal ProPrincipal principal, @PathVariable String id,
                                         @RequestBody(required = false) MeetingLinkRequest request) {
        return service.addMeetingLink(principal, id, request);
    }

    @PostMapping("/{id}/propose")
    public ProBookingView propose(@AuthenticationPrincipal ProPrincipal principal, @PathVariable String id,
                                  @RequestBody(required = false) ProposeRequest request) {
        return service.propose(principal, id, request);
    }
}
