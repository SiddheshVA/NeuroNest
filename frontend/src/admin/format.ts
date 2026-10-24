// Display helpers for the admin pages.

import type { AdminBookingItem, BookingStatus } from "./types";

// "2030-03-31" (a date with no time) shown in the admin's locale without a timezone shift.
export function formatDate(isoDate: string | null | undefined): string | null {
  if (!isoDate) return null;
  const [year, month, day] = isoDate.split("-").map(Number);
  if (!year || !month || !day) return isoDate;
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

export function formatDateTime(isoInstant: string | null | undefined): string | null {
  if (!isoInstant) return null;
  const date = new Date(isoInstant);
  return Number.isNaN(date.getTime())
    ? isoInstant
    : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export function isPastDate(isoDate: string | null | undefined): boolean {
  if (!isoDate) return false;
  const today = new Date();
  const todayIso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  return isoDate < todayIso;
}

// Today's date in India as YYYY-MM-DD. The backend checks registration and re-check dates in Asia/Kolkata.
export function todayInIndia(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata" }).format(new Date());
}

export function tomorrowInIndia(): string {
  const [y, m, d] = todayInIndia().split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
}

// Age in whole years on today's date in India.
export function ageFromDateOfBirth(isoDate: string | null | undefined): number | null {
  if (!isoDate) return null;
  const [by, bm, bd] = isoDate.split("-").map(Number);
  const [ty, tm, td] = todayInIndia().split("-").map(Number);
  if (!by || !bm || !bd) return null;
  return ty - by - (tm < bm || (tm === bm && td < bd) ? 1 : 0);
}

// "3 days and 4 hours", "5 hours" or "Less than an hour" since an instant.
export function waitingTime(isoInstant: string | null | undefined, now = Date.now()): string | null {
  if (!isoInstant) return null;
  const start = Date.parse(isoInstant);
  if (Number.isNaN(start)) return null;
  const hours = Math.max(0, Math.floor((now - start) / 3_600_000));
  if (hours < 1) return "Less than an hour";
  const days = Math.floor(hours / 24);
  const rest = hours % 24;
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
  if (days === 0) return plural(hours, "hour");
  return rest === 0 ? plural(days, "day") : `${plural(days, "day")} and ${plural(rest, "hour")}`;
}

export function hoursSince(isoInstant: string | null | undefined, now = Date.now()): number | null {
  if (!isoInstant) return null;
  const start = Date.parse(isoInstant);
  return Number.isNaN(start) ? null : (now - start) / 3_600_000;
}

export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return "Size unknown";
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// ---- Indian Standard Time, 12-hour (booking pages) ----

const istDateTime = new Intl.DateTimeFormat("en-IN", {
  timeZone: "Asia/Kolkata",
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "numeric",
  minute: "2-digit",
  hour12: true,
});

const istDate = new Intl.DateTimeFormat("en-IN", {
  timeZone: "Asia/Kolkata",
  day: "numeric",
  month: "short",
  year: "numeric",
});

/** "14 Oct 2026, 6:00 pm IST", whatever the browser's own time zone is. */
export function formatIstDateTime(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : `${istDateTime.format(date)} IST`;
}

/** "14 Oct 2026" in India. */
export function formatIstDate(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : istDate.format(date);
}

/** "3 days 4 hours", "5 hours" or "Less than an hour" for a whole number of hours. */
export function hoursAsText(hours: number): string {
  if (hours < 1) return "Less than an hour";
  const days = Math.floor(hours / 24);
  const rest = hours % 24;
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
  if (days === 0) return plural(hours, "hour");
  return rest === 0 ? plural(days, "day") : `${plural(days, "day")} ${plural(rest, "hour")}`;
}

/** A median such as 5.5 hours: "5.5 hours". */
export function medianHoursText(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) return "No replies yet.";
  return `${hours.toLocaleString("en-IN", { maximumFractionDigits: 1 })} ${hours === 1 ? "hour" : "hours"}`;
}

/**
 * The status to show. A PENDING or ALTERNATE_PROPOSED request whose expiresAt has passed, or an
 * AWAITING_PAYMENT booking whose paymentDueAt has passed, is shown as EXPIRED (and has no actions),
 * because the server only changes the stored status later.
 */
export function displayStatus(
  row: Pick<AdminBookingItem, "status" | "expiresAt"> & { paymentDueAt?: string | null },
  now: number,
): BookingStatus {
  if ((row.status === "PENDING" || row.status === "ALTERNATE_PROPOSED") && row.expiresAt && Date.parse(row.expiresAt) <= now) {
    return "EXPIRED";
  }
  if (row.status === "AWAITING_PAYMENT" && row.paymentDueAt && Date.parse(row.paymentDueAt) <= now) {
    return "EXPIRED";
  }
  return row.status;
}

/**
 * Expired because the person did not pay: the server only sends paymentDueAt for an expired booking when
 * a time had been agreed. `shown` is the status shown (see displayStatus).
 */
export function isUnpaidExpiry(row: Pick<AdminBookingItem, "status" | "paymentDueAt">, shown: BookingStatus): boolean {
  return shown === "EXPIRED" && (row.status === "AWAITING_PAYMENT" || row.paymentDueAt != null);
}
