import { ASSESSMENTS } from "./registry.ts";
import type { AssessmentType } from "./types";

/**
 * Pure logic for the dashboard's check-in result page: each instrument's bands grouped into four
 * levels, the sentence comparing a result with the previous one, and the existing Relaxation items
 * and articles suggested per level. No React and no data imports, so it runs under `node --test`
 * (relative imports therefore carry their .ts extension).
 */

export type InsightLevel = "good" | "mild" | "moderate" | "high";

/** The frontend scoring's levelKeys (lib/assessments/*.ts, ranges.ts), grouped into four levels. */
export const BAND_LEVELS: Record<AssessmentType, Record<string, InsightLevel>> = {
  who5: { high: "good", moderate: "mild", low: "moderate" },
  gad7: { minimal: "good", mild: "mild", moderate: "moderate", severe: "high" },
  phq9: { minimal: "good", mild: "mild", moderate: "moderate", "moderately-severe": "high", severe: "high" },
  // PSS-10's "moderate" covers the broad middle (14-26); the results screen only suggests support at "high".
  pss10: { low: "good", moderate: "mild", high: "high" },
  k10: { "likely-well": "good", mild: "mild", moderate: "moderate", severe: "high" },
  sci: { steady: "good", difficulty: "moderate" },
};

export function levelFor(type: AssessmentType, levelKey: string): InsightLevel | null {
  return BAND_LEVELS[type][levelKey] ?? null;
}

export function isModerateOrAbove(level: InsightLevel | null): boolean {
  return level === "moderate" || level === "high";
}

export const VARIABILITY_NOTE =
  "Scores can change from day to day. The pattern over several check-ins tells you more.";

/**
 * One neutral sentence comparing a score with the previous result of the same instrument.
 * `numericOnly` (a high-risk result) leaves out which direction is the better one.
 */
export function compareWithPrevious(
  type: AssessmentType,
  score: number,
  previousScore: number | null,
  numericOnly = false,
): string {
  const { label, higherIsBetter } = ASSESSMENTS[type];
  if (previousScore === null) {
    return `This is your first ${label}. Retake it in a couple of weeks to see how it changes.`;
  }
  const difference = score - previousScore;
  if (difference === 0) {
    return `Your score is the same as last time (${score}).`;
  }
  const points = Math.abs(difference) === 1 ? "1 point" : `${Math.abs(difference)} points`;
  const sentence = `Your score is ${points} ${difference > 0 ? "higher" : "lower"} than last time (${previousScore} → ${score}).`;
  if (numericOnly) return sentence;
  const movedTheBetterWay = difference > 0 === higherIsBetter;
  return movedTheBetterWay
    ? `${sentence} For ${label}, that is the better direction.`
    : `${sentence} For ${label}, ${higherIsBetter ? "higher" : "lower"} scores are the better direction.`;
}

/**
 * The one-line reason next to "Talk to a professional". `bandLabel` is the instrument's own band
 * name (e.g. "Moderate"); `previousLevel` is the level of the previous result of the same instrument.
 */
export function professionalReason(
  type: AssessmentType,
  level: InsightLevel | null,
  bandLabel: string,
  previousLevel: InsightLevel | null,
  numericOnly = false,
): string {
  const { label } = ASSESSMENTS[type];
  const band = bandLabel.toLowerCase();
  const suffix = numericOnly ? "." : ", so talking it through with a professional could help.";
  if (isModerateOrAbove(level) && isModerateOrAbove(previousLevel)) {
    const range = level === "moderate" && previousLevel === "moderate" ? "the moderate range" : "the moderate range or above";
    return `Your last two ${label} results were both in ${range}${suffix}`;
  }
  return `Your ${label} result is in the ${band} range${suffix}`;
}

/** An existing item: a Relaxation catalog key (lib data/relaxation.ts) or an article slug. */
export type SuggestionRef = { kind: "relaxation"; key: string } | { kind: "article"; slug: string };

const relax = (key: string): SuggestionRef => ({ kind: "relaxation", key });
const article = (slug: string): SuggestionRef => ({ kind: "article", slug });

const DAILY_ROUTINE = article("healthy-daily-routine");
const WHEN_TO_TALK = article("when-to-talk-to-a-professional");
const UNDERSTANDING_STRESS = article("understanding-stress");
const SLEEP_ARTICLE = article("sleep-and-mental-wellbeing");
const PANIC_ATTACKS = article("panic-attacks");
const SETBACKS = article("coping-with-failure-and-setbacks");
const BREATHE_RESET = relax("meditation:mindful-breathing");
const BOX_BREATHING = relax("breathing:box-breathing");
const GROUNDING = relax("mindful-reset:5-4-3-2-1-grounding");
const EXTENDED_EXHALE = relax("breathing:extended-exhale");
const LOVING_KINDNESS = relax("meditation:loving-kindness");
const MUSCLE_RELAXATION = relax("mindful-reset:progressive-muscle-relaxation");

/** 2-3 next steps per instrument and level (agreed list). */
export const SUGGESTIONS: Record<AssessmentType, Partial<Record<InsightLevel, SuggestionRef[]>>> = {
  who5: {
    good: [BREATHE_RESET, DAILY_ROUTINE],
    mild: [BOX_BREATHING, DAILY_ROUTINE, SLEEP_ARTICLE],
    moderate: [relax("breathing:belly-breathing"), article("loneliness-and-social-connection"), WHEN_TO_TALK],
  },
  gad7: {
    good: [BOX_BREATHING, BREATHE_RESET],
    mild: [EXTENDED_EXHALE, GROUNDING],
    moderate: [GROUNDING, EXTENDED_EXHALE, PANIC_ATTACKS],
    high: [GROUNDING, relax("breathing:cyclic-sighing"), PANIC_ATTACKS],
  },
  phq9: {
    good: [LOVING_KINDNESS, DAILY_ROUTINE],
    mild: [SETBACKS, DAILY_ROUTINE, LOVING_KINDNESS],
    moderate: [SETBACKS, DAILY_ROUTINE, LOVING_KINDNESS],
    high: [DAILY_ROUTINE, WHEN_TO_TALK],
  },
  pss10: {
    good: [BOX_BREATHING, BREATHE_RESET],
    mild: [UNDERSTANDING_STRESS, MUSCLE_RELAXATION, BOX_BREATHING],
    high: [article("understanding-burnout"), article("managing-exam-stress"), MUSCLE_RELAXATION],
  },
  k10: {
    good: [BREATHE_RESET, DAILY_ROUTINE],
    mild: [BOX_BREATHING, UNDERSTANDING_STRESS, DAILY_ROUTINE],
    moderate: [GROUNDING, UNDERSTANDING_STRESS, WHEN_TO_TALK],
    high: [GROUNDING, WHEN_TO_TALK],
  },
  sci: {
    good: [relax("sleep:gentle-rain"), SLEEP_ARTICLE],
    moderate: [relax("sleep:sleep-meditation"), relax("breathing:4-7-8-breathing"), SLEEP_ARTICLE],
  },
};

export function suggestionsFor(type: AssessmentType, level: InsightLevel | null): SuggestionRef[] {
  return level ? (SUGGESTIONS[type][level] ?? []) : [];
}
