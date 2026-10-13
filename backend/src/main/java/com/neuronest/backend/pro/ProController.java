package com.neuronest.backend.pro;

import com.neuronest.backend.pro.ProDtos.DocumentView;
import com.neuronest.backend.pro.ProDtos.ProMeResponse;
import com.neuronest.backend.pro.ProDtos.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

// Professional self-service. Every route is behind the /api/pro/** security chain (SecurityConfig),
// which only lets through a valid Clerk token. The professional is always the one linked to that
// token; no route takes a professional id from the client.
@RestController
@RequestMapping("/api/pro")
public class ProController {

    private final ProProfileService profiles;
    private final ProDocumentService documents;

    public ProController(ProProfileService profiles, ProDocumentService documents) {
        this.profiles = profiles;
        this.documents = documents;
    }

    @PostMapping("/register")
    public ResponseEntity<ProMeResponse> register(@AuthenticationPrincipal ProPrincipal principal,
                                                  @RequestBody RegisterRequest request,
                                                  HttpServletRequest httpRequest) {
        // Remote address only: X-Forwarded-For is client-controlled and would let anyone dodge the limit.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(profiles.register(principal, request, httpRequest.getRemoteAddr()));
    }

    @GetMapping("/me")
    public ProMeResponse me(@AuthenticationPrincipal ProPrincipal principal) {
        return profiles.me(principal);
    }

    @PutMapping("/me")
    public ProMeResponse update(@AuthenticationPrincipal ProPrincipal principal, @RequestBody JsonNode body) {
        return profiles.update(principal, body);
    }

    @PostMapping(path = "/me/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentView> upload(@AuthenticationPrincipal ProPrincipal principal,
                                               @RequestParam(name = "docType", required = false) String docType,
                                               @RequestPart(name = "file", required = false) MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.upload(principal, docType, file));
    }

    @DeleteMapping("/me/documents/{docId}")
    public ResponseEntity<Void> deleteDocument(@AuthenticationPrincipal ProPrincipal principal,
                                               @PathVariable String docId) {
        documents.delete(principal, docId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/submit")
    public ProMeResponse submit(@AuthenticationPrincipal ProPrincipal principal) {
        return profiles.submit(principal);
    }
}
