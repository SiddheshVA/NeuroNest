import type { PaymentTotals } from "@/lib/money";
import type { WeeklyHours } from "@/lib/weekly-hours";

// Shapes of the admin professionals API. Field names and enum values mirror the backend's
// AdminProfessionalDtos and the professional enums exactly.

export const CREDENTIAL_TYPES = ["PSYCHOLOGIST", "PSYCHIATRIST", "COUNSELOR"] as const;
export const CONSULTATION_MODES = ["ONLINE", "IN_PERSON", "BOTH"] as const;
export const REGISTRATION_BODIES = ["RCI", "NMC", "OTHER"] as const;
export const VERIFICATION_STATUSES = [
  "DRAFT",
  "SUBMITTED",
  "VERIFIED",
  "NEEDS_INFO",
  "REJECTED",
  "EXPIRED",
  "SUSPENDED",
] as const;

export type CredentialType = (typeof CREDENTIAL_TYPES)[number];
export type ConsultationMode = (typeof CONSULTATION_MODES)[number];
export type RegistrationBody = (typeof REGISTRATION_BODIES)[number];
export type VerificationStatus = (typeof VERIFICATION_STATUSES)[number];

export const CREDENTIAL_TYPE_LABELS: Record<CredentialType, string> = {
  PSYCHOLOGIST: "Psychologist",
  PSYCHIATRIST: "Psychiatrist",
  COUNSELOR: "Counselor",
};

export const CONSULTATION_MODE_LABELS: Record<ConsultationMode, string> = {
  ONLINE: "Online",
  IN_PERSON: "In person",
  BOTH: "Online and in person",
};

export const REGISTRATION_BODY_LABELS: Record<RegistrationBody, string> = {
  RCI: "RCI (Rehabilitation Council of India)",
  NMC: "NMC (National Medical Commission)",
  OTHER: "Other",
};

export const VERIFICATION_STATUS_LABELS: Record<VerificationStatus, string> = {
  DRAFT: "Draft",
  SUBMITTED: "Submitted",
  VERIFIED: "Verified",
  NEEDS_INFO: "Needs information",
  REJECTED: "Rejected",
  EXPIRED: "Expired",
  SUSPENDED: "Suspended",
};

export interface ProfessionalSummary {
  id: string;
  fullName: string;
  credentialType: CredentialType;
  city: string | null;
  verificationStatus: VerificationStatus;
  verified: boolean;
  active: boolean;
  isSampleData: boolean;
  registrationExpiry: string | null;
  createdAt: string;
}

export interface ProfessionalDetail {
  id: string;
  fullName: string;
  credentialType: CredentialType;
  qualifications: string | null;
  registrationBody: RegistrationBody | null;
  registrationNumber: string | null;
  registrationCouncil: string | null;
  registrationYear: number | null;
  registrationExpiry: string | null;
  bio: string | null;
  specialties: string[];
  languages: string[];
  city: string | null;
  state: string | null;
  consultationMode: ConsultationMode;
  /** Rupees per session. */
  consultationFee: number | null;
  yearsExperience: number | null;
  contactEmail: string;
  contactPhone: string | null;
  availabilityNote: string | null;
  photoUrl: string | null;
  isSampleData: boolean;
  active: boolean;
  verificationStatus: VerificationStatus;
  verified: boolean;
  verificationSource: string | null;
  verifiedBy: string | null;
  verifiedAt: string | null;
  verificationNotes: string | null;
  nextRecheckDate: string | null;
  submittedAt: string | null;
  /** Shown read-only in the admin area; the professional edits them on their profile. */
  weeklyHours: WeeklyHours;
  sessionMinutes: number | null;
  bufferMinutes: number;
  /** Read-only for the admin; set by the professional on their own profile. */
  dateOfBirth: string | null;
  socialLinks: Partial<Record<SocialPlatform, string>>;
  declarationAcceptedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

// PUT response: the full record plus the re-review flag, flattened into one object.
export interface ProfessionalUpdateResult extends ProfessionalDetail {
  reReviewRequired: boolean;
}

// Body of POST and PUT. Verification fields and "active" are not accepted by the backend here.
export interface ProfessionalRequest {
  fullName: string;
  credentialType: CredentialType | "";
  qualifications: string | null;
  registrationBody: RegistrationBody | null;
  registrationNumber: string | null;
  registrationCouncil: string | null;
  registrationYear: number | null;
  registrationExpiry: string | null;
  bio: string | null;
  specialties: string[];
  languages: string[];
  city: string | null;
  state: string | null;
  consultationMode: ConsultationMode | "";
  /** Rupees per session. */
  consultationFee: number | null;
  yearsExperience: number | null;
  contactEmail: string;
  contactPhone: string | null;
  availabilityNote: string | null;
  photoUrl: string | null;
  isSampleData: boolean;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ProfessionalListFilters {
  q?: string;
  credentialType?: CredentialType | "";
  verificationStatus?: VerificationStatus | "";
  active?: "true" | "false" | "";
  page: number;
  size: number;
}

// ---- Verification (AdminVerificationDtos and AdminVerificationService) ----

export const SOCIAL_PLATFORMS = ["LINKEDIN", "INSTAGRAM", "FACEBOOK", "X", "YOUTUBE", "WEBSITE"] as const;
export type SocialPlatform = (typeof SOCIAL_PLATFORMS)[number];
export const SOCIAL_PLATFORM_LABELS: Record<SocialPlatform, string> = {
  LINKEDIN: "LinkedIn",
  INSTAGRAM: "Instagram",
  FACEBOOK: "Facebook",
  X: "X (Twitter)",
  YOUTUBE: "YouTube",
  WEBSITE: "Website",
};

export const DOCUMENT_TYPES = ["REGISTRATION_CERTIFICATE", "DEGREE_CERTIFICATE", "ID_PROOF", "OTHER"] as const;
export type DocumentType = (typeof DOCUMENT_TYPES)[number];
export const DOCUMENT_TYPE_LABELS: Record<DocumentType, string> = {
  REGISTRATION_CERTIFICATE: "Registration certificate",
  DEGREE_CERTIFICATE: "Degree certificate",
  ID_PROOF: "Government ID proof",
  OTHER: "Additional document",
};

// The admin record has no requiredDocuments, so this mirrors SubmissionChecklist.requiredDocuments:
// the registration certificate is optional only for counselors.
export function requiredDocuments(type: CredentialType): DocumentType[] {
  return type === "COUNSELOR"
    ? ["DEGREE_CERTIFICATE", "ID_PROOF"]
    : ["REGISTRATION_CERTIFICATE", "DEGREE_CERTIFICATE", "ID_PROOF"];
}

export const VERIFICATION_SOURCES = ["NMC", "STATE_COUNCIL", "RCI", "DEGREE_CERTIFICATE", "OTHER"] as const;
export type VerificationSource = (typeof VERIFICATION_SOURCES)[number];
export const VERIFICATION_SOURCE_LABELS: Record<VerificationSource, string> = {
  NMC: "NMC Indian Medical Register",
  STATE_COUNCIL: "State Medical Council register",
  RCI: "RCI register",
  DEGREE_CERTIFICATE: "Degree certificate",
  OTHER: "Other",
};

/** Mirrors AdminVerificationService.allowedSources. */
export function allowedSources(type: CredentialType): VerificationSource[] {
  if (type === "PSYCHIATRIST") return ["NMC", "STATE_COUNCIL", "OTHER"];
  if (type === "PSYCHOLOGIST") return ["RCI", "OTHER"];
  return ["DEGREE_CERTIFICATE", "OTHER"];
}

/** Mirrors AdminVerificationService.REGISTERED_CHECKLIST and COUNSELOR_CHECKLIST, with the labels shown. */
export function verificationChecklist(type: CredentialType): { key: string; label: string }[] {
  if (type === "COUNSELOR") {
    return [
      { key: "nameMatchesDegree", label: "The name on the degree certificate matches the profile." },
      { key: "degreeCertificateSeen", label: "I have seen the degree certificate." },
      { key: "credentialTypeMatches", label: "The credential type matches what the person claims." },
    ];
  }
  return [
    { key: "nameMatchesRegister", label: "The name matches the official register and the ID proof." },
    { key: "registrationNumberChecked", label: "I checked the registration number on the official register." },
    { key: "registrationActive", label: "The registration is active and its expiry date is in the future." },
    { key: "credentialTypeMatches", label: "The credential type matches what the person claims." },
    { key: "idMatchesPerson", label: "The ID proof matches the person." },
  ];
}

export const LOG_ACTION_LABELS: Record<string, string> = {
  SUBMITTED: "Submitted",
  VERIFIED: "Verified",
  NEEDS_INFO: "Sent back for more information",
  REJECTED: "Rejected",
  EXPIRED: "Expired",
  SUSPENDED: "Suspended",
  REINSTATED: "Reinstated",
};

export interface ReviewQueueItem {
  id: string;
  fullName: string;
  credentialType: CredentialType;
  city: string | null;
  submittedAt: string | null;
  documentCount: number;
  registrationBody: RegistrationBody | null;
  registrationNumber: string | null;
  registrationExpiry: string | null;
}

export interface AdminDocument {
  id: string;
  docType: DocumentType;
  originalFilename: string | null;
  contentType: string | null;
  sizeBytes: number | null;
  uploadedAt: string;
}

export interface LogEntry {
  action: string;
  fromStatus: string | null;
  toStatus: string | null;
  actor: string;
  source: string | null;
  note: string | null;
  evidenceDocumentId: string | null;
  createdAt: string;
}

/** Body of POST /{id}/verify. Empty optional values are left out. */
export interface VerifyRequest {
  source: VerificationSource;
  registrationExpiry?: string;
  nextRecheckDate?: string;
  notes?: string;
  evidenceDocumentId?: string;
  activate: boolean;
  checklist: Record<string, boolean>;
}

// ---- Booking requests (AdminBookingDtos) ----

export const BOOKING_STATUSES = [
  "PENDING",
  "AWAITING_PAYMENT",
  "CONFIRMED",
  "ALTERNATE_PROPOSED",
  "DECLINED",
  "CANCELLED",
  "EXPIRED",
] as const;
export type BookingStatus = (typeof BOOKING_STATUSES)[number];

export const BOOKING_STATUS_LABELS: Record<BookingStatus, string> = {
  PENDING: "Pending",
  AWAITING_PAYMENT: "Awaiting payment",
  CONFIRMED: "Confirmed",
  ALTERNATE_PROPOSED: "Waiting for the person",
  DECLINED: "Declined",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
};

export type RespondedBy = "PROFESSIONAL" | "ADMIN" | "USER";

export const RESPONDED_BY_LABELS: Record<RespondedBy, string> = {
  PROFESSIONAL: "Professional",
  ADMIN: "Admin",
  USER: "Person",
};

export interface DashboardSummary {
  reviewQueue: number;
  reviewQueueOver48h: number;
  unansweredOver48h: number;
  pendingTotal: number;
  awaitingUserTotal: number;
  /** Paid (or free) appointments still to come. */
  upcomingConfirmed: number;
  /** Accepted by the professional, waiting for the person's payment (deadline not passed). */
  awaitingPaymentTotal: number;
  professionalsVerifiedActive: number;
  professionalsNeedsInfo: number;
  registrationsExpiringIn30Days: number;
  last30Days: {
    requestsCreated: number;
    answered: number;
    expired: number;
    cancelledByUser: number;
    responseRatePercent: number;
    medianHoursToFirstResponse: number | null;
  };
}

/** One booking request as the admin sees it: never the person's contact details, notes, assessment or meeting details. */
export interface AdminBookingItem {
  id: string;
  status: BookingStatus;
  createdAt: string;
  expiresAt: string | null;
  hoursWaiting: number;
  professionalId: string;
  professionalName: string;
  contactName: string;
  preferredMode: "ONLINE" | "IN_PERSON" | null;
  preferredMethod: "PHONE" | "VIDEO" | null;
  /** ISO date-time with the +05:30 offset. */
  scheduledOrRequestedTime: string | null;
  proposedDatetime: string | null;
  respondedBy: RespondedBy | null;
  respondedAt: string | null;
  adminNudgedAt: string | null;
  adminNote: string | null;
  /**
   * The person's deadline to pay while AWAITING_PAYMENT; on an EXPIRED booking it means it expired unpaid.
   * Null otherwise, including an unanswered request that expired.
   */
  paymentDueAt: string | null;
}

export interface BookingListFilters {
  status: BookingStatus | "";
  unansweredOver: number | null;
  professionalId: string;
  page: number;
  size: number;
}

export interface ResponsivenessItem {
  professionalId: string;
  fullName: string;
  received: number;
  answered: number;
  expired: number;
  pendingNow: number;
  medianHoursToFirstResponse: number | null;
  lastAnsweredAt: string | null;
}

// ---- Payments and commission (/api/admin/payments...) ----

/** GET /api/admin/payments/summary. Totals in paise; only paid payments whose booking is CONFIRMED. */
export interface AdminPaymentSummary {
  /** The current month in India, YYYY-MM. */
  month: string;
  allTime: PaymentTotals;
  thisMonth: PaymentTotals;
}

/** One PAID payment. counted is false when its booking is not CONFIRMED (a refund is needed). */
export interface AdminPaymentItem {
  paymentId: string;
  bookingId: string;
  professionalId: string;
  professionalName: string;
  sessionTime: string | null;
  paidAt: string | null;
  amountPaise: number;
  commissionPaise: number;
  professionalSharePaise: number;
  currency: string;
  bookingStatus: string;
  counted: boolean;
}

/** GET /api/admin/payments/refunds-needed: money taken for a booking that is not CONFIRMED. */
export interface RefundNeededItem {
  paymentId: string;
  bookingId: string;
  bookingStatus: string;
  razorpayOrderId: string;
  razorpayPaymentId: string | null;
  amountPaise: number;
  currency: string;
  paidAt: string | null;
  professionalId: string;
  professionalName: string;
  contactName: string;
}
