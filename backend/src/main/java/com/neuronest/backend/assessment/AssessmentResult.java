package com.neuronest.backend.assessment;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "assessment_results")
public class AssessmentResult {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "instrument", nullable = false)
    private String instrument;

    @Column(name = "language", nullable = false)
    private String language = "en";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_responses", nullable = false)
    private List<Integer> itemResponses;

    @Column(name = "score", nullable = false)
    private Integer score;

    @Column(name = "severity_band", nullable = false)
    private String severityBand;

    @Column(name = "risk_flag", nullable = false)
    private boolean riskFlag = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public List<Integer> getItemResponses() { return itemResponses; }
    public void setItemResponses(List<Integer> itemResponses) { this.itemResponses = itemResponses; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getSeverityBand() { return severityBand; }
    public void setSeverityBand(String severityBand) { this.severityBand = severityBand; }

    public boolean isRiskFlag() { return riskFlag; }
    public void setRiskFlag(boolean riskFlag) { this.riskFlag = riskFlag; }

    public Instant getCreatedAt() { return createdAt; }
}
