package com.neuronest.backend.admin;

import com.neuronest.backend.admin.AdminBookingDtos.StatusNoteRequest;
import com.neuronest.backend.admin.AdminProfessionalDtos.ProfessionalDetail;
import com.neuronest.backend.config.ApiValidationException;
import com.neuronest.backend.config.ConflictException;
import com.neuronest.backend.professional.Professional;
import com.neuronest.backend.professional.ProfessionalAccount;
import com.neuronest.backend.professional.ProfessionalAccountRepository;
import com.neuronest.backend.professional.ProfessionalAccountStatus;
import com.neuronest.backend.professional.ProfessionalRepository;
import com.neuronest.backend.professional.VerificationLogEntry;
import com.neuronest.backend.professional.VerificationLogRepository;
import com.neuronest.backend.professional.VerificationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

// Suspending and reinstating a verified professional. Each locks the professional's row and re-checks the
// status inside its transaction, so two quick clicks cannot both act. Suspending disables the account at
// once, so the professional filter answers 403 on their next request; their open booking requests are
// left to expire normally.
@Service
public class AdminSuspensionService {

    static final int MIN_NOTE = 10;
    static final int MAX_NOTE = 1000;
    static final String REGISTRATION_EXPIRED_MESSAGE =
            "The registration has expired. The professional must update it and be reviewed again.";

    private final ProfessionalRepository professionals;
    private final ProfessionalAccountRepository accounts;
    private final VerificationLogRepository verificationLog;
    private final Clock clock;

    @Autowired
    public AdminSuspensionService(ProfessionalRepository professionals, ProfessionalAccountRepository accounts,
                                  VerificationLogRepository verificationLog) {
        this(professionals, accounts, verificationLog, Clock.system(AdminVerificationService.ZONE));
    }

    AdminSuspensionService(ProfessionalRepository professionals, ProfessionalAccountRepository accounts,
                           VerificationLogRepository verificationLog, Clock clock) {
        this.professionals = professionals;
        this.accounts = accounts;
        this.verificationLog = verificationLog;
        this.clock = clock;
    }

    @Transactional
    public ProfessionalDetail suspend(UUID id, StatusNoteRequest request) {
        String note = note(request);
        Professional p = professionals.findByIdForUpdate(id).orElseThrow(() -> new ProfessionalNotFoundException(id));
        if (p.getVerificationStatus() != VerificationStatus.VERIFIED) {
            throw new ConflictException("Only a verified professional can be suspended. This professional's status is "
                    + p.getVerificationStatus().name() + ".");
        }
        setAccounts(id, ProfessionalAccountStatus.DISABLED);
        return change(p, VerificationStatus.SUSPENDED, false, "SUSPENDED", note);
    }

    @Transactional
    public ProfessionalDetail reinstate(UUID id, StatusNoteRequest request) {
        String note = note(request);
        Professional p = professionals.findByIdForUpdate(id).orElseThrow(() -> new ProfessionalNotFoundException(id));
        if (p.getVerificationStatus() != VerificationStatus.SUSPENDED) {
            throw new ConflictException("Only a suspended professional can be reinstated. This professional's status is "
                    + p.getVerificationStatus().name() + ".");
        }
        LocalDate today = LocalDate.now(clock.withZone(AdminVerificationService.ZONE));
        if (p.getRegistrationExpiry() != null && p.getRegistrationExpiry().isBefore(today)) {
            throw new ConflictException(REGISTRATION_EXPIRED_MESSAGE);
        }
        setAccounts(id, ProfessionalAccountStatus.ACTIVE);
        return change(p, VerificationStatus.VERIFIED, true, "REINSTATED", note);
    }

    private void setAccounts(UUID professionalId, ProfessionalAccountStatus status) {
        for (ProfessionalAccount account : accounts.findAllByProfessionalId(professionalId)) {
            account.setStatus(status);
            accounts.saveAndFlush(account);
        }
    }

    private ProfessionalDetail change(Professional p, VerificationStatus to, boolean listed, String action, String note) {
        VerificationStatus from = p.getVerificationStatus();
        p.setVerificationStatus(to);
        p.setVerified(listed);
        p.setActive(listed);
        p.setVerificationNotes(note);
        Professional saved = professionals.saveAndFlush(p);
        verificationLog.save(new VerificationLogEntry(p.getId(), action, from, to, AdminVerificationService.ACTOR, null,
                note, null));
        return ProfessionalDetail.from(saved);
    }

    private static String note(StatusNoteRequest request) {
        String note = request == null || request.note() == null ? "" : request.note().trim();
        if (note.length() < MIN_NOTE || note.length() > MAX_NOTE) {
            throw new ApiValidationException("note is required and must be between " + MIN_NOTE + " and " + MAX_NOTE
                    + " characters.");
        }
        return note;
    }
}
