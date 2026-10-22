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
