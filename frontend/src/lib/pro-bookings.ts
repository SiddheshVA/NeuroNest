import type {
  BookingStatus,
  MeetingMethod,
  PreferredMethod,
  PreferredMode,
  SharedAssessment,
} from "./bookings-client";

/**
 * Shapes and rules for a professional's booking requests (/api/pro/bookings). Field names mirror the
 * backend's ProBookingDtos. The calls themselves live in useProApi (lib/pro-client).
 */

export interface ProBooking {
  id: string;
  status: BookingStatus;
  createdAt: string;
  expiresAt: string | null;
  contactName: string;
  preferredMode: PreferredMode | null;
  preferredMethod: PreferredMethod | null;
  /** ISO date-times with the +05:30 offset. For a CONFIRMED booking, preferredDatetime is the appointment time. */
  preferredDatetime: string | null;
  /** How many sessions in a row, and when the meeting ends (never the break after it); null without a stored end. */
  sessionCount: number;
  endDatetime: string | null;
  alternateDatetime: string | null;
  proposedDatetime: string | null;
  note: string | null;
  professionalNote: string | null;
  sharedAssessment: SharedAssessment | null;
  assessmentSharedAt: string | null;
  meetingMethod: MeetingMethod | null;
  meetingLink: string | null;
  meetingAddress: string | null;
  respondedAt: string | null;
  /** Only for a CONFIRMED booking whose time is at most 7 days in the past; otherwise null. */
  contactEmail: string | null;
  contactPhone: string | null;
  /**
   * ISO-8601 in UTC. The person's deadline to pay while AWAITING_PAYMENT; on an EXPIRED booking it means
   * the payment window ended unpaid. Null otherwise, including an unanswered request that expired.
   */
  paymentDueAt: string | null;
}

export interface ProBookingPage {
  items: ProBooking[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ProBookingSummary {
  /** PENDING requests that have not expired. */
  pending: number;
  /** ALTERNATE_PROPOSED requests that have not expired. */
  awaitingUser: number;
}

export interface MeetingDetails {
  meetingMethod: MeetingMethod;
  meetingLink?: string;
  meetingAddress?: string;
  note?: string;
}

export type ConfirmBookingBody = MeetingDetails & { chosenSlot: "PREFERRED" | "ALTERNATE" };
export type ProposeBookingBody = MeetingDetails & { proposedSlot: string };
export interface DeclineBookingBody {
  note?: string;
}

/** Status names from the professional's point of view. */
export const PRO_STATUS_LABELS: Record<BookingStatus, string> = {
  PENDING: "Waiting for me",
  AWAITING_PAYMENT: "Waiting for payment",
  CONFIRMED: "Confirmed",
  ALTERNATE_PROPOSED: "Waiting for the person",
  DECLINED: "Declined",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
};

export const PRO_STATUS_FILTERS: { value: BookingStatus | ""; label: string }[] = [
  { value: "", label: "All" },
  { value: "PENDING", label: "Waiting for me" },
  { value: "CONFIRMED", label: "Confirmed" },
  { value: "ALTERNATE_PROPOSED", label: "Waiting for the person" },
  { value: "AWAITING_PAYMENT", label: "Waiting for payment" },
  { value: "DECLINED", label: "Declined" },
  { value: "CANCELLED", label: "Cancelled" },
  { value: "EXPIRED", label: "Expired" },
];

export const MAX_REPLY_NOTE = 500;
export const MAX_MEETING_LINK = 300;
export const MIN_MEETING_ADDRESS = 5;
export const MAX_MEETING_ADDRESS = 200;

/** The same checks as the server: https only, a real host, at most 300 characters. */
export function meetingLinkProblem(value: string): string | null {
  const link = value.trim();
  if (!link) return "Please paste the meeting link.";
  if (link.length > MAX_MEETING_LINK) return `The meeting link must be at most ${MAX_MEETING_LINK} characters.`;
  if (!link.startsWith("https://")) return "The meeting link must start with https://.";
  try {
    const url = new URL(link);
    if (/\s/.test(link) || url.protocol !== "https:" || !url.hostname.includes(".")) throw new Error("invalid");
  } catch {
    return "Please enter a valid web address, for example https://meet.example.com/your-room.";
  }
  return null;
}

export function meetingAddressProblem(value: string): string | null {
  const address = value.trim();
  if (address.length < MIN_MEETING_ADDRESS || address.length > MAX_MEETING_ADDRESS) {
    return `Please enter the clinic address in ${MIN_MEETING_ADDRESS} to ${MAX_MEETING_ADDRESS} characters.`;
  }
  return null;
}

// ---- Payments ----

/** How an unpaid booking that expired is described, apart from an unanswered request. */
export const UNPAID_EXPIRY_TEXT = "Expired: not paid in time, slot released";

/**
 * A booking the professional accepted that expired because the person did not pay: the server only sends
 * paymentDueAt for an expired booking when a time had been agreed. `status` is the status shown.
 */
export function isUnpaidExpiry(b: Pick<ProBooking, "status" | "paymentDueAt">, status: BookingStatus): boolean {
  return status === "EXPIRED" && (b.status === "AWAITING_PAYMENT" || b.paymentDueAt != null);
}

/** Agreed: the time is set (waiting for the person's payment, or paid). */
export function isAgreed(b: Pick<ProBooking, "status">): boolean {
  return b.status === "AWAITING_PAYMENT" || b.status === "CONFIRMED";
}

/** A missing newer field reads as null and a missing list as [], so an older response never breaks a page. */
export function normalizeProBooking(b: ProBooking): ProBooking {
  return { ...b, paymentDueAt: b?.paymentDueAt ?? null };
}

export function normalizeProBookingPage(p: ProBookingPage): ProBookingPage {
  return {
    items: (Array.isArray(p?.items) ? p.items : []).map(normalizeProBooking),
    page: p?.page ?? 0,
    size: p?.size ?? 0,
    totalElements: p?.totalElements ?? 0,
    totalPages: p?.totalPages ?? 0,
  };
}
