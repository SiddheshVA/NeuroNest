import { API_BASE } from "./api";

/**
 * Client for the public professional directory (GET /api/professionals, /{id}, /meta).
 * Field names and enum values mirror the backend's PublicProfessionalDtos. These endpoints are
 * public, so no token or cookie is sent.
 */

export const CREDENTIAL_TYPES = ["PSYCHOLOGIST", "PSYCHIATRIST", "COUNSELOR"] as const;
export type CredentialType = (typeof CREDENTIAL_TYPES)[number];

/** Search options. A listing offering BOTH matches either. */
export const SEARCH_MODES = ["ONLINE", "IN_PERSON"] as const;
export type SearchMode = (typeof SEARCH_MODES)[number];
export type ConsultationMode = SearchMode | "BOTH";

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

export interface PublicProfessional {
  id: string;
  fullName: string;
  credentialType: CredentialType;
  qualifications: string | null;
  bio: string | null;
  specialties: string[];
  languages: string[];
  city: string | null;
  state: string | null;
  consultationMode: ConsultationMode;
  feeMin: number | null;
  feeMax: number | null;
  yearsExperience: number | null;
  availabilityNote: string | null;
  photoUrl: string | null;
  registrationBody: string | null;
  isSampleData: boolean;
  credentialsLabel: string;
}

export interface ProfessionalPage {
  items: PublicProfessional[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ProfessionalMeta {
  cities: string[];
  languages: string[];
}

export interface ProfessionalFilters {
  q?: string;
  credentialType?: CredentialType | "";
  city?: string;
  language?: string;
  mode?: SearchMode | "";
  page?: number;
  size?: number;
}

export const DIRECTORY_PAGE_SIZE = 12;

export class ProfessionalsApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, { signal, credentials: "omit" });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ProfessionalsApiError(0, "We could not reach NeuroNest. Please check your connection and try again.");
  }
  if (!response.ok) {
    let message = "Something went wrong. Please try again.";
    try {
      const body = await response.json();
      if (body && typeof body.error === "string") message = body.error;
    } catch {
      // Not JSON; keep the generic message.
    }
    throw new ProfessionalsApiError(response.status, message);
  }
  return (await response.json()) as T;
}

export function searchProfessionals(filters: ProfessionalFilters, signal?: AbortSignal) {
  const params = new URLSearchParams();
  if (filters.q?.trim()) params.set("q", filters.q.trim());
  if (filters.credentialType) params.set("credentialType", filters.credentialType);
  if (filters.city) params.set("city", filters.city);
  if (filters.language) params.set("language", filters.language);
  if (filters.mode) params.set("mode", filters.mode);
  params.set("page", String(filters.page ?? 0));
  params.set("size", String(filters.size ?? DIRECTORY_PAGE_SIZE));
  return getJson<ProfessionalPage>(`/professionals?${params}`, signal);
}

export function getPublicProfessional(id: string, signal?: AbortSignal) {
  return getJson<PublicProfessional>(`/professionals/${encodeURIComponent(id)}`, signal);
}

export function getProfessionalMeta(signal?: AbortSignal) {
  return getJson<ProfessionalMeta>("/professionals/meta", signal);
}

/** "₹500 to ₹1,500", "From ₹500", "Up to ₹1,500", or null when no fee is listed. */
export function formatFeeRange(min: number | null, max: number | null): string | null {
  const rupees = (value: number) => `₹${value.toLocaleString("en-IN")}`;
  if (min !== null && max !== null) return min === max ? rupees(min) : `${rupees(min)} to ${rupees(max)}`;
  if (min !== null) return `From ${rupees(min)}`;
  if (max !== null) return `Up to ${rupees(max)}`;
  return null;
}

export function formatExperience(years: number | null): string | null {
  if (years === null || years === undefined) return null;
  if (years === 0) return "Less than a year of experience";
  return `${years} ${years === 1 ? "year" : "years"} of experience`;
}

export function formatLocation(city: string | null, state: string | null): string | null {
  const parts = [city?.trim(), state?.trim()].filter(Boolean);
  return parts.length > 0 ? parts.join(", ") : null;
}

/** The directory link offered from crisis screens: opens the directory with helplines on top. */
export const URGENT_DIRECTORY_HREF = "/professionals?urgent=1";
