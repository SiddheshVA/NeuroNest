import { apiFetch } from "./api";

/**
 * Client for a signed-in user's booking requests (POST /api/bookings, GET /api/bookings/mine,
 * GET /api/bookings/{id}, POST /api/bookings/{id}/cancel). Field names mirror the backend's BookingDtos.
 * apiFetch attaches a fresh Clerk token to every call. Request bodies are never logged.
 */

export type BookingStatus = "PENDING" | "CONFIRMED" | "DECLINED" | "ALTERNATE_PROPOSED" | "CANCELLED" | "EXPIRED";
export type PreferredMode = "ONLINE" | "IN_PERSON";
export type PreferredMethod = "PHONE" | "VIDEO";
export type MeetingMethod = "PHONE" | "VIDEO" | "IN_PERSON";

export interface SharedAssessment {
  instrument: string;
  instrumentLabel: string;
  score: number;
  severityBand: string;
  /** ISO-8601 in UTC. */
  completedAt: string;
}

export interface BookingView {
  id: string;
  status: BookingStatus;
  createdAt: string;
  expiresAt: string | null;
  preferredMode: PreferredMode | null;
  preferredMethod: PreferredMethod | null;
  /** ISO date-times with the +05:30 offset. */
  preferredDatetime: string | null;
  alternateDatetime: string | null;
  proposedDatetime: string | null;
  professionalNote: string | null;
  respondedAt: string | null;
  /** The meeting details are only sent once the request is CONFIRMED. */
  meetingMethod: MeetingMethod | null;
  meetingLink: string | null;
  meetingAddress: string | null;
  sharedAssessment: SharedAssessment | null;
  assessmentSharedAt: string | null;
  professional: { id: string; fullName: string; credentialType: string; city: string | null } | null;
}

export interface BookingPage {
  items: BookingView[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CreateBookingRequest {
  professionalId: string;
  preferredMode: PreferredMode;
  preferredMethod?: PreferredMethod;
  contactName: string;
  contactPhone: string;
  slotStart: string;
  alternateSlotStart?: string;
  note?: string;
  consentGiven: true;
  shareLatestAssessment?: boolean;
}

export class BookingApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** The server's message for a 409 when the chosen time was taken in the meantime. */
export const SLOT_TAKEN_MESSAGE = "This time is no longer available. Please choose another time.";

export function isSlotTaken(error: unknown): boolean {
  return error instanceof BookingApiError && error.status === 409 && error.message.includes("no longer available");
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await apiFetch(path, init);
  } catch {
    throw new BookingApiError(0, "We could not reach NeuroNest. Please check your connection and try again.");
  }
  if (!response.ok) {
    let message = "Something went wrong. Please try again.";
    try {
      const body = await response.json();
      if (body && typeof body.error === "string") message = body.error;
    } catch {
      // Not JSON; keep the generic message.
    }
    throw new BookingApiError(response.status, message);
  }
  return (await response.json()) as T;
}

export function createBooking(body: CreateBookingRequest) {
  return request<BookingView>("/bookings", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

export function getMyBookings(page: number, size: number) {
  return request<BookingPage>(`/bookings/mine?page=${page}&size=${size}`);
}

export function cancelBooking(id: string) {
  return request<BookingView>(`/bookings/${encodeURIComponent(id)}/cancel`, { method: "POST" });
}

/** GET /api/bookings/summary: the signed-in user's next confirmed appointment and open requests. */
export interface BookingSummary {
  nextAppointment: {
    id: string;
    /** ISO date-time with the +05:30 offset. */
    scheduledTime: string;
    preferredMode: PreferredMode | null;
    meetingMethod: MeetingMethod | null;
    meetingLink: string | null;
    meetingAddress: string | null;
    professional: { id: string; fullName: string; credentialType: string; city: string | null } | null;
  } | null;
  /** PENDING requests that have not expired. */
  waitingForProfessional: number;
  /** ALTERNATE_PROPOSED requests that have not expired. */
  needsYourAnswer: number;
}

export function getBookingSummary() {
  return request<BookingSummary>("/bookings/summary");
}

/** Accepts the time the professional suggested (owner only, from ALTERNATE_PROPOSED). */
export function acceptProposal(id: string) {
  return request<BookingView>(`/bookings/${encodeURIComponent(id)}/accept-proposal`, { method: "POST" });
}

/**
 * The status to show. A PENDING or ALTERNATE_PROPOSED request whose expiresAt has passed is shown as
 * EXPIRED, with no actions, because the server only changes the stored status later.
 */
export function displayStatus(b: { status: BookingStatus; expiresAt: string | null }, now: number): BookingStatus {
  if ((b.status === "PENDING" || b.status === "ALTERNATE_PROPOSED") && b.expiresAt && Date.parse(b.expiresAt) <= now) {
    return "EXPIRED";
  }
  return b.status;
}

// ---- The user's latest assessment result, for the "share" preview ----

export interface LatestAssessment {
  instrument: string;
  score: number;
  severityBand: string;
  createdAt: string;
}

/**
 * The newest result from the dashboard's assessment history (GET /api/me/dashboard, newest first). It
 * uses the same link the booking uses when it stores the copy: results saved under the user's own
 * sessions. Null when there is none.
 */
export async function getLatestAssessment(): Promise<LatestAssessment | null> {
  const dashboard = await request<{ assessments?: LatestAssessment[] }>("/me/dashboard");
  return dashboard.assessments && dashboard.assessments.length > 0 ? dashboard.assessments[0] : null;
}

/** The same test names the backend stores in instrumentLabel. */
export const INSTRUMENT_LABELS: Record<string, string> = {
  WHO5: "WHO-5",
  PHQ9: "PHQ-9",
  GAD7: "GAD-7",
  PSS10: "PSS-10",
  K10: "K10",
};

// ---- Phone and display helpers ----

/** A 10-digit Indian mobile number starting 6 to 9, optionally written with +91 or a leading 0. */
export function isIndianMobile(value: string): boolean {
  return /^(?:\+91|0)?[6-9]\d{9}$/.test(value.replace(/[\s()-]/g, ""));
}

export const STATUS_LABELS: Record<BookingStatus, string> = {
  PENDING: "Waiting for a reply",
  CONFIRMED: "Confirmed",
  DECLINED: "Declined",
  ALTERNATE_PROPOSED: "Another time suggested",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
};

export const METHOD_LABELS: Record<MeetingMethod, string> = {
  PHONE: "Phone call",
  VIDEO: "Video call",
  IN_PERSON: "In person",
};

/** How the user asked to meet: "Phone call", "Video call" or "In person". */
export function requestedMethodLabel(b: Pick<BookingView, "preferredMode" | "preferredMethod">): string | null {
  if (b.preferredMode === "IN_PERSON") return METHOD_LABELS.IN_PERSON;
  if (b.preferredMethod) return METHOD_LABELS[b.preferredMethod];
  return b.preferredMode === "ONLINE" ? "Online" : null;
}
