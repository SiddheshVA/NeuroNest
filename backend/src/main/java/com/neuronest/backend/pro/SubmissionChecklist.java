package com.neuronest.backend.pro;

import com.neuronest.backend.pro.ProExceptions.MissingItem;
import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.professional.DocumentType;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.RegistrationBody;
import com.neuronest.backend.professional.VerificationStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// What a professional must provide before submitting for verification. Used both to block an
// incomplete submit and to show the professional what is still missing.
public final class SubmissionChecklist {

    static final int MIN_BIO_LENGTH = 50;
    static final Set<VerificationStatus> EDITABLE_STATUSES = EnumSet.of(VerificationStatus.DRAFT, VerificationStatus.NEEDS_INFO);

    private SubmissionChecklist() {}

    static boolean isEditable(VerificationStatus status) {
        return EDITABLE_STATUSES.contains(status);
    }

    static boolean isRegistered(CredentialType type) {
        return type == CredentialType.PSYCHIATRIST || type == CredentialType.PSYCHOLOGIST;
    }

    /** The registration body each regulated profession must be registered with, or null if any is fine. */
    static RegistrationBody expectedRegistrationBody(CredentialType type) {
        if (type == CredentialType.PSYCHIATRIST) return RegistrationBody.NMC;
        if (type == CredentialType.PSYCHOLOGIST) return RegistrationBody.RCI;
        return null;
    }

    static List<DocumentType> requiredDocuments(CredentialType type) {
        return isRegistered(type)
                ? List.of(DocumentType.REGISTRATION_CERTIFICATE, DocumentType.ID_PROOF)
                : List.of(DocumentType.DEGREE_CERTIFICATE);
    }

    static List<DocumentType> missingDocuments(CredentialType type, Set<DocumentType> uploaded) {
        return requiredDocuments(type).stream().filter(doc -> !uploaded.contains(doc)).toList();
    }

    static List<MissingItem> missing(Professional p, Set<DocumentType> uploaded, LocalDate today) {
        List<MissingItem> missing = new ArrayList<>();
        if (blank(p.getFullName())) missing.add(new MissingItem("fullName", "Add your full name."));
        if (p.getCredentialType() == null) missing.add(new MissingItem("credentialType", "Choose your credential type."));
        if (blank(p.getContactEmail())) missing.add(new MissingItem("contactEmail", "Your account has no contact email."));
        if (p.getConsultationMode() == null) missing.add(new MissingItem("consultationMode", "Choose how you consult."));
        if (blank(p.getQualifications())) missing.add(new MissingItem("qualifications", "Add your qualifications."));
        if (blank(p.getCity())) missing.add(new MissingItem("city", "Add the city you practise in."));
        if (p.getLanguages().isEmpty()) missing.add(new MissingItem("languages", "Add at least one language."));
        if (p.getSpecialties().isEmpty()) missing.add(new MissingItem("specialties", "Add at least one specialty."));
        if (p.getBio() == null || p.getBio().trim().length() < MIN_BIO_LENGTH) {
            missing.add(new MissingItem("bio", "Write a bio of at least " + MIN_BIO_LENGTH + " characters."));
        }

        CredentialType type = p.getCredentialType();
        if (isRegistered(type)) {
            RegistrationBody expected = expectedRegistrationBody(type);
            if (p.getRegistrationBody() == null) {
                missing.add(new MissingItem("registrationBody", "Add your registration body (" + expected + ")."));
            } else if (p.getRegistrationBody() != expected) {
                missing.add(new MissingItem("registrationBody", "Your registration body must be " + expected + "."));
            }
            if (blank(p.getRegistrationNumber())) missing.add(new MissingItem("registrationNumber", "Add your registration number."));
            if (blank(p.getRegistrationCouncil())) missing.add(new MissingItem("registrationCouncil", "Add your registration council."));
            if (p.getRegistrationYear() == null) missing.add(new MissingItem("registrationYear", "Add your registration year."));
            if (p.getRegistrationExpiry() == null) {
                missing.add(new MissingItem("registrationExpiry", "Add your registration expiry date."));
            } else if (!p.getRegistrationExpiry().isAfter(today)) {
                missing.add(new MissingItem("registrationExpiry", "Your registration expiry date must be in the future."));
            }
        }
        if (type != null) {
            for (DocumentType doc : missingDocuments(type, uploaded)) {
                missing.add(new MissingItem("document:" + doc.name(), "Upload your " + describe(doc) + "."));
            }
        }
        return missing;
    }

    static String describe(DocumentType doc) {
        return switch (doc) {
            case REGISTRATION_CERTIFICATE -> "registration certificate";
            case ID_PROOF -> "proof of identity";
            case DEGREE_CERTIFICATE -> "degree certificate";
            case OTHER -> "document";
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
