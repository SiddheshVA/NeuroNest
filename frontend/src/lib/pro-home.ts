import type { MeetingMethod, PreferredMode } from "./bookings-client";
import { list, normalizeTotals, type PaymentTotals } from "./money";

/**
 * Shapes of the professional's home summary, appointments and days off (/api/pro/dashboard,
 * /api/pro/appointments, /api/pro/days-off). Field names mirror the backend's ProHomeDtos and
 * ProBookingDtos.ProAppointmentView. The calls live in useProApi (lib/pro-client).
 */

export type ProWarningCode =
  | "REGISTRATION_EXPIRING"
  | "REGISTRATION_EXPIRED"
  | "NO_WEEKLY_HOURS"
  | "NOT_LISTED"
  | "SUSPENDED"
  | "DAY_OFF_CONFLICTS"
  | "DAY_OFF_AWAITING_PAYMENT";

export interface ProWarning {
  code: ProWarningCode | string;
  /** Written on the server; shown as it is. */
  message: string;
}

export interface ProDashboard {
  verificationStatus: string;
  active: boolean;
  accountStatus: string;
  pendingRequests: number;
  awaitingUser: number;
  /** Paid (or free) appointments still to come. */
  upcomingConfirmed: number;
  /** Bookings accepted by the professional, waiting for the person's payment (deadline not passed). */
  awaitingPayment: number;
  /** Up to 20 of them, earliest deadline first. No contact details. */
  awaitingPaymentBookings: ProAwaitingPaymentItem[];
  nextAppointment: { id: string; contactName: string; scheduledTime: string; meetingMethod: MeetingMethod | null } | null;
  /** YYYY-MM-DD. */
  registrationExpiry: string | null;
  registrationDaysLeft: number | null;
  hasWeeklyHours: boolean;
  daysOffCount: number;
  warnings: ProWarning[];
}

export interface ProAwaitingPaymentItem {
  id: string;
  contactName: string;
  /** ISO date-time with the +05:30 offset. */
  scheduledTime: string | null;
  meetingMethod: MeetingMethod | null;
  /** ISO-8601 in UTC. */
  paymentDueAt: string | null;
}

/** Missing lists read as [] and missing counts as 0, so an older response never breaks the dashboard. */
export function normalizeProDashboard(d: ProDashboard): ProDashboard {
  return {
    ...d,
    awaitingPayment: d?.awaitingPayment ?? 0,
    awaitingPaymentBookings: list(d?.awaitingPaymentBookings),
    warnings: list(d?.warnings),
  };
}

export interface ProAppointment {
  id: string;
  contactName: string;
  preferredMode: PreferredMode | null;
  meetingMethod: MeetingMethod | null;
  meetingLink: string | null;
  meetingAddress: string | null;
  /** ISO date-time with the +05:30 offset. */
  scheduledTime: string;
  professionalNote: string | null;
  /** Only until 7 days after the appointment; null afterwards. */
  contactEmail: string | null;
  contactPhone: string | null;
}

export interface ProAppointmentPage {
  items: ProAppointment[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ProDayOff {
  id: string;
  /** YYYY-MM-DD, both included. */
  dateFrom: string;
  dateTo: string;
  /** Private to the professional. */
  reason: string | null;
  createdAt: string;
}

export interface ProDayOffCreated extends ProDayOff {
  affectedConfirmed: number;
  /** Bookings on these dates that were accepted and are waiting for the person's payment. */
  affectedAwaitingPayment: number;
  affectedPending: number;
}

export function normalizeDayOffCreated(d: ProDayOffCreated): ProDayOffCreated {
  return {
    ...d,
    affectedConfirmed: d?.affectedConfirmed ?? 0,
    affectedAwaitingPayment: d?.affectedAwaitingPayment ?? 0,
    affectedPending: d?.affectedPending ?? 0,
  };
}

export interface ProDayOffRequest {
  dateFrom: string;
  dateTo: string;
  reason?: string;
}

export const MAX_DAY_OFF_REASON = 100;
export const MAX_DAY_OFF_RANGE_DAYS = 60;
export const MAX_DAY_OFF_DAYS_AHEAD = 365;

/** Days from one YYYY-MM-DD date to another (0 for the same day). */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

// ---- Earnings (/api/pro/earnings, ProEarningsService on the server) ----

/** One paid, confirmed session. Amounts in paise, exactly as the server stored them. No user details. */
export interface ProEarningsItem {
  paymentId: string;
  bookingId: string;
  /** ISO date-time with the +05:30 offset. */
  sessionTime: string | null;
  /** ISO-8601 in UTC. */
  paidAt: string | null;
  amountPaise: number;
  commissionPaise: number;
  professionalSharePaise: number;
  currency: string;
}

export interface ProEarnings {
  /** The current month in India, YYYY-MM. */
  month: string;
  allTime: PaymentTotals;
  thisMonth: PaymentTotals;
  payments: { items: ProEarningsItem[]; page: number; size: number; totalElements: number; totalPages: number };
}

/** Every list defaults to [] and every total to 0, so an older or partial response never breaks the page. */
export function normalizeProEarnings(e: Partial<ProEarnings> | null | undefined): ProEarnings {
  const p = e?.payments;
  return {
    month: typeof e?.month === "string" ? e.month : "",
    allTime: normalizeTotals(e?.allTime),
    thisMonth: normalizeTotals(e?.thisMonth),
    payments: {
      items: list(p?.items),
      page: p?.page ?? 0,
      size: p?.size ?? 0,
      totalElements: p?.totalElements ?? 0,
      totalPages: p?.totalPages ?? 0,
    },
  };
}
