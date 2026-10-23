package com.neuronest.backend.pro;

import com.neuronest.backend.pro.ProEarningsService.ProEarnings;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// The professional's earnings, behind the /api/pro/** security chain (SecurityConfig). The professional is
// always the one linked to the verified token.
@RestController
@RequestMapping("/api/pro")
public class ProEarningsController {

    private final ProEarningsService service;

    public ProEarningsController(ProEarningsService service) {
        this.service = service;
    }

    @GetMapping("/earnings")
    public ProEarnings earnings(@AuthenticationPrincipal ProPrincipal principal,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "" + ProEarningsService.DEFAULT_PAGE_SIZE) int size) {
        return service.earnings(principal, page, size);
    }
}
