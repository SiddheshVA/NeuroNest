package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminProfessionalDtos.ActiveRequest;
import com.neuronest.backend.admin.AdminProfessionalDtos.PageResponse;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalDetail;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalRequest;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalSummary;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalUpdateResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Admin management of professional listings. Protected by the admin security chain (SecurityConfig):
// every request here carries a valid admin token.
@RestController
@RequestMapping("/api/admin/professionals")
public class AdminProfessionalController {

    private final AdminProfessionalService service;

    public AdminProfessionalController(AdminProfessionalService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ProfessionalSummary> list(
            @RequestParam(required = false) String verificationStatus,
            @RequestParam(required = false) String credentialType,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AdminProfessionalService.DEFAULT_PAGE_SIZE) int size) {
        return service.list(verificationStatus, credentialType, active, q, page, size);
    }

    @GetMapping("/{id}")
    public ProfessionalDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<ProfessionalDetail> create(@RequestBody ProfessionalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ProfessionalUpdateResult update(@PathVariable UUID id, @RequestBody ProfessionalRequest request,
                                           Authentication authentication) {
        return service.update(id, request, authentication.getName());
    }

    @PatchMapping("/{id}/active")
    public ProfessionalDetail setActive(@PathVariable UUID id, @RequestBody ActiveRequest request) {
        return service.setActive(id, request.active());
    }
}
