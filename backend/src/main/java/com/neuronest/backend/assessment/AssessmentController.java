package com.neuronest.backend.assessment;

import com.neuronest.backend.auth.AuthUtil;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions/{sessionId}/assessments")
public class AssessmentController {

    private final AssessmentService assessmentService;

    public AssessmentController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
    }

    @PostMapping("/who5")
    public AssessmentResponse submitWho5(@PathVariable UUID sessionId, @RequestBody AssessmentRequest body, Authentication authentication) {
        return assessmentService.submitWho5(sessionId, body.responses(), AuthUtil.currentUserId(authentication));
    }

    @PostMapping("/phq9")
    public AssessmentResponse submitPhq9(@PathVariable UUID sessionId, @RequestBody AssessmentRequest body, Authentication authentication) {
        return assessmentService.submitPhq9(sessionId, body.responses(), AuthUtil.currentUserId(authentication));
    }

    @PostMapping("/gad7")
    public AssessmentResponse submitGad7(@PathVariable UUID sessionId, @RequestBody AssessmentRequest body, Authentication authentication) {
        return assessmentService.submitGad7(sessionId, body.responses(), AuthUtil.currentUserId(authentication));
    }

    @PostMapping("/pss10")
    public AssessmentResponse submitPss10(@PathVariable UUID sessionId, @RequestBody AssessmentRequest body, Authentication authentication) {
        return assessmentService.submitPss10(sessionId, body.responses(), AuthUtil.currentUserId(authentication));
    }

    @PostMapping("/k10")
    public AssessmentResponse submitK10(@PathVariable UUID sessionId, @RequestBody AssessmentRequest body, Authentication authentication) {
        return assessmentService.submitK10(sessionId, body.responses(), AuthUtil.currentUserId(authentication));
    }

    @GetMapping
    public List<AssessmentResult> getResults(@PathVariable UUID sessionId, Authentication authentication) {
        return assessmentService.getResults(sessionId, AuthUtil.currentUserId(authentication));
    }
}