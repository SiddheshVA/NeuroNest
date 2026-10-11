package com.neuronest.backend.assessment;

import com.neuronest.backend.safety.CrisisResource;
import com.neuronest.backend.safety.CrisisResources;
import com.neuronest.backend.session.Session;
import com.neuronest.backend.session.SessionAccessService;
import com.neuronest.backend.session.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
public class AssessmentService {

    private final SessionRepository sessionRepository;
    private final SessionAccessService sessionAccessService;
    private final AssessmentResultRepository assessmentResultRepository;
    private final Who5ScoringService who5ScoringService;
    private final Phq9ScoringService phq9ScoringService;
    private final Gad7ScoringService gad7ScoringService;
    private final Pss10ScoringService pss10ScoringService;
    private final K10ScoringService k10ScoringService;

    public AssessmentService(SessionRepository sessionRepository,
                             SessionAccessService sessionAccessService,
                             AssessmentResultRepository assessmentResultRepository,
                             Who5ScoringService who5ScoringService,
                             Phq9ScoringService phq9ScoringService,
                             Gad7ScoringService gad7ScoringService,
                             Pss10ScoringService pss10ScoringService,
                             K10ScoringService k10ScoringService) {
        this.sessionRepository = sessionRepository;
        this.sessionAccessService = sessionAccessService;
        this.assessmentResultRepository = assessmentResultRepository;
        this.who5ScoringService = who5ScoringService;
        this.phq9ScoringService = phq9ScoringService;
        this.gad7ScoringService = gad7ScoringService;
        this.pss10ScoringService = pss10ScoringService;
        this.k10ScoringService = k10ScoringService;
    }

    public AssessmentResponse submitWho5(UUID sessionId, List<Integer> responses, UUID currentUserId) {
        return submit(sessionId, "WHO5", responses, who5ScoringService::score, currentUserId);
    }

    public AssessmentResponse submitPhq9(UUID sessionId, List<Integer> responses, UUID currentUserId) {
        return submit(sessionId, "PHQ9", responses, phq9ScoringService::score, currentUserId);
    }

    public AssessmentResponse submitGad7(UUID sessionId, List<Integer> responses, UUID currentUserId) {
        return submit(sessionId, "GAD7", responses, gad7ScoringService::score, currentUserId);
    }

    public AssessmentResponse submitPss10(UUID sessionId, List<Integer> responses, UUID currentUserId) {
        return submit(sessionId, "PSS10", responses, pss10ScoringService::score, currentUserId);
    }

    public AssessmentResponse submitK10(UUID sessionId, List<Integer> responses, UUID currentUserId) {
        return submit(sessionId, "K10", responses, k10ScoringService::score, currentUserId);
    }

    public List<AssessmentResult> getResults(UUID sessionId, UUID currentUserId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        sessionAccessService.checkAccess(session, currentUserId);

        return assessmentResultRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    private AssessmentResponse submit(UUID sessionId, String instrument, List<Integer> responses,
                                      Function<List<Integer>, ScoreResult> scorer, UUID currentUserId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        sessionAccessService.checkAccess(session, currentUserId);

        ScoreResult result = scorer.apply(responses);

        AssessmentResult entity = new AssessmentResult();
        entity.setSessionId(session.getId());
        entity.setInstrument(instrument);
        entity.setLanguage("en");
        entity.setItemResponses(responses);
        entity.setScore(result.score());
        entity.setSeverityBand(result.severityBand());
        entity.setRiskFlag(result.riskFlag());

        AssessmentResult saved = assessmentResultRepository.save(entity);

        List<CrisisResource> resources = result.riskFlag() ? CrisisResources.INDIA_RESOURCES : null;

        return new AssessmentResponse(saved.getId(), result.score(), result.severityBand(), result.riskFlag(), resources);
    }
}