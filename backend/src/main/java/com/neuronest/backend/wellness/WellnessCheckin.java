package com.neuronest.backend.wellness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One stored wellness check-in (V21). Only signed-in users' check-ins are stored, and only their owner can
 * read or delete them (WellnessCheckinService). inputs is what was sent to the ML service, result its full
 * response; the other columns are copied out of result for listing and summaries.
 */
@Entity
@Table(name = "wellness_checkin")
public class WellnessCheckin {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "segment", nullable = false)
    private String segment;

    @Column(name = "mood_band", nullable = false)
    private String moodBand;

    @Column(name = "mood_probability", nullable = false)
    private double moodProbability;

    @Column(name = "mood_confidence", nullable = false)
    private String moodConfidence;

    @Column(name = "sleep_class")
    private String sleepClass;

    @Column(name = "sleep_in_range", nullable = false)
    private boolean sleepInRange;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs", nullable = false)
    private Map<String, Object> inputs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", nullable = false)
    private Map<String, Object> result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_versions", nullable = false)
    private Map<String, Object> modelVersions;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getSegment() { return segment; }
    public void setSegment(String segment) { this.segment = segment; }

    public String getMoodBand() { return moodBand; }
    public void setMoodBand(String moodBand) { this.moodBand = moodBand; }

    public double getMoodProbability() { return moodProbability; }
    public void setMoodProbability(double moodProbability) { this.moodProbability = moodProbability; }

    public String getMoodConfidence() { return moodConfidence; }
    public void setMoodConfidence(String moodConfidence) { this.moodConfidence = moodConfidence; }

    public String getSleepClass() { return sleepClass; }
    public void setSleepClass(String sleepClass) { this.sleepClass = sleepClass; }

    public boolean isSleepInRange() { return sleepInRange; }
    public void setSleepInRange(boolean sleepInRange) { this.sleepInRange = sleepInRange; }

    public Map<String, Object> getInputs() { return inputs; }
    public void setInputs(Map<String, Object> inputs) { this.inputs = inputs; }

    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> result) { this.result = result; }

    public Map<String, Object> getModelVersions() { return modelVersions; }
    public void setModelVersions(Map<String, Object> modelVersions) { this.modelVersions = modelVersions; }

    public Instant getCreatedAt() { return createdAt; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
