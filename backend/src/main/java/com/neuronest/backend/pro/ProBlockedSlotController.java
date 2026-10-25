package com.neuronest.backend.pro;

import com.neuronest.backend.pro.ProBlockedSlotDtos.BlockSlotRequest;
import com.neuronest.backend.pro.ProBlockedSlotDtos.BlockWeek;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// "Block slots" behind the /api/pro/** security chain (SecurityConfig): the professional is always the one
// linked to the verified token. Each change answers with the updated week, so the screen redraws from it.
@RestController
@RequestMapping("/api/pro/blocked-slots")
public class ProBlockedSlotController {

    private final ProBlockedSlotService service;

    public ProBlockedSlotController(ProBlockedSlotService service) {
        this.service = service;
    }

    @GetMapping
    public BlockWeek week(@AuthenticationPrincipal ProPrincipal principal) {
        return service.week(principal);
    }

    @PostMapping
    public BlockWeek block(@AuthenticationPrincipal ProPrincipal principal,
                           @RequestBody(required = false) BlockSlotRequest request) {
        return service.block(principal, request);
    }

    // The id is taken as text so a malformed one gets the same 404 as a missing one.
    @DeleteMapping("/{id}")
    public BlockWeek unblock(@AuthenticationPrincipal ProPrincipal principal, @PathVariable String id) {
        return service.unblock(principal, id);
    }
}
