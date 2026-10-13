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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// What a professional must provide before submitting for verification. Used both to block an
// incomplete submit and to show the professional what is still missing.
public final class SubmissionChecklist {

    static final int MIN_BIO_LENGTH = 50;
    static final int MIN_AVAILABILITY_NOTE = 10;
    static final int MAX_YEARS_EXPERIENCE = 60;
    static final Set<VerificationStatus> EDITABLE_STATUSES = EnumSet.of(VerificationStatus.DRAFT, VerificationStatus.NEEDS_INFO);

    private SubmissionChecklist() {}

    static boolean isEditable(VerificationStatus status) {
        return EDITABLE_STATUSES.contains(status);
    }

    static boolean isRegistered(CredentialType type) {
        return type == CredentialType.PSYCHIATRIST || type == CredentialType.PSYCHOLOGIST;
    }

    /** The registration bodies each credential type may use. Counselors may use any (or none). */
    static Set<RegistrationBody> allowedRegistrationBodies(CredentialType type) {
        if (type == CredentialType.PSYCHIATRIST) return EnumSet.of(RegistrationBody.NMC, RegistrationBody.OTHER);
        if (type == CredentialType.PSYCHOLOGIST) return EnumSet.of(RegistrationBody.RCI, RegistrationBody.OTHER);
        return EnumSet.allOf(RegistrationBody.class);
    }

    /** "NMC or OTHER": the allowed bodies in a readable list. */
    static String describeAllowed(CredentialType type) {
        return allowedRegistrationBodies(type).stream().map(Enum::name).collect(Collectors.joining(" or "))
                .replaceFirst(" or (?=.* or )", ", ");
    }

    /** The council field must be filled when any body is set (always for OTHER, where it names the body). */
    static boolean councilRequired(CredentialType type, RegistrationBody body) {
        return body != null || isRegistered(type);
    }

    /** A 10-digit Indian mobile number starting 6 to 9, optionally written with +91 or a leading 0. */
    static final Pattern INDIAN_MOBILE = Pattern.compile("^(?:\\+91|0)?([6-9][0-9]{9})$");

    /** The number as +91XXXXXXXXXX, or null when it is not a valid Indian mobile number. */
    static String normalizeIndianMobile(String value) {
        if (value == null) return null;
        Matcher m = INDIAN_MOBILE.matcher(value.replaceAll("[\\s()\\-]", ""));
        return m.matches() ? "+91" + m.group(1) : null;
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

        if (p.getYearsExperience() == null) {
            missing.add(new MissingItem("yearsExperience", "Add your years of experience."));
        }
        if (p.getConsultationFee() == null) {
            missing.add(new MissingItem("consultationFee", "Add your consultation fee. Enter 0 if you do not charge a fee."));
        }
        String note = p.getAvailabilityNote() == null ? "" : p.getAvailabilityNote().trim();
        if (note.length() < MIN_AVAILABILITY_NOTE) {
            missing.add(new MissingItem("availabilityNote", "Add an availability note of at least "
                    + MIN_AVAILABILITY_NOTE + " characters."));
        }
        if (blank(p.getContactPhone())) {
            missing.add(new MissingItem("contactPhone", "Add your contact phone number."));
        } else if (normalizeIndianMobile(p.getContactPhone()) == null) {
            missing.add(new MissingItem("contactPhone", "Your contact phone must be a 10-digit Indian mobile number."));
        }

        CredentialType type = p.getCredentialType();
        RegistrationBody body = p.getRegistrationBody();
        if (isRegistered(type) && body == null) {
            missing.add(new MissingItem("registrationBody", "Choose your registration body (" + describeAllowed(type) + ")."));
        } else if (body != null && !allowedRegistrationBodies(type).contains(body)) {
            missing.add(new MissingItem("registrationBody", "Your registration body must be " + describeAllowed(type)
                    + " for a " + type.name().toLowerCase(Locale.ROOT) + "."));
        }
        if (councilRequired(type, body) && blank(p.getRegistrationCouncil())) {
            missing.add(new MissingItem("registrationCouncil", body == RegistrationBody.OTHER
                    ? "Add the name of your registration body."
                    : "Add the council that issued your registration."));
        }
        if (isRegistered(type)) {
            if (blank(p.getRegistrationNumber())) missing.add(new MissingItem("registrationNumber", "Add your registration number."));
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
