import { apiFetch } from "./api";

/**
 * Client for a signed-in user's booking requests (POST /api/bookings, GET /api/bookings/mine,
 * GET /api/bookings/{id}, POST /api/bookings/{id}/cancel, and paying: POST /api/bookings/{id}/payment/order
 * and /payment/verify). Field names mirror the backend's BookingDtos.
 * apiFetch attaches a fresh Clerk token to every call. Request bodies are never logged.
 */

/** AWAITING_PAYMENT: the time is agreed and the user has until paymentDueAt to pay. CONFIRMED means paid (or free). */
export type BookingStatus =
  | "PENDING"
  | "AWAITING_PAYMENT"
  | "CONFIRMED"
  | "DECLINED"
  | "ALTERNATE_PROPOSED"
  | "CANCELLED"
  | "EXPIRED";
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
  /**
   * ISO-8601 in UTC. The deadline to pay while AWAITING_PAYMENT; on an EXPIRED booking it means the payment
   * window ended unpaid. Null otherwise.
   */
  paymentDueAt: string | null;
  /** While AWAITING_PAYMENT: what paying charges, in paise (the same amount the order uses). */
  amount: number | null;
  currency: string | null;
  /** A receipt: what was paid, in paise, for a CONFIRMED booking with a payment. Null otherwise. */
  paidAmount: number | null;
  paidCurrency: string | null;
  /** How many consecutive sessions, and when the last one ends (+05:30); null end for older bookings. */
  sessionCount: number;
  endDatetime: string | null;
  /** The professional's email and phone, only once the booking is CONFIRMED. */
  professionalEmail: string | null;
  professionalPhone: string | null;
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
  /** Consecutive sessions from slotStart, 1 to MAX_SESSIONS (default 1). */
  sessionCount?: number;
  note?: string;
  consentGiven: true;
  shareLatestAssessment?: boolean;
  /** "Send me updates on WhatsApp" to contactPhone. Stored now; used once WhatsApp updates are switched on. */
  whatsappUpdates?: boolean;
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

/** The most consecutive sessions one booking may cover (BookingService.MAX_SESSIONS). */
export const MAX_SESSIONS = 4;

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

// ---- Tolerating older or partial responses ----
// An older backend build sends no payment fields. Every list defaults to [] and every newer field to
// null, so a missing field never breaks a page.

function list<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : [];
}

function normalizeView(b: BookingView): BookingView {
  return {
    ...b,
    paymentDueAt: b.paymentDueAt ?? null,
    amount: b.amount ?? null,
    currency: b.currency ?? null,
    paidAmount: b.paidAmount ?? null,
    paidCurrency: b.paidCurrency ?? null,
  };
}

function normalizePage(p: BookingPage): BookingPage {
  return {
    ...p,
    items: list(p?.items).map(normalizeView),
    page: p?.page ?? 0,
    size: p?.size ?? 0,
    totalElements: p?.totalElements ?? 0,
    totalPages: p?.totalPages ?? 0,
  };
}

function normalizeSummary(s: BookingSummary): BookingSummary {
  return {
    nextAppointment: s?.nextAppointment ?? null,
    // Only entries the page can show: an id and a payment deadline.
    awaitingPayment: list(s?.awaitingPayment)
      .filter((a) => a && typeof a.id === "string" && typeof a.paymentDueAt === "string")
      .map((a) => ({ ...a, amount: a.amount ?? null, currency: a.currency ?? null })),
    waitingForProfessional: s?.waitingForProfessional ?? 0,
    needsYourAnswer: s?.needsYourAnswer ?? 0,
  };
}

export async function createBooking(body: CreateBookingRequest) {
  return normalizeView(
    await request<BookingView>("/bookings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

export async function getMyBookings(page: number, size: number) {
  return normalizePage(await request<BookingPage>(`/bookings/mine?page=${page}&size=${size}`));
}

export async function cancelBooking(id: string) {
  return normalizeView(await request<BookingView>(`/bookings/${encodeURIComponent(id)}/cancel`, { method: "POST" }));
}

/** An agreed booking the user still has to pay for. No meeting details until it is paid. */
export interface AwaitingPaymentBooking {
  id: string;
  /** ISO date-time with the +05:30 offset. */
  scheduledTime: string;
  /** ISO-8601 in UTC. */
  paymentDueAt: string;
  preferredMode: PreferredMode | null;
  professional: { id: string; fullName: string; credentialType: string; city: string | null } | null;
  /** What paying charges, in paise (the same amount the order uses); null if not known. */
  amount: number | null;
  currency: string | null;
  /** How many sessions in a row, and when the meeting ends (never the break after it); null without a stored end. */
  sessionCount: number;
  endDatetime: string | null;
}

/**
 * GET /api/bookings/summary: the signed-in user's next confirmed appointment, the bookings waiting for
 * their payment, and open requests.
 */
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
    sessionCount: number;
    endDatetime: string | null;
    professionalEmail: string | null;
    professionalPhone: string | null;
  } | null;
  /** AWAITING_PAYMENT bookings whose deadline has not passed, earliest deadline first. */
  awaitingPayment: AwaitingPaymentBooking[];
  /** PENDING requests that have not expired. */
  waitingForProfessional: number;
  /** ALTERNATE_PROPOSED requests that have not expired. */
  needsYourAnswer: number;
}

export async function getBookingSummary() {
  return normalizeSummary(await request<BookingSummary>("/bookings/summary"));
}

/** Accepts the time the professional suggested (owner only, from ALTERNATE_PROPOSED). */
export async function acceptProposal(id: string) {
  return normalizeView(
    await request<BookingView>(`/bookings/${encodeURIComponent(id)}/accept-proposal`, { method: "POST" }),
  );
}

// ---- Paying (the order amount always comes from the server, never from the page) ----

export interface PaymentOrder {
  razorpayOrderId: string;
  /** In paise. */
  amount: number;
  currency: string;
  /** Razorpay's public key id. Only ever taken from this response. */
  keyId: string;
}

export interface VerifyPaymentBody {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

/** Creates the Razorpay order, or returns the unpaid one already made for this booking. */
export async function createPaymentOrder(id: string) {
  const order = await request<PaymentOrder>(`/bookings/${encodeURIComponent(id)}/payment/order`, { method: "POST" });
  if (!order || !order.razorpayOrderId || !order.keyId || !(order.amount > 0) || !order.currency) {
    throw new BookingApiError(0, "The payment could not be started. Please try again later.");
  }
  return order;
}

/** Checks the payment and confirms the booking. Safe to call more than once. */
export async function verifyPayment(id: string, body: VerifyPaymentBody) {
  return normalizeView(
    await request<BookingView>(`/bookings/${encodeURIComponent(id)}/payment/verify`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }),
  );
}

/**
 * The status to show. A PENDING or ALTERNATE_PROPOSED request whose expiresAt has passed, or an
 * AWAITING_PAYMENT booking whose paymentDueAt has passed, is shown as EXPIRED, with no actions, because
 * the server only changes the stored status later.
 */
export function displayStatus(
  b: { status: BookingStatus; expiresAt: string | null; paymentDueAt?: string | null },
  now: number,
): BookingStatus {
  if ((b.status === "PENDING" || b.status === "ALTERNATE_PROPOSED") && b.expiresAt && Date.parse(b.expiresAt) <= now) {
    return "EXPIRED";
  }
  if (b.status === "AWAITING_PAYMENT" && b.paymentDueAt && Date.parse(b.paymentDueAt) <= now) {
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
  AWAITING_PAYMENT: "Awaiting payment",
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

export { formatAmount } from "./money";

/** How the user asked to meet: "Phone call", "Video call" or "In person". */
export function requestedMethodLabel(b: Pick<BookingView, "preferredMode" | "preferredMethod">): string | null {
  if (b.preferredMode === "IN_PERSON") return METHOD_LABELS.IN_PERSON;
  if (b.preferredMethod) return METHOD_LABELS[b.preferredMethod];
  return b.preferredMode === "ONLINE" ? "Online" : null;
}
