package com.neuronest.backend.professional;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Maps the professionals table (hand-made baseline + V2 verification columns + V4 profile fields). Flyway owns the schema.
@Entity
@Table(name = "professionals")
public class Professional {

    /** Highest accepted consultation fee, in rupees per session. */
    public static final int MAX_CONSULTATION_FEE = 100_000;

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false)
    private CredentialType credentialType;

    @Column(name = "qualifications")
    private String qualifications;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_body")
    private RegistrationBody registrationBody;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "registration_council")
    private String registrationCouncil;

    @Column(name = "registration_year")
    private Integer registrationYear;

    @Column(name = "registration_expiry")
    private LocalDate registrationExpiry;

    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    @Column(name = "bio")
    private String bio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specialties", nullable = false)
    private List<String> specialties = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "languages", nullable = false)
    private List<String> languages = new ArrayList<>();

    @Column(name = "city")
    private String city;

    @Column(name = "state")
    private String state;

    @Enumerated(EnumType.STRING)
    @Column(name = "consultation_mode", nullable = false)
    private ConsultationMode consultationMode;

    // Fee for one session, in rupees (V3 replaced the old fee range with this single value).
    @Column(name = "consultation_fee")
    private Integer consultationFee;

    @Column(name = "years_experience")
    private Integer yearsExperience;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "availability_note")
    private String availabilityNote;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "active", nullable = false)
    private boolean active = false;

    @Column(name = "is_sample_data", nullable = false)
    private boolean sampleData = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private VerificationStatus verificationStatus = VerificationStatus.DRAFT;

    @Column(name = "verification_source")
    private String verificationSource;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verification_notes")
    private String verificationNotes;

    @Column(name = "next_recheck_date")
    private LocalDate nextRecheckDate;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    // V4: identity and verification details. Never part of any public view.
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    // Platform key (LINKEDIN, INSTAGRAM, ...) to profile link.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "social_links", nullable = false)
    private Map<String, String> socialLinks = new LinkedHashMap<>();

    // When the professional last accepted the submission declaration.
    @Column(name = "declaration_accepted_at")
    private Instant declarationAcceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // No database trigger maintains this, so the entity does.
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public CredentialType getCredentialType() { return credentialType; }
    public void setCredentialType(CredentialType credentialType) { this.credentialType = credentialType; }

    public String getQualifications() { return qualifications; }
    public void setQualifications(String qualifications) { this.qualifications = qualifications; }

    public RegistrationBody getRegistrationBody() { return registrationBody; }
    public void setRegistrationBody(RegistrationBody registrationBody) { this.registrationBody = registrationBody; }

    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }

    public String getRegistrationCouncil() { return registrationCouncil; }
    public void setRegistrationCouncil(String registrationCouncil) { this.registrationCouncil = registrationCouncil; }

    public Integer getRegistrationYear() { return registrationYear; }
    public void setRegistrationYear(Integer registrationYear) { this.registrationYear = registrationYear; }

    public LocalDate getRegistrationExpiry() { return registrationExpiry; }
    public void setRegistrationExpiry(LocalDate registrationExpiry) { this.registrationExpiry = registrationExpiry; }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public List<String> getSpecialties() { return specialties; }
    public void setSpecialties(List<String> specialties) { this.specialties = specialties; }

    public List<String> getLanguages() { return languages; }
    public void setLanguages(List<String> languages) { this.languages = languages; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public ConsultationMode getConsultationMode() { return consultationMode; }
    public void setConsultationMode(ConsultationMode consultationMode) { this.consultationMode = consultationMode; }

    public Integer getConsultationFee() { return consultationFee; }
    public void setConsultationFee(Integer consultationFee) { this.consultationFee = consultationFee; }

    public Integer getYearsExperience() { return yearsExperience; }
    public void setYearsExperience(Integer yearsExperience) { this.yearsExperience = yearsExperience; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getAvailabilityNote() { return availabilityNote; }
    public void setAvailabilityNote(String availabilityNote) { this.availabilityNote = availabilityNote; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isSampleData() { return sampleData; }
    public void setSampleData(boolean sampleData) { this.sampleData = sampleData; }

    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(VerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }

    public String getVerificationSource() { return verificationSource; }
    public void setVerificationSource(String verificationSource) { this.verificationSource = verificationSource; }

    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public String getVerificationNotes() { return verificationNotes; }
    public void setVerificationNotes(String verificationNotes) { this.verificationNotes = verificationNotes; }

    public LocalDate getNextRecheckDate() { return nextRecheckDate; }
    public void setNextRecheckDate(LocalDate nextRecheckDate) { this.nextRecheckDate = nextRecheckDate; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public Map<String, String> getSocialLinks() { return socialLinks; }
    public void setSocialLinks(Map<String, String> socialLinks) { this.socialLinks = socialLinks; }

    public Instant getDeclarationAcceptedAt() { return declarationAcceptedAt; }
    public void setDeclarationAcceptedAt(Instant declarationAcceptedAt) { this.declarationAcceptedAt = declarationAcceptedAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
