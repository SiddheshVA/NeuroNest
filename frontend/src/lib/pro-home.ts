import type { MeetingMethod, PreferredMode } from "./bookings-client";

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
  | "DAY_OFF_CONFLICTS";

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
  upcomingConfirmed: number;
  nextAppointment: { id: string; contactName: string; scheduledTime: string; meetingMethod: MeetingMethod | null } | null;
  /** YYYY-MM-DD. */
  registrationExpiry: string | null;
  registrationDaysLeft: number | null;
  hasWeeklyHours: boolean;
  daysOffCount: number;
  warnings: ProWarning[];
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
  affectedPending: number;
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
