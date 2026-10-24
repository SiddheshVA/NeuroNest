import type { WeeklyHours } from "./weekly-hours";
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
  /** Rupees per session. */
  consultationFee: number | null;
  yearsExperience: number | null;
  availabilityNote: string | null;
  /** Days and times in Indian Standard Time (see lib/weekly-hours). */
  weeklyHours: WeeklyHours;
  sessionMinutes: number | null;
  photoUrl: string | null;
  registrationBody: string | null;
  isSampleData: boolean;
  credentialsLabel: string;
  /** True only when the listing can take booking requests. */
  bookable: boolean;
  /** Average of the visible reviews, or null when there are none yet (shown as "New"). */
  ratingAvg: number | null;
  ratingCount: number;
}

/** Directory sort options (the API's sort parameter). No sort keeps the default order, by name. */
export const DIRECTORY_SORTS = ["TOP_RATED", "MOST_REVIEWED", "LOWEST_FEE", "MOST_EXPERIENCED"] as const;
export type DirectorySort = (typeof DIRECTORY_SORTS)[number];

export const DIRECTORY_SORT_LABELS: Record<DirectorySort, string> = {
  TOP_RATED: "Top rated",
  MOST_REVIEWED: "Most reviewed",
  LOWEST_FEE: "Lowest fee",
  MOST_EXPERIENCED: "Most experienced",
};

/** One visible review, as GET /api/professionals/{id}/reviews returns it. month is "YYYY-MM". */
export interface PublicReview {
  rating: number;
  comment: string | null;
  month: string;
}

export interface ReviewPage {
  items: PublicReview[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export const REVIEWS_PAGE_SIZE = 5;

/** GET /api/professionals/{id}/availability. Slots are ISO date-times with the +05:30 offset. */
export interface ProfessionalAvailability {
  sessionMinutes: number | null;
  bufferMinutes: number;
  timezone: string;
  days: { date: string; slots: string[] }[];
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
  sort?: DirectorySort | "";
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
  if (filters.sort) params.set("sort", filters.sort);
  params.set("page", String(filters.page ?? 0));
  params.set("size", String(filters.size ?? DIRECTORY_PAGE_SIZE));
  return getJson<ProfessionalPage>(`/professionals?${params}`, signal);
}

export function getPublicProfessional(id: string, signal?: AbortSignal) {
  return getJson<PublicProfessional>(`/professionals/${encodeURIComponent(id)}`, signal);
}

/** Free slots between two dates (YYYY-MM-DD, India time, both included; at most 31 days apart). */
export function getProfessionalAvailability(id: string, from: string, to: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ from, to });
  return getJson<ProfessionalAvailability>(`/professionals/${encodeURIComponent(id)}/availability?${params}`, signal);
}

/** Visible reviews of a listed professional, newest first. Never says who wrote them. */
export function getProfessionalReviews(id: string, page: number, signal?: AbortSignal) {
  const params = new URLSearchParams({ page: String(page), size: String(REVIEWS_PAGE_SIZE) });
  return getJson<ReviewPage>(`/professionals/${encodeURIComponent(id)}/reviews?${params}`, signal);
}

/** "4.8" (one decimal). */
export function formatRating(avg: number): string {
  return avg.toFixed(1);
}

/** "October 2026" from "2026-10". */
export function formatReviewMonth(month: string): string {
  const [year, monthNumber] = month.split("-").map(Number);
  if (!year || !monthNumber) return month;
  return new Date(Date.UTC(year, monthNumber - 1, 1)).toLocaleDateString("en-IN", {
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  });
}

export function getProfessionalMeta(signal?: AbortSignal) {
  return getJson<ProfessionalMeta>("/professionals/meta", signal);
}

/** "₹1,000 per session", or null when no fee is listed (and then nothing is shown). */
export function formatFee(fee: number | null): string | null {
  return fee === null || fee === undefined ? null : `₹${fee.toLocaleString("en-IN")} per session`;
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
