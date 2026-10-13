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
