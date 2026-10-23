package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminBookingDtos.AdminBookingItem;
import com.neuronest.backend.admin.AdminBookingDtos.CloseRequest;
import com.neuronest.backend.admin.AdminBookingDtos.DashboardSummary;
import com.neuronest.backend.admin.AdminBookingDtos.RefundNeededItem;
import com.neuronest.backend.admin.AdminBookingDtos.ResponsivenessItem;
import com.neuronest.backend.admin.AdminBookingDtos.StatusNoteRequest;
import com.neuronest.backend.admin.AdminProfessionalDtos.PageResponse;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// The admin's booking overview, the actions on one request, and suspending or reinstating a professional.
// Under /api/admin/**, so the admin security chain has already required a valid admin token.
@RestController
@RequestMapping("/api/admin")
public class AdminBookingController {

    private final AdminBookingService bookings;
    private final AdminSuspensionService suspensions;

    public AdminBookingController(AdminBookingService bookings, AdminSuspensionService suspensions) {
        this.bookings = bookings;
        this.suspensions = suspensions;
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummary summary() {
        return bookings.summary();
    }

    @GetMapping("/bookings")
    public PageResponse<AdminBookingItem> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) Integer unansweredOver,
                                               @RequestParam(required = false) UUID professionalId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "" + AdminProfessionalService.DEFAULT_PAGE_SIZE) int size) {
        return bookings.list(status, unansweredOver, professionalId, page, size);
    }

    @PostMapping("/bookings/{id}/nudge")
    public AdminBookingItem nudge(@PathVariable UUID id) {
        return bookings.nudge(id);
    }

    @PostMapping("/bookings/{id}/close")
    public AdminBookingItem close(@PathVariable UUID id, @RequestBody(required = false) CloseRequest request) {
        return bookings.close(id, request);
    }

    // Payments taken for bookings that are not CONFIRMED (expired or cancelled while paying): to refund by hand.
    @GetMapping("/payments/refunds-needed")
    public PageResponse<RefundNeededItem> refundsNeeded(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AdminProfessionalService.DEFAULT_PAGE_SIZE) int size) {
        return bookings.refundsNeeded(page, size);
    }

    @GetMapping("/professionals/responsiveness")
    public PageResponse<ResponsivenessItem> responsiveness(
            @RequestParam(defaultValue = "" + AdminBookingService.DEFAULT_DAYS) int days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AdminProfessionalService.DEFAULT_PAGE_SIZE) int size) {
        return bookings.responsiveness(days, page, size);
    }

    @PostMapping("/professionals/{id}/suspend")
    public ProfessionalDetail suspend(@PathVariable UUID id, @RequestBody(required = false) StatusNoteRequest request) {
        return suspensions.suspend(id, request);
    }

    @PostMapping("/professionals/{id}/reinstate")
    public ProfessionalDetail reinstate(@PathVariable UUID id, @RequestBody(required = false) StatusNoteRequest request) {
        return suspensions.reinstate(id, request);
    }
}
