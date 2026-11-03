// What the results screen shows, worked out from the API response. Pure functions (no React), unit-tested.

import {
  ACTIONS,
  CONFIDENCE_REASON_FALLBACK,
  CONFIDENCE_REASONS,
  DRIVER_LABELS,
  DRIVER_VALUES,
  OPTIONAL,
  RESULT,
  YES_NO,
} from "./copy.ts";
import type { CheckinResponse, Driver, Guidance, MoodResult, RiskBand, SleepResult } from "./types.ts";

export const WELLNESS_ROUTES = {
  checkin: "/wellness-snapshot",
  history: "/wellness-snapshot/history",
} as const;

export const BANDS: RiskBand[] = ["Low", "Moderate", "High"];

/** Position on the calm three-step scale; -1 for an unknown band (nothing highlighted). */
export function bandIndex(band: string): number {
  return BANDS.indexOf(band as RiskBand);
}

export function bandCopy(band: string): { label: string; text: string } | null {
  return (RESULT.bands as Record<string, { label: string; text: string }>)[band] ?? null;
}

/** The API's confidence reasons in plain language, without duplicates; never the raw technical strings. */
export function friendlyConfidenceReasons(reasons: string[] | null | undefined): string[] {
  const out: string[] = [];
  for (const reason of reasons ?? []) {
    const match = CONFIDENCE_REASONS.find((r) => reason.startsWith(r.startsWith));
    const text = match ? match.text : CONFIDENCE_REASON_FALLBACK;
    if (!out.includes(text)) out.push(text);
  }
  return out.length > 0 ? out : [RESULT.confidence.fullAnswers];
}

export interface DriverView {
  key: string;
  label: string;
  direction: "up" | "down";
  phrase: string;
  /** "You rated it 4 out of 5", from the value the API returned; null when there is nothing to show. */
  valueText: string | null;
}

/** The driver's value in words. Booleans and diet codes become the answer the user picked. */
export function driverValueText(feature: string, value: Driver["value"]): string | null {
  const format = DRIVER_VALUES[feature];
  if (!format || value === null || value === undefined || value === "") return null;
  let shown = String(value);
  if (typeof value === "boolean") shown = value ? YES_NO.yes : YES_NO.no;
  else if (feature === "dietary_habits") {
    const options = OPTIONAL.dietaryHabits.options as Record<string, string>;
    shown = options[shown.toLowerCase()] ?? shown;
  } else if (feature === "family_history") {
    const options = { yes: YES_NO.yes, no: YES_NO.no, true: YES_NO.yes, false: YES_NO.no } as Record<string, string>;
    shown = options[shown.toLowerCase()] ?? shown;
  }
  return format(shown);
}

/** Up to three drivers with friendly labels. Unknown feature names fall back to the API's own label. */
export function driverViews(drivers: Driver[] | null | undefined, kind: "mood" | "sleep"): DriverView[] {
  return (drivers ?? []).slice(0, 3).map((d, i) => {
    const up = d.direction === "increases";
    return {
      key: `${d.feature}-${i}`,
      label: DRIVER_LABELS[d.feature] ?? (d.label || d.feature.replace(/_/g, " ")),
      direction: up ? "up" : "down",
      phrase: RESULT.direction[kind][up ? "increases" : "decreases"],
      valueText: driverValueText(d.feature, d.value),
    };
  });
}

export type SleepView =
  | { mode: "pattern"; displayText: string; guidance: Guidance; showSeeDoctor: boolean }
  | { mode: "guidance"; reason: string | null; guidance: Guidance; showSeeDoctor: false };

/**
 * A pattern result when the sleep model was in range and returned a class; otherwise the guidance-only result,
 * which is a normal outcome, not an error. The see-a-doctor line follows the backend's SEE_DOCTOR action
 * (derived from the ML service's see_doctor flag).
 */
export function sleepView(sleep: SleepResult, suggestedActions: string[]): SleepView {
  if (sleep.in_range && sleep.pattern_code && sleep.display_text) {
    return {
      mode: "pattern",
      displayText: sleep.display_text,
      guidance: sleep.guidance,
      showSeeDoctor: suggestedActions.includes("SEE_DOCTOR"),
    };
  }
  return { mode: "guidance", reason: sleep.reason, guidance: sleep.guidance, showSeeDoctor: false };
}

export interface ActionLink {
  code: string;
  label: string;
  description: string;
  /** Null for advice that has no page in the app (SEE_DOCTOR). */
  href: string | null;
}

const ACTION_HREFS: Record<string, string | null> = {
  ASSESSMENT_PHQ9: "/assessment/phq9",
  ASSESSMENT_PSS10: "/assessment/pss10",
  FIND_PROFESSIONAL: "/professionals",
  RELAXATION: "/relaxation",
  RELAXATION_SLEEP: "/relaxation/sleep",
  SEE_DOCTOR: null,
  KEEP_TRACKING: WELLNESS_ROUTES.history,
};

/** Known codes in the backend's order; unknown codes are ignored, duplicates dropped. */
export function actionLinks(codes: string[] | null | undefined): ActionLink[] {
  const out: ActionLink[] = [];
  for (const code of codes ?? []) {
    const copy = ACTIONS[code];
    if (!copy || !(code in ACTION_HREFS) || out.some((a) => a.code === code)) continue;
    out.push({ code, label: copy.label, description: copy.description, href: ACTION_HREFS[code] });
  }
  return out;
}

/** "saved" when the backend stored it (signed in), otherwise the sign-in call to action. */
export function saveState(response: Pick<CheckinResponse, "saved" | "id">): "saved" | "anonymous" {
  return response.saved && response.id ? "saved" : "anonymous";
}

/** The app's existing crisis resources are shown with a High band. */
export function showCrisisResources(band: string): boolean {
  return band === "High";
}

/** History/dashboard sleep flag: a pattern worth a look, an okay pattern, or guidance only. */
export function sleepFlag(sleep: SleepResult, suggestedActions: string[]): "concern" | "ok" | "guidance" {
  if (!sleep.in_range || !sleep.pattern_code) return "guidance";
  return sleep.pattern_code !== "none" || suggestedActions.includes("SEE_DOCTOR") ? "concern" : "ok";
}

/** True when the mood estimate filled in optional answers the user skipped (so adding them could help). */
export function skippedOptional(mood: Pick<MoodResult, "imputed" | "confidence_reasons">): boolean {
  return (
    (mood.imputed ?? []).length > 0 ||
    (mood.confidence_reasons ?? []).some((r) => r.startsWith("optional input(s) not provided"))
  );
}

/** How a what-if band compares with the snapshot's band. */
export function bandChange(from: string, to: string): "same" | "lower" | "higher" | null {
  const a = bandIndex(from);
  const b = bandIndex(to);
  if (a < 0 || b < 0) return null;
  return a === b ? "same" : b < a ? "lower" : "higher";
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** Range-meter scales: the two documented guidelines (adults 7+ h sleep; WHO 150+ min activity a week). */
export const SLEEP_METER = { min: 4, max: 10, zoneFrom: 7, zoneTo: 9 } as const;
export const MOVEMENT_METER = { min: 0, max: 300, zoneFrom: 150, zoneTo: 300 } as const;

/** Minutes a day to minutes a week. */
export function weeklyMinutes(minutesPerDay: number): number {
  return Math.round(minutesPerDay * 7);
}

/** Position (0-100 %) of a value on a meter, clamped to the scale. */
export function meterPercent(value: number, scale: { min: number; max: number }): number {
  return ((clamp(value, scale.min, scale.max) - scale.min) / (scale.max - scale.min)) * 100;
}

/** Whole calendar days between a date and now (local time); 0 for today or a future date. */
export function daysAgo(iso: string, now: Date = new Date()): number {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return 0;
  const start = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  return Math.max(0, Math.round((start(now) - start(date)) / 86_400_000));
}
