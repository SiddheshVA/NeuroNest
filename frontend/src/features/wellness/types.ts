// Types for /api/wellness (backend/docs/wellness-checkin.md; source of truth: WellnessDtos.java). The envelope
// is camelCase; `result` is the ML service's response unchanged, so its keys are snake_case.

export type Segment = "student" | "working" | "both";
export type DietaryHabits = "unhealthy" | "moderate" | "healthy";
export type BmiCategory = "normal" | "overweight" | "obese";

/**
 * POST /api/wellness/checkin body. Exactly the fields WellnessDtos.CheckinRequest accepts: any other field
 * is rejected by the API. Optional fields are left out when not answered (never sent as null).
 */
export interface CheckinRequest {
  segment: Segment;
  age: number;
  /** Hours asleep per night. */
  sleepDuration: number;
  /** 1-10: how rested you feel on waking. */
  qualityOfSleep: number;
  /** 1-10. */
  stressLevel: number;
  /** Minutes per day. */
  physicalActivityMinutes: number;
  /** 1-5. */
  pressure: number;
  /** 1-5. */
  satisfaction: number;
  /** 1-5. */
  financialStress: number;
  dietaryHabits?: DietaryHabits;
  workStudyHours?: number;
  familyHistory?: boolean;
  bmiCategory?: BmiCategory;
  heartRate?: number;
  dailySteps?: number;
}

export type RiskBand = "Low" | "Moderate" | "High";
export type Confidence = "low" | "medium" | "high";
export type SleepCode = "none" | "insomnia_like" | "apnea_like";

export interface Driver {
  feature: string;
  label: string;
  value: string | number | boolean | null;
  direction: "increases" | "decreases";
  effect: string;
  impact: number;
}

export interface MoodResult {
  model: string;
  segment: string;
  risk_band: RiskBand;
  probability: number;
  confidence: Confidence;
  confidence_reasons: string[];
  drivers: Driver[];
  imputed: string[];
  ignored_inputs: string[];
  out_of_range: string[];
}

export interface GuidanceMessage {
  code: string;
  text: string;
}

export interface Guidance {
  messages: GuidanceMessage[];
  meets_sleep_guideline: boolean;
  meets_activity_guideline: boolean;
  weekly_activity_minutes: number;
  source: string;
}

export interface SleepResult {
  model: string | null;
  in_range: boolean;
  reason: string | null;
  pattern_code: SleepCode | null;
  display_text: string | null;
  see_doctor: boolean | null;
  probability: number | null;
  probabilities: Record<string, number> | null;
  probabilities_calibrated: boolean | null;
  confidence: Confidence | null;
  confidence_reasons: string[];
  drivers: Driver[];
  imputed: string[];
  out_of_range: string[];
  guidance: Guidance;
}

export interface CheckinResult {
  request_id: string;
  mood: MoodResult;
  sleep: SleepResult;
  model_versions: Record<string, unknown>;
  disclaimer: string;
}

/** The POST answer. Guests: id and createdAt null, saved false (nothing stored). */
export interface CheckinResponse {
  id: string | null;
  saved: boolean;
  createdAt: string | null;
  result: CheckinResult;
  suggestedActions: string[];
}

export interface CheckinItem {
  id: string;
  createdAt: string;
  segment: Segment;
  result: CheckinResult;
  suggestedActions: string[];
}

export interface CheckinPage {
  items: CheckinItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface FieldError {
  /** camelCase field name of CheckinRequest, or null for a general message. */
  field: string | null;
  message: string;
}
