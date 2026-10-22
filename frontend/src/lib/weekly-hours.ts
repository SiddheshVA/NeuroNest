/**
 * Weekly hours shared by the professional profile, the public directory and the admin pages. The format
 * mirrors the backend's WeeklyHours: {"MON": [{"start": "09:00", "end": "12:00"}], ...} in 24-hour
 * Indian Standard Time, on 15-minute marks, with "24:00" allowed as an end. Days without ranges are left out.
 */

export const WEEK_DAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"] as const;
export type WeekDay = (typeof WEEK_DAYS)[number];

export const WEEK_DAY_LABELS: Record<WeekDay, string> = {
  MON: "Monday",
  TUE: "Tuesday",
  WED: "Wednesday",
  THU: "Thursday",
  FRI: "Friday",
  SAT: "Saturday",
  SUN: "Sunday",
};

export const WEEK_DAY_SHORT: Record<WeekDay, string> = {
  MON: "Mon",
  TUE: "Tue",
  WED: "Wed",
  THU: "Thu",
  FRI: "Fri",
  SAT: "Sat",
  SUN: "Sun",
};

export interface TimeRange {
  start: string;
  end: string;
}

export type WeeklyHours = Partial<Record<WeekDay, TimeRange[]>>;

// Mirror WeeklyHours.SESSION_MINUTES, BUFFER_MINUTES and MAX_RANGES_PER_DAY on the backend.
export const SESSION_MINUTE_OPTIONS = [30, 45, 60] as const;
export const BUFFER_MINUTE_OPTIONS = [0, 5, 10, 15] as const;
export const MAX_RANGES_PER_DAY = 3;

const END_OF_DAY = 24 * 60;

function pad(n: number) {
  return String(n).padStart(2, "0");
}

function hhmm(minutes: number) {
  return `${pad(Math.floor(minutes / 60))}:${pad(minutes % 60)}`;
}

/** Minutes from midnight; "24:00" is 1440. NaN when the text is not HH:mm. */
export function toMinutes(time: string): number {
  const match = /^(\d{2}):(\d{2})$/.exec(time);
  if (!match) return Number.NaN;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours === 24 && minutes === 0) return END_OF_DAY;
  if (hours > 23 || minutes > 59) return Number.NaN;
  return hours * 60 + minutes;
}

/** "18:00" becomes "6:00 pm"; "00:00" is "12:00 am", "12:00" is "12:00 pm" and "24:00" is "12:00 midnight". */
export function formatTime(time: string): string {
  const minutes = toMinutes(time);
  if (Number.isNaN(minutes)) return time;
  if (minutes === END_OF_DAY) return "12:00 midnight";
  const hours = Math.floor(minutes / 60);
  const hour12 = hours % 12 === 0 ? 12 : hours % 12;
  return `${hour12}:${pad(minutes % 60)} ${hours < 12 ? "am" : "pm"}`;
}

export function formatRange(range: TimeRange): string {
  return `${formatTime(range.start)} to ${formatTime(range.end)}`;
}

/** Every 15 minutes from 00:00 to 23:45 (a range cannot start at midnight at the end of the day). */
export const START_TIME_OPTIONS: string[] = Array.from({ length: 96 }, (_, i) => hhmm(i * 15));
/** Every 15 minutes from 00:15 to 24:00 (12:00 midnight at the end of the day). */
export const END_TIME_OPTIONS: string[] = Array.from({ length: 96 }, (_, i) => hhmm((i + 1) * 15));

/** The saved hours in day order, with each day's ranges sorted and empty days left out. */
export function orderedDays(hours: WeeklyHours | null | undefined): { day: WeekDay; ranges: TimeRange[] }[] {
  if (!hours) return [];
  return WEEK_DAYS.flatMap((day) => {
    const ranges = (hours[day] ?? []).filter((r) => r && r.start && r.end);
    if (ranges.length === 0) return [];
    return [{ day, ranges: [...ranges].sort((a, b) => toMinutes(a.start) - toMinutes(b.start)) }];
  });
}

export function hasAnyRange(hours: WeeklyHours | null | undefined): boolean {
  return orderedDays(hours).length > 0;
}

/**
 * The days with hours as a short phrase, grouping consecutive days: "Mon to Fri", "Mon, Wed and Fri",
 * "Mon to Wed and Sat". Two consecutive days are listed separately ("Sat and Sun"). Null when no days.
 */
export function summarizeDays(hours: WeeklyHours | null | undefined): string | null {
  const indexes = orderedDays(hours).map(({ day }) => WEEK_DAYS.indexOf(day));
  if (indexes.length === 0) return null;
  const parts: string[] = [];
  let start = indexes[0];
  let previous = indexes[0];
  const flush = () => {
    if (previous - start >= 2) parts.push(`${WEEK_DAY_SHORT[WEEK_DAYS[start]]} to ${WEEK_DAY_SHORT[WEEK_DAYS[previous]]}`);
    else for (let i = start; i <= previous; i++) parts.push(WEEK_DAY_SHORT[WEEK_DAYS[i]]);
  };
  for (const index of indexes.slice(1)) {
    if (index === previous + 1) {
      previous = index;
      continue;
    }
    flush();
    start = index;
    previous = index;
  }
  flush();
  return parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(", ")} and ${parts[parts.length - 1]}`;
}

// ---- Editing ----

/** One day in the editor. Times are "" until chosen. */
export interface DayDraft {
  enabled: boolean;
  ranges: TimeRange[];
}
export type WeeklyDraft = Record<WeekDay, DayDraft>;

export function emptyDraft(): WeeklyDraft {
  return Object.fromEntries(WEEK_DAYS.map((day) => [day, { enabled: false, ranges: [] }])) as unknown as WeeklyDraft;
}

export function draftFromHours(hours: WeeklyHours | null | undefined): WeeklyDraft {
  const draft = emptyDraft();
  for (const { day, ranges } of orderedDays(hours)) {
    draft[day] = { enabled: true, ranges: ranges.map((r) => ({ start: r.start, end: r.end })) };
  }
  return draft;
}

/** Ticked days with their ranges, as the backend stores them. Run validateDraft first. */
export function hoursFromDraft(draft: WeeklyDraft): WeeklyHours {
  const hours: WeeklyHours = {};
  for (const day of WEEK_DAYS) {
    const { enabled, ranges } = draft[day];
    const complete = ranges.filter((r) => r.start && r.end);
    if (enabled && complete.length > 0) {
      hours[day] = [...complete].sort((a, b) => toMinutes(a.start) - toMinutes(b.start)).map((r) => ({ ...r }));
    }
  }
  return hours;
}

export function draftHasAnyRange(draft: WeeklyDraft): boolean {
  return hasAnyRange(hoursFromDraft(draft));
}

/**
 * The rules the backend applies on save, one sentence per day that breaks them. A ticked day needs at
 * least one complete range; ranges must start before they end, be at least one session long (when the
 * session length is chosen) and must not overlap or touch.
 */
export function validateDraft(draft: WeeklyDraft, sessionMinutes: number | null): Partial<Record<WeekDay, string>> {
  const errors: Partial<Record<WeekDay, string>> = {};
  for (const day of WEEK_DAYS) {
    const { enabled, ranges } = draft[day];
    if (!enabled) continue;
    const label = WEEK_DAY_LABELS[day];
    if (ranges.length === 0) {
      errors[day] = `Add at least one time range for ${label}, or untick ${label}.`;
      continue;
    }
    if (ranges.length > MAX_RANGES_PER_DAY) {
      errors[day] = `${label} can have at most ${MAX_RANGES_PER_DAY} time ranges.`;
      continue;
    }
    if (ranges.some((r) => !r.start || !r.end)) {
      errors[day] = `Choose a start and an end time for every time range on ${label}, or remove the empty range.`;
      continue;
    }
    const sorted = [...ranges].sort((a, b) => toMinutes(a.start) - toMinutes(b.start));
    const wrongOrder = sorted.find((r) => toMinutes(r.start) >= toMinutes(r.end));
    if (wrongOrder) {
      errors[day] = `On ${label}, the time range ${formatRange(wrongOrder)} must start before it ends.`;
      continue;
    }
    const tooShort = sessionMinutes
      ? sorted.find((r) => toMinutes(r.end) - toMinutes(r.start) < sessionMinutes)
      : undefined;
    if (tooShort) {
      errors[day] = `On ${label}, the time range ${formatRange(tooShort)} is shorter than one ${sessionMinutes}-minute session.`;
      continue;
    }
    for (let i = 1; i < sorted.length; i++) {
      if (toMinutes(sorted[i].start) <= toMinutes(sorted[i - 1].end)) {
        errors[day] = `On ${label}, the time ranges ${formatRange(sorted[i - 1])} and ${formatRange(sorted[i])} overlap or touch. Leave a gap between them.`;
        break;
      }
    }
  }
  return errors;
}
