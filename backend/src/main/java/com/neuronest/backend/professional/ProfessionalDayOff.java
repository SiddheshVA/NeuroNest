package com.neuronest.backend.professional;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// One range of dates a professional is off (V9), both ends included, in India's calendar. The reason is
// private to the professional and never shown publicly.
@Entity
@Table(name = "professional_days_off")
public class ProfessionalDayOff {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProfessionalDayOff() {}

    public ProfessionalDayOff(UUID professionalId, LocalDate dateFrom, LocalDate dateTo, String reason) {
        this.professionalId = professionalId;
        this.dateFrom = dateFrom;
        this.dateTo = dateTo;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public DateRange range() {
        return new DateRange(dateFrom, dateTo);
    }

    public UUID getId() { return id; }
    public UUID getProfessionalId() { return professionalId; }
    public LocalDate getDateFrom() { return dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
