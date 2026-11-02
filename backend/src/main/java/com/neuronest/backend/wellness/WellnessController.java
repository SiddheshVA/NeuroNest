package com.neuronest.backend.wellness;

import com.neuronest.backend.auth.AuthUtil;
import com.neuronest.backend.wellness.WellnessDtos.CheckinItem;
import com.neuronest.backend.wellness.WellnessDtos.CheckinPage;
import com.neuronest.backend.wellness.WellnessDtos.CheckinRequest;
import com.neuronest.backend.wellness.WellnessDtos.CheckinResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Wellness check-in. These paths are on the main security chain (open to guests); the user comes only from
// the verified Clerk token or the app cookie (AuthUtil), never from the request. The check-in itself works
// for guests and is then not stored; the history endpoints need a signed-in user and only ever see their own.
@RestController
@RequestMapping("/api/wellness")
public class WellnessController {

    private final WellnessCheckinService service;

    public WellnessController(WellnessCheckinService service) {
        this.service = service;
    }

    @PostMapping("/checkin")
    public CheckinResponse checkin(Authentication authentication, @RequestBody(required = false) CheckinRequest request) {
        return service.checkin(request, AuthUtil.currentUserId(authentication));
    }

    @GetMapping("/checkins")
    public CheckinPage list(Authentication authentication,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "" + WellnessCheckinService.DEFAULT_PAGE_SIZE) int size) {
        return service.list(userId(authentication), page, size);
    }

    // 204 when the user has no check-ins yet.
    @GetMapping("/checkins/latest")
    public ResponseEntity<CheckinItem> latest(Authentication authentication) {
        return service.latest(userId(authentication))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping("/checkins/{checkinId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String checkinId) {
        service.delete(userId(authentication), checkinId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/checkins")
    public ResponseEntity<Void> deleteAll(Authentication authentication) {
        service.deleteAll(userId(authentication));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) throw new WellnessExceptions.NotSignedInException();
        return userId;
    }
}
