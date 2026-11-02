// The questionnaire's answer model and its mapping onto the API request. Pure functions (no React), so they
// are unit-tested with Node's test runner. Ranges mirror backend/docs/wellness-checkin.md; the ML service is
// still the source of truth and its 400 field messages are shown inline.

import type { CheckinRequest, DietaryHabits, FieldError, Segment } from "./types.ts";

/** A chip: what the user taps, and the number (or value) that is sent. */
export interface ChipOption<V> {
  id: string;
  value: V;
}

/** Minutes of movement per day for each chip. */
export const MOVEMENT_OPTIONS: ChipOption<number>[] = [
  { id: "lt15", value: 10 },
  { id: "m15to30", value: 25 },
  { id: "m30to60", value: 45 },
  { id: "gt60", value: 75 },
];

/** Representative hours asleep for each chip. */
export const SLEEP_OPTIONS: ChipOption<number>[] = [
  { id: "lt5", value: 4.5 },
  { id: "h5to6", value: 5.5 },
  { id: "h6to7", value: 6.5 },
  { id: "h7to8", value: 7.5 },
  { id: "gt8", value: 8.5 },
];

export const WORK_STUDY_HOURS_OPTIONS: ChipOption<number>[] = [
  { id: "lt4", value: 3 },
  { id: "h4to6", value: 5 },
  { id: "h6to8", value: 7 },
  { id: "h8to10", value: 9 },
  { id: "gt10", value: 11 },
];

export const DIET_OPTIONS: ChipOption<DietaryHabits>[] = [
  { id: "unhealthy", value: "unhealthy" },
  { id: "moderate", value: "moderate" },
  { id: "healthy", value: "healthy" },
];

export const HEART_RATE_OPTIONS: ChipOption<number>[] = [
  { id: "lt60", value: 55 },
  { id: "b60to70", value: 65 },
  { id: "b70to80", value: 75 },
  { id: "gt80", value: 85 },
];

export const STEPS_OPTIONS: ChipOption<number>[] = [
  { id: "lt3k", value: 2500 },
  { id: "s3to6k", value: 4500 },
  { id: "s6to9k", value: 7500 },
  { id: "gt9k", value: 10500 },
];

export const SEGMENTS: Segment[] = ["student", "working", "both"];

export const AGE_MIN = 16;
export const AGE_MAX = 80;
/** Where the age stepper starts when first tapped. */
export const AGE_START: Record<Segment, number> = { student: 20, working: 30, both: 24 };

/** "Prefer not to say" is an answer of its own: the field is then left out of the request. */
export type FamilyHistoryAnswer = "yes" | "no" | "prefer_not";

/** Everything the user has answered; null = not answered (required) or skipped (optional). */
export interface Answers {
  segment: Segment | null;
  age: number | null;
  movementMinutes: number | null;
  sleepHours: number | null;
  rested: number | null;
  pressure: number | null;
  satisfaction: number | null;
  financialStress: number | null;
  stress: number | null;
  workStudyHours: number | null;
  dietaryHabits: DietaryHabits | null;
  familyHistory: FamilyHistoryAnswer | null;
  heartRate: number | null;
  dailySteps: number | null;
}

export const EMPTY_ANSWERS: Answers = {
  segment: null,
  age: null,
  movementMinutes: null,
  sleepHours: null,
  rested: null,
  pressure: null,
  satisfaction: null,
  financialStress: null,
  stress: null,
  workStudyHours: null,
  dietaryHabits: null,
  familyHistory: null,
  heartRate: null,
  dailySteps: null,
};

export type RequiredKey =
  | "segment"
  | "age"
  | "movementMinutes"
  | "sleepHours"
  | "rested"
  | "pressure"
  | "satisfaction"
  | "financialStress"
  | "stress";

/** Required answers per step (steps 0-3). The optional section sits on the last step. */
export const STEP_FIELDS: RequiredKey[][] = [
  ["segment", "age", "movementMinutes"],
  ["sleepHours", "rested"],
  ["pressure", "satisfaction", "financialStress"],
  ["stress"],
];

/** API (camelCase request) field name to the answer that feeds it, for showing 400 messages inline. */
export const API_FIELD_TO_ANSWER: Record<string, keyof Answers> = {
  segment: "segment",
  age: "age",
  physicalActivityMinutes: "movementMinutes",
  sleepDuration: "sleepHours",
  qualityOfSleep: "rested",
  pressure: "pressure",
  satisfaction: "satisfaction",
  financialStress: "financialStress",
  stressLevel: "stress",
  workStudyHours: "workStudyHours",
  dietaryHabits: "dietaryHabits",
  familyHistory: "familyHistory",
  heartRate: "heartRate",
  dailySteps: "dailySteps",
};

const RANGES: Partial<Record<RequiredKey, [number, number]>> = {
  age: [AGE_MIN, AGE_MAX],
  rested: [1, 10],
  stress: [1, 10],
  pressure: [1, 5],
  satisfaction: [1, 5],
  financialStress: [1, 5],
};

/** Required answers of one step that are missing or out of range. */
export function missingInStep(answers: Answers, step: number): RequiredKey[] {
  return (STEP_FIELDS[step] ?? []).filter((key) => {
    const value = answers[key];
    if (value === null || value === undefined) return true;
    const range = RANGES[key];
    return range !== undefined && typeof value === "number" && (value < range[0] || value > range[1] || !Number.isInteger(value));
  });
}

export function isComplete(answers: Answers): boolean {
  return STEP_FIELDS.every((_, step) => missingInStep(answers, step).length === 0);
}

/** The first step with an answer behind one of these API field errors (to jump back to it). */
export function stepForFieldErrors(errors: FieldError[]): number | null {
  let first: number | null = null;
  for (const e of errors) {
    const answer = e.field ? API_FIELD_TO_ANSWER[e.field] : undefined;
    if (!answer) continue;
    const step = STEP_FIELDS.findIndex((fields) => (fields as (keyof Answers)[]).includes(answer));
    // Optional answers live on the last step.
    const resolved = step === -1 ? STEP_FIELDS.length - 1 : step;
    first = first === null ? resolved : Math.min(first, resolved);
  }
  return first;
}

/** Field errors keyed by answer, for inline messages. */
export function errorsByAnswer(errors: FieldError[]): Partial<Record<keyof Answers, string>> {
  const out: Partial<Record<keyof Answers, string>> = {};
  for (const e of errors) {
    const answer = e.field ? API_FIELD_TO_ANSWER[e.field] : undefined;
    if (answer && !out[answer]) out[answer] = e.message;
  }
  return out;
}

/**
 * The API request for complete answers. Skipped optional answers (and "prefer not to say") are left out
 * entirely, never sent as null. Throws if a required answer is missing; check isComplete first.
 */
export function toRequest(answers: Answers): CheckinRequest {
  if (!isComplete(answers)) throw new Error("toRequest called with incomplete answers");
  const req: CheckinRequest = {
    segment: answers.segment as Segment,
    age: answers.age as number,
    sleepDuration: answers.sleepHours as number,
    qualityOfSleep: answers.rested as number,
    stressLevel: answers.stress as number,
    physicalActivityMinutes: answers.movementMinutes as number,
    pressure: answers.pressure as number,
    satisfaction: answers.satisfaction as number,
    financialStress: answers.financialStress as number,
  };
  if (answers.workStudyHours !== null) req.workStudyHours = answers.workStudyHours;
  if (answers.dietaryHabits !== null) req.dietaryHabits = answers.dietaryHabits;
  if (answers.familyHistory === "yes") req.familyHistory = true;
  if (answers.familyHistory === "no") req.familyHistory = false;
  if (answers.heartRate !== null) req.heartRate = answers.heartRate;
  if (answers.dailySteps !== null) req.dailySteps = answers.dailySteps;
  return req;
}

/** The what-if variant of a request: only sleep, stress and movement change. */
export function withWhatIf(
  base: CheckinRequest,
  change: { sleepDuration: number; stressLevel: number; physicalActivityMinutes: number },
): CheckinRequest {
  return { ...base, ...change };
}
