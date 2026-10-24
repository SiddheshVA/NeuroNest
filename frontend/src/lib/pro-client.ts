import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "@clerk/clerk-react";
import { useNavigate } from "react-router-dom";
import { API_BASE } from "./api";
import type { WeeklyHours } from "./weekly-hours";
import type { ReviewPage } from "./professionals-client";
import type {
  ConfirmBookingBody,
  DeclineBookingBody,
  ProBooking,
  ProBookingPage,
  ProBookingSummary,
  ProposeBookingBody,
} from "./pro-bookings";
import { normalizeProBooking, normalizeProBookingPage } from "./pro-bookings";
import {
  normalizeDayOffCreated,
  normalizeProDashboard,
  normalizeProEarnings,
  type ProAppointmentPage,
  type ProDashboard,
  type ProDayOff,
  type ProDayOffCreated,
  type ProDayOffRequest,
  type ProEarnings,
} from "./pro-home";

/**
 * Client for the professional self-service API (/api/pro/**). Shapes mirror the backend's ProDtos
 * and ProExceptions. Every request gets a fresh Clerk session token as a Bearer header; nothing else
 * (no cookie) is sent.
 */

export const PRO_CREDENTIAL_TYPES = ["PSYCHOLOGIST", "PSYCHIATRIST", "COUNSELOR"] as const;
export const PRO_CONSULTATION_MODES = ["ONLINE", "IN_PERSON", "BOTH"] as const;
export const PRO_REGISTRATION_BODIES = ["RCI", "NMC", "OTHER"] as const;
export const PRO_DOCUMENT_TYPES = ["REGISTRATION_CERTIFICATE", "ID_PROOF", "DEGREE_CERTIFICATE", "OTHER"] as const;

export type ProCredentialType = (typeof PRO_CREDENTIAL_TYPES)[number];
export type ProConsultationMode = (typeof PRO_CONSULTATION_MODES)[number];
export type ProRegistrationBody = (typeof PRO_REGISTRATION_BODIES)[number];
export type ProDocumentType = (typeof PRO_DOCUMENT_TYPES)[number];
export type ProVerificationStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "VERIFIED"
  | "NEEDS_INFO"
  | "REJECTED"
  | "EXPIRED"
  | "SUSPENDED";

export const PRO_CREDENTIAL_TYPE_LABELS: Record<ProCredentialType, string> = {
  PSYCHOLOGIST: "Psychologist",
  PSYCHIATRIST: "Psychiatrist",
  COUNSELOR: "Counselor",
};

export const PRO_CONSULTATION_MODE_LABELS: Record<ProConsultationMode, string> = {
  ONLINE: "Online",
  IN_PERSON: "In person",
  BOTH: "Online and in person",
};

export const PRO_REGISTRATION_BODY_LABELS: Record<ProRegistrationBody, string> = {
  RCI: "RCI (Rehabilitation Council of India)",
  NMC: "NMC (National Medical Commission)",
  OTHER: "Other (please specify)",
};

export const PRO_DOCUMENT_TYPE_LABELS: Record<ProDocumentType, string> = {
  REGISTRATION_CERTIFICATE: "Registration certificate",
  ID_PROOF: "Government ID proof",
  DEGREE_CERTIFICATE: "Degree certificate",
  OTHER: "Additional document",
};

/** The fixed "Specializations" choices. Anything else saved is shown as the "Other" text. */
export const PRO_SPECIALIZATIONS = [
  "Clinical Psychologist",
  "Counselling Psychologist",
  "Rehabilitation Psychologist",
  "Psychiatrist",
  "Counsellor / Therapist",
  "Child & Adolescent Specialist",
  "Marriage & Family Therapist",
  "Addiction Specialist",
  "Neuropsychologist",
  "Health Psychologist",
  "School Psychologist",
  "Career Counsellor",
  "Mental Health Social Worker",
  "Wellness / Life Coach",
] as const;
export const PRO_SPECIALIZATION_OTHER = "Other";

export const PRO_SOCIAL_PLATFORMS = ["LINKEDIN", "INSTAGRAM", "FACEBOOK", "X", "YOUTUBE", "WEBSITE"] as const;
export type ProSocialPlatform = (typeof PRO_SOCIAL_PLATFORMS)[number];

/** Label, example link and allowed hosts (subdomains allowed) for each platform; mirrors SocialLinks.java. */
export const PRO_SOCIAL_PLATFORM_INFO: Record<ProSocialPlatform, { label: string; placeholder: string; hosts: string[] }> = {
  LINKEDIN: { label: "LinkedIn", placeholder: "https://www.linkedin.com/in/your-name", hosts: ["linkedin.com"] },
  INSTAGRAM: { label: "Instagram", placeholder: "https://www.instagram.com/your-name", hosts: ["instagram.com"] },
  FACEBOOK: { label: "Facebook", placeholder: "https://www.facebook.com/your-name", hosts: ["facebook.com"] },
  X: { label: "X (Twitter)", placeholder: "https://x.com/your-name", hosts: ["x.com", "twitter.com"] },
  YOUTUBE: { label: "YouTube", placeholder: "https://www.youtube.com/@your-channel", hosts: ["youtube.com", "youtu.be"] },
  WEBSITE: { label: "Website", placeholder: "https://www.your-practice.in", hosts: [] },
};

/**
 * Indian states and union territories, spelled exactly as IndianStates.java on the backend. Only used on
 * the profile page before the person has an account; afterwards GET /api/pro/me sends the backend's own
 * list (stateOptions). Keep the two lists the same.
 */
export const PRO_INDIAN_STATES: string[] = [
  "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
  "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
  "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
  "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal",
  "Andaman and Nicobar Islands", "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu", "Delhi",
  "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry",
];

/** The registration bodies each credential type may use (mirrors SubmissionChecklist), in display order. */
export function allowedRegistrationBodies(type: ProCredentialType | ""): ProRegistrationBody[] {
  if (type === "PSYCHIATRIST") return ["NMC", "OTHER"];
  if (type === "PSYCHOLOGIST") return ["RCI", "OTHER"];
  if (type === "COUNSELOR") return ["NMC", "RCI", "OTHER"];
  return [];
}

/** A 10-digit Indian mobile number starting 6 to 9, optionally with +91 or a leading 0 (mirrors the backend). */
export function normalizeIndianMobile(value: string): string | null {
  const match = /^(?:\+91|0)?([6-9][0-9]{9})$/.exec(value.replace(/[\s()-]/g, ""));
  return match ? `+91${match[1]}` : null;
}

export interface ProMissingItem {
  item: string;
  message: string;
}

export interface ProProfile {
  id: string;
  fullName: string;
  credentialType: ProCredentialType;
  qualifications: string | null;
  registrationBody: ProRegistrationBody | null;
  registrationNumber: string | null;
  registrationCouncil: string | null;
  registrationYear: number | null;
  registrationExpiry: string | null;
  bio: string | null;
  specialties: string[];
  languages: string[];
  city: string | null;
  state: string | null;
  consultationMode: ProConsultationMode;
  /** Rupees per session. */
  consultationFee: number | null;
  yearsExperience: number | null;
  contactEmail: string;
  contactPhone: string | null;
  availabilityNote: string | null;
  /** Days and times in Indian Standard Time (see lib/weekly-hours). */
  weeklyHours: WeeklyHours;
  sessionMinutes: number | null;
  /** Gap after each session; never shown publicly. */
  bufferMinutes: number;
  photoUrl: string | null;
  /** YYYY-MM-DD. Only ever shown to the professional and the admin. */
  dateOfBirth: string | null;
  /** Only ever shown to the professional and the admin. */
  socialLinks: Partial<Record<ProSocialPlatform, string>>;
  verificationStatus: ProVerificationStatus;
  verified: boolean;
  active: boolean;
  submittedAt: string | null;
  verifiedAt: string | null;
  nextRecheckDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ProDocument {
  id: string;
  docType: ProDocumentType;
  originalFilename: string | null;
  sizeBytes: number | null;
  uploadedAt: string;
}

export interface ProMe {
  account: { status: "INVITED" | "ACTIVE" | "DISABLED"; email: string; createdAt: string };
  profile: ProProfile;
  verificationNote: { note: string; action: string | null; at: string | null } | null;
  documents: ProDocument[];
  /** For the saved credential type. */
  requiredDocuments: ProDocumentType[];
  optionalDocuments: ProDocumentType[];
  /** The same rule for every credential type, from the backend (SubmissionChecklist). */
  documentRules: Record<ProCredentialType, { required: ProDocumentType[]; optional: ProDocumentType[] }>;
  /** The states and union territories the backend accepts, in display order (IndianStates). */
  stateOptions: string[];
  missingDocuments: ProDocumentType[];
  missingForSubmit: ProMissingItem[];
  canEditCredentials: boolean;
  canManageDocuments: boolean;
  canSubmit: boolean;
}

/** Body of PUT /api/pro/me. Only fields present are changed; the backend ignores anything else. */
export type ProUpdate = Partial<{
  fullName: string;
  dateOfBirth: string | null;
  credentialType: ProCredentialType;
  qualifications: string | null;
  registrationBody: ProRegistrationBody | null;
  registrationNumber: string | null;
  registrationCouncil: string | null;
  registrationYear: number | null;
  registrationExpiry: string | null;
  bio: string | null;
  specialties: string[];
  languages: string[];
  city: string | null;
  state: string | null;
  consultationMode: ProConsultationMode;
  consultationFee: number | null;
  yearsExperience: number | null;
  availabilityNote: string | null;
  photoUrl: string | null;
  contactPhone: string | null;
  socialLinks: Partial<Record<ProSocialPlatform, string>>;
  weeklyHours: WeeklyHours;
  sessionMinutes: number | null;
  bufferMinutes: number;
}>;

export const DISABLED_ACCOUNT_MESSAGE = "This professional account has been disabled. Please contact support.";
const BACKEND_DISABLED_PREFIX = "This professional account has been disabled";
const NETWORK_MESSAGE = "We could not reach NeuroNest. Please check your connection and try again.";
const SESSION_MESSAGE =
  "We could not confirm your sign-in with NeuroNest. Please try again. If this keeps happening, sign out and sign in again.";

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

// "unauthorized": signed out, sent to sign in. "session": signed in at Clerk, but the token could
// not be obtained or was refused; the page shows an error with Retry instead of redirecting.
export type ProErrorKind = "unauthorized" | "session" | "disabled" | "not-registered" | "other";

export class ProApiError extends Error {
  readonly status: number;
  readonly kind: ProErrorKind;
  /** The backend's list of missing items, on a 400 from submit. */
  readonly missing: ProMissingItem[];

  constructor(status: number, message: string, kind: ProErrorKind = "other", missing: ProMissingItem[] = []) {
    super(message);
    this.status = status;
    this.kind = kind;
    this.missing = missing;
  }
}

/**
 * The professional API, bound to the signed-in Clerk user. Pages call it only once Clerk has loaded.
 * It sends the person to sign in only when Clerk says they are signed out; if they are signed in but
 * the token is missing or refused, it retries once with a fresh token and then reports an error
 * (kind "session") rather than redirecting. A disabled account sets `disabled`, and pages show only
 * that message.
 */
export function useProApi() {
  const { getToken, isSignedIn } = useAuth();
  const navigate = useNavigate();
  const [disabled, setDisabled] = useState(false);

  // Kept in refs so the API object stays the same across renders even if Clerk hands out a new
  // getToken function; pages load data in effects that depend on it.
  const getTokenRef = useRef(getToken);
  const signedInRef = useRef(isSignedIn);
  useEffect(() => {
    getTokenRef.current = getToken;
    signedInRef.current = isSignedIn;
  }, [getToken, isSignedIn]);

  // A fresh token for every request. Right after a page refresh Clerk can report that it has loaded
  // a moment before the session token is ready, so a missing token is retried briefly.
  const freshToken = useCallback(async (skipCache: boolean): Promise<string | null> => {
    for (let attempt = 0; attempt < 4; attempt++) {
      const token = await getTokenRef.current(skipCache ? { skipCache: true } : undefined);
      if (token) return token;
      if (!signedInRef.current) return null;
      await sleep(250 * (attempt + 1));
    }
    return null;
  }, []);

  // Signed-out professionals sign in on /pro/join, which always continues to /pro/dashboard.
  const goToSignIn = useCallback(() => {
    navigate("/pro/join", { replace: true });
  }, [navigate]);

  const request = useCallback(
    async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
      const send = async (skipCache: boolean): Promise<Response | null> => {
        const token = await freshToken(skipCache);
        if (!token) return null;
        const headers = new Headers(init.headers);
        headers.set("Authorization", `Bearer ${token}`);
        if (typeof init.body === "string") headers.set("Content-Type", "application/json");
        try {
          return await fetch(`${API_BASE}${path}`, { ...init, headers, credentials: "omit" });
        } catch {
          throw new ProApiError(0, NETWORK_MESSAGE);
        }
      };
      const notAuthenticated = (): never => {
        if (!signedInRef.current) {
          goToSignIn();
          throw new ProApiError(401, "Please sign in to continue.", "unauthorized");
        }
        throw new ProApiError(401, SESSION_MESSAGE, "session");
      };

      let response = await send(false);
      if (!response) return notAuthenticated();
      // One retry with a token fetched straight from Clerk, in case the cached one had just expired.
      if (response.status === 401 && signedInRef.current) {
        response = await send(true);
        if (!response) return notAuthenticated();
      }

      if (response.status === 204) return undefined as T;
      let body: unknown = null;
      try {
        body = await response.json();
      } catch {
        // Empty or non-JSON body.
      }
      if (response.ok) return body as T;

      const error = (body as { error?: unknown } | null)?.error;
      const message = typeof error === "string" ? error : "Something went wrong. Please try again.";
      if (response.status === 401) return notAuthenticated();
      if (response.status === 403 && message.startsWith(BACKEND_DISABLED_PREFIX)) {
        setDisabled(true);
        throw new ProApiError(403, DISABLED_ACCOUNT_MESSAGE, "disabled");
      }
      if (response.status === 404 && path === "/pro/me") {
        throw new ProApiError(404, message, "not-registered");
      }
      const missing = (body as { missing?: unknown } | null)?.missing;
      throw new ProApiError(response.status, message, "other", Array.isArray(missing) ? (missing as ProMissingItem[]) : []);
    },
    [freshToken, goToSignIn],
  );

  const api = useMemo(
    () => ({
      me: () => request<ProMe>("/pro/me"),
      register: (body: { fullName: string; credentialType: ProCredentialType; consultationMode: ProConsultationMode }) =>
        request<ProMe>("/pro/register", { method: "POST", body: JSON.stringify(body) }),
      update: (body: ProUpdate) => request<ProMe>("/pro/me", { method: "PUT", body: JSON.stringify(body) }),
      uploadDocument: (docType: ProDocumentType, file: File) => {
        const form = new FormData();
        form.append("docType", docType);
        form.append("file", file);
        return request<ProDocument>("/pro/me/documents", { method: "POST", body: form });
      },
      deleteDocument: (docId: string) =>
        request<void>(`/pro/me/documents/${encodeURIComponent(docId)}`, { method: "DELETE" }),
      submit: () =>
        request<ProMe>("/pro/me/submit", { method: "POST", body: JSON.stringify({ declarationAccepted: true }) }),
      // Booking requests: always the signed-in professional's own (the server takes them from the token).
      bookings: (status: string, page: number, size: number) => {
        const params = new URLSearchParams({ page: String(page), size: String(size) });
        if (status) params.set("status", status);
        return request<ProBookingPage>(`/pro/bookings?${params}`).then(normalizeProBookingPage);
      },
      bookingSummary: () => request<ProBookingSummary>("/pro/bookings/summary"),
      booking: (id: string) => request<ProBooking>(`/pro/bookings/${encodeURIComponent(id)}`).then(normalizeProBooking),
      confirmBooking: (id: string, body: ConfirmBookingBody) =>
        request<ProBooking>(`/pro/bookings/${encodeURIComponent(id)}/confirm`, { method: "POST", body: JSON.stringify(body) })
          .then(normalizeProBooking),
      declineBooking: (id: string, body: DeclineBookingBody) =>
        request<ProBooking>(`/pro/bookings/${encodeURIComponent(id)}/decline`, { method: "POST", body: JSON.stringify(body) })
          .then(normalizeProBooking),
      proposeBooking: (id: string, body: ProposeBookingBody) =>
        request<ProBooking>(`/pro/bookings/${encodeURIComponent(id)}/propose`, { method: "POST", body: JSON.stringify(body) })
          .then(normalizeProBooking),
      // Home summary, confirmed appointments and days off.
      dashboard: () => request<ProDashboard>("/pro/dashboard").then(normalizeProDashboard),
      appointments: (when: "upcoming" | "past", page: number, size: number) =>
        request<ProAppointmentPage>(`/pro/appointments?${new URLSearchParams({ when, page: String(page), size: String(size) })}`),
      daysOff: () => request<ProDayOff[]>("/pro/days-off"),
      addDayOff: (body: ProDayOffRequest) =>
        request<ProDayOffCreated>("/pro/days-off", { method: "POST", body: JSON.stringify(body) }).then(normalizeDayOffCreated),
      deleteDayOff: (id: string) => request<void>(`/pro/days-off/${encodeURIComponent(id)}`, { method: "DELETE" }),
      // The signed-in professional's own paid sessions and totals (the server takes them from the token).
      earnings: async (page: number, size: number) =>
        normalizeProEarnings(
          await request<ProEarnings>(`/pro/earnings?${new URLSearchParams({ page: String(page), size: String(size) })}`),
        ),
      // The professional's own visible reviews and average, read-only (who wrote them is never sent).
      reviews: (page: number, size: number) =>
        request<ProReviews>(`/pro/me/reviews?${new URLSearchParams({ page: String(page), size: String(size) })}`),
    }),
    [request],
  );

  return { api, disabled };
}

/** GET /api/pro/me/reviews. */
export interface ProReviews {
  ratingAvg: number | null;
  ratingCount: number;
  reviews: ReviewPage;
}

// ---- Upload checks done in the browser before sending (the server checks again) ----

export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
export const MAX_DOCUMENTS = 10;
export const ACCEPTED_UPLOAD_TYPES = ".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png";

/** Returns an error sentence, or null when the file looks acceptable. Checks the real leading bytes. */
export async function checkUploadFile(file: File): Promise<string | null> {
  if (file.size === 0) return "This file is empty. Please choose another file.";
  if (file.size > MAX_UPLOAD_BYTES) return "This file is larger than 5 MB. Please choose a smaller file.";
  const extension = file.name.includes(".") ? file.name.split(".").pop()!.toLowerCase() : "";
  if (!["pdf", "jpg", "jpeg", "png"].includes(extension)) {
    return "Only PDF, JPG and PNG files can be uploaded.";
  }
  const head = new Uint8Array(await file.slice(0, 8).arrayBuffer());
  const startsWith = (bytes: number[]) => bytes.every((b, i) => head[i] === b);
  const isPdf = startsWith([0x25, 0x50, 0x44, 0x46, 0x2d]);
  const isPng = startsWith([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const isJpeg = startsWith([0xff, 0xd8, 0xff]);
  const matches = extension === "pdf" ? isPdf : extension === "png" ? isPng : isJpeg;
  if (!matches) return "This file's contents do not match its type. Please upload a real PDF, JPG or PNG file.";
  return null;
}

export function formatFileSize(bytes: number | null): string {
  if (bytes === null || bytes === undefined) return "Size unknown";
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
