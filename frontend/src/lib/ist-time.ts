/**
 * Dates and times shown to users are always in Indian Standard Time, in 12-hour format, whatever the
 * device's own time zone is.
 */

export const IST_ZONE = "Asia/Kolkata";

const timeFormat = new Intl.DateTimeFormat("en-IN", {
  timeZone: IST_ZONE,
  hour: "numeric",
  minute: "2-digit",
  hour12: true,
});

const dayFormat = new Intl.DateTimeFormat("en-IN", {
  timeZone: IST_ZONE,
  weekday: "short",
  day: "numeric",
  month: "short",
});

const dateFormat = new Intl.DateTimeFormat("en-IN", {
  timeZone: IST_ZONE,
  day: "numeric",
  month: "short",
  year: "numeric",
});

const isoDateFormat = new Intl.DateTimeFormat("en-CA", {
  timeZone: IST_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

function parse(value: string): Date | null {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** "6:00 pm". */
export function formatIstTime(value: string): string {
  const date = parse(value);
  return date ? timeFormat.format(date) : value;
}

/** "Wed, 14 Oct, 6:00 pm IST". */
export function formatIstDateTime(value: string): string {
  const date = parse(value);
  return date ? `${dayFormat.format(date)}, ${timeFormat.format(date)} IST` : value;
}

/** "2 hours", "1 hour 30 minutes", "45 minutes". */
export function formatMeetingLength(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  const h = hours === 0 ? "" : `${hours} ${hours === 1 ? "hour" : "hours"}`;
  const m = rest === 0 ? "" : `${rest} ${rest === 1 ? "minute" : "minutes"}`;
  return [h, m].filter(Boolean).join(" ");
}

/**
 * "Thu, 29 Oct, 7:45 pm to 9:45 pm IST (2 sessions, 2 hours)": the whole meeting, never the break after
 * it. Without an end (an older booking), the start alone, as formatIstDateTime shows it.
 */
export function formatMeetingTime(start: string, end?: string | null, sessions?: number | null): string {
  const from = parse(start);
  const to = end ? parse(end) : null;
  if (!from || !to || to.getTime() <= from.getTime()) return formatIstDateTime(start);
  const count = Math.max(1, sessions ?? 1);
  const minutes = Math.round((to.getTime() - from.getTime()) / 60_000);
  return (
    `${dayFormat.format(from)}, ${timeFormat.format(from)} to ${timeFormat.format(to)} IST ` +
    `(${count} ${count === 1 ? "session" : "sessions"}, ${formatMeetingLength(minutes)})`
  );
}

/** "12 Oct 2026". */
export function formatIstDate(value: string): string {
  const date = parse(value);
  return date ? dateFormat.format(date) : value;
}

/** "Wed, 14 Oct" for a calendar date written as YYYY-MM-DD. */
export function formatIstDay(isoDate: string): string {
  // Noon UTC is the same calendar day in India.
  const date = parse(`${isoDate}T12:00:00Z`);
  return date ? dayFormat.format(date) : isoDate;
}

const RELATIVE_UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86400],
  ["hour", 3600],
  ["minute", 60],
];
const relativeFormat = new Intl.RelativeTimeFormat("en-IN", { numeric: "always" });

/** "3 hours ago" or "in 2 days", rounded down so a deadline is never overstated. */
export function formatRelative(value: string, now: number): string {
  const date = parse(value);
  if (!date) return value;
  const seconds = (date.getTime() - now) / 1000;
  for (const [unit, size] of RELATIVE_UNITS) {
    const amount = Math.trunc(seconds / size);
    if (amount !== 0) return relativeFormat.format(amount, unit);
  }
  return seconds < 0 ? "less than a minute ago" : "in less than a minute";
}

/** Today's date in India as YYYY-MM-DD. */
export function todayInIndia(): string {
  return isoDateFormat.format(new Date());
}

/** A YYYY-MM-DD date plus a number of days. */
export function addDays(isoDate: string, days: number): string {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}
