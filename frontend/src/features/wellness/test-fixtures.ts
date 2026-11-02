// Test-only fixtures shaped like backend/docs/wellness-checkin.md. Not imported by the app.
import type { CheckinItem, CheckinRequest, CheckinResponse, RiskBand, SleepResult } from "./types.ts";

export const DISCLAIMER =
  "This is an estimate based on a few self-reported answers, not a diagnosis. If you are struggling, please talk to a professional.";

const GUIDANCE = {
  messages: [
    { code: "sleep_short", text: "You're sleeping about 6.5 hours. Adults are generally advised to get at least 7 hours a night." },
    { code: "activity_ok", text: "About 210 minutes of activity a week meets the 150-minute adult guideline." },
  ],
  meets_sleep_guideline: false,
  meets_activity_guideline: true,
  weekly_activity_minutes: 210,
  source: "General adult guidelines: 7+ hours of sleep per night; 150 minutes of moderate activity per week.",
};

export function sleepInRange(code: "none" | "insomnia_like" | "apnea_like" = "insomnia_like"): SleepResult {
  return {
    model: "sleep_pattern_core",
    in_range: true,
    reason: null,
    pattern_code: code,
    display_text:
      code === "none"
        ? "Your answers don't resemble a specific sleep-problem pattern."
        : "Your answers resemble a difficulty-sleeping (insomnia-like) pattern.",
    see_doctor: code !== "none",
    probability: 0.57,
    probabilities: { none: 0.24, insomnia_like: 0.57, apnea_like: 0.19 },
    probabilities_calibrated: false,
    confidence: "medium",
    confidence_reasons: ["model quality limits confidence to 'medium'"],
    drivers: [
      { feature: "sleep_duration", label: "Sleep duration (h)", value: 6.5, direction: "increases", effect: "", impact: 0.67 },
    ],
    imputed: [],
    out_of_range: [],
    guidance: GUIDANCE,
  };
}

export function sleepOutOfRange(): SleepResult {
  return {
    model: null,
    in_range: false,
    reason: "The sleep-pattern model was built from adults aged 27-59 sleeping 5.8-8.5 hours.",
    pattern_code: null,
    display_text: null,
    see_doctor: null,
    probability: null,
    probabilities: null,
    probabilities_calibrated: null,
    confidence: null,
    confidence_reasons: ["out of range"],
    drivers: [],
    imputed: [],
    out_of_range: ["age"],
    guidance: GUIDANCE,
  };
}

export function response(
  band: RiskBand,
  opts: { sleep?: SleepResult; actions?: string[]; saved?: boolean } = {},
): CheckinResponse {
  const saved = opts.saved ?? false;
  return {
    id: saved ? "5f0c2a8e-3b1d-4c7a-9e2f-8d6b1a4c3e70" : null,
    saved,
    createdAt: saved ? "2026-11-02T11:40:12Z" : null,
    suggestedActions: opts.actions ?? ["KEEP_TRACKING"],
    result: {
      request_id: "r1",
      mood: {
        model: "mood_working",
        segment: "working",
        risk_band: band,
        probability: 0.2,
        confidence: "low",
        confidence_reasons: ["optional input(s) not provided: ['family_history']"],
        drivers: [
          { feature: "pressure", label: "Academic / work pressure", value: 4, direction: "increases", effect: "", impact: 0.55 },
          { feature: "satisfaction", label: "Study / job satisfaction", value: 2, direction: "increases", effect: "", impact: 0.45 },
          { feature: "sleep_duration", label: "Sleep duration", value: "7-8", direction: "decreases", effect: "", impact: -0.3 },
        ],
        imputed: ["family_history"],
        ignored_inputs: ["age"],
        out_of_range: [],
      },
      sleep: opts.sleep ?? sleepInRange(),
      model_versions: {},
      disclaimer: DISCLAIMER,
    },
  };
}

export function item(id: string, band: RiskBand, createdAt: string): CheckinItem {
  const r = response(band, { saved: true });
  return { id, createdAt, segment: "working", result: r.result, suggestedActions: r.suggestedActions };
}

export const REQUEST: CheckinRequest = {
  segment: "working",
  age: 34,
  sleepDuration: 6.5,
  qualityOfSleep: 6,
  stressLevel: 7,
  physicalActivityMinutes: 45,
  pressure: 4,
  satisfaction: 2,
  financialStress: 3,
};
