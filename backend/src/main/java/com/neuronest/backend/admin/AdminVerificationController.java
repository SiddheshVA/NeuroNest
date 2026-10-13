package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminProfessionalDtos.PageResponse;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalDetail;
import com.neuronest.backend.admin.AdminVerificationDtos.DocumentFile;
import com.neuronest.backend.admin.AdminVerificationDtos.DocumentView;
import com.neuronest.backend.admin.AdminVerificationDtos.LogEntryView;
import com.neuronest.backend.admin.AdminVerificationDtos.NeedsInfoRequest;
import com.neuronest.backend.admin.AdminVerificationDtos.RejectRequest;
import com.neuronest.backend.admin.AdminVerificationDtos.ReviewQueueItem;
import com.neuronest.backend.admin.AdminVerificationDtos.VerifyRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

// Verification of submitted professionals. Under /api/admin/**, so the admin security chain has
// already required a valid admin token. Uploaded documents are only ever served through this
// controller; there is no public route or static mapping for them.
@RestController
@RequestMapping("/api/admin/professionals")
public class AdminVerificationController {

    private final AdminVerificationService service;

    public AdminVerificationController(AdminVerificationService service) {
        this.service = service;
    }

    @GetMapping("/review-queue")
    public PageResponse<ReviewQueueItem> reviewQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + AdminProfessionalService.DEFAULT_PAGE_SIZE) int size) {
        return service.reviewQueue(page, size);
    }

    @GetMapping("/{id}/documents")
    public List<DocumentView> documents(@PathVariable UUID id) {
        return service.documents(id);
    }

    @GetMapping("/{id}/documents/{docId}/file")
    public ResponseEntity<Resource> documentFile(@PathVariable UUID id, @PathVariable UUID docId) {
        DocumentFile file = service.documentFile(id, docId);
        return ResponseEntity.ok()
                .contentType(mediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(file.path()));
    }

    @GetMapping("/{id}/log")
    public List<LogEntryView> log(@PathVariable UUID id) {
        return service.log(id);
    }

    @PostMapping("/{id}/verify")
    public ProfessionalDetail verify(@PathVariable UUID id, @RequestBody(required = false) VerifyRequest request) {
        return service.verify(id, request);
    }

    @PostMapping("/{id}/needs-info")
    public ProfessionalDetail needsInfo(@PathVariable UUID id, @RequestBody(required = false) NeedsInfoRequest request) {
        return service.needsInfo(id, request);
    }

    @PostMapping("/{id}/reject")
    public ProfessionalDetail reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest request) {
        return service.reject(id, request);
    }

    // The stored type was checked against the file's signature on upload; anything unreadable is sent
    // as plain bytes.
    private static MediaType mediaType(String stored) {
        if (stored == null || stored.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(stored);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
