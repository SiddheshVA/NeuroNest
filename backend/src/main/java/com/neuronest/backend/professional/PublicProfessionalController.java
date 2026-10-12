package com.neuronest.backend.professional;

import com.neuronest.backend.professional.PublicProfessionalDtos.PublicPage;
import com.neuronest.backend.professional.PublicProfessionalDtos.PublicProfessional;
import com.neuronest.backend.professional.PublicProfessionalDtos.PublicProfessionalMeta;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Public, unauthenticated, read-only professional directory (Find Professional). Open to guests via
// the main security chain's permitAll; only publicly visible listings are ever returned.
@RestController
@RequestMapping("/api/professionals")
public class PublicProfessionalController {

    private final PublicProfessionalService service;

    public PublicProfessionalController(PublicProfessionalService service) {
        this.service = service;
    }

    @GetMapping
    public PublicPage<PublicProfessional> list(
            @RequestParam(required = false) String credentialType,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PublicProfessionalService.DEFAULT_PAGE_SIZE) int size) {
        return service.search(credentialType, city, language, mode, q, page, size);
    }

    // Declared before /{id} for readability; Spring prefers the literal path either way.
    @GetMapping("/meta")
    public PublicProfessionalMeta meta() {
        return service.meta();
    }

    // id is taken as a string so a malformed id gets the same 404 as a missing or hidden one.
    @GetMapping("/{id}")
    public PublicProfessional get(@PathVariable String id) {
        return service.get(id);
    }
}
