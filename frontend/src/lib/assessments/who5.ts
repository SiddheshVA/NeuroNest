import type { AssessmentOption, AssessmentQuestion, AssessmentResult } from "./types";
import { ASSESSMENTS } from "./registry";

// Official WHO-5 statements (WHO, 2024), in the official order. The stem
// "Over the last two weeks..." is shown as the instruction in the modal.
export const WHO5_QUESTIONS: AssessmentQuestion[] = [
  { id: 1, area: "Mood", text: "I have felt cheerful and in good spirits" },
  { id: 2, area: "Calm", text: "I have felt calm and relaxed" },
  { id: 3, area: "Energy", text: "I have felt active and vigorous" },
  { id: 4, area: "Sleep", text: "I woke up feeling fresh and rested" },
  { id: 5, area: "Interest", text: "My daily life has been filled with things that interest me" },
];

// Official WHO-5 response scale: 0 (At no time) – 5 (All of the time).
export const WHO5_OPTIONS: AssessmentOption[] = [
  { label: "All of the time", score: 5 },
  { label: "Most of the time", score: 4 },
  { label: "More than half of the time", score: 3 },
  { label: "Less than half of the time", score: 2 },
  { label: "Some of the time", score: 1 },
  { label: "At no time", score: 0 },
];

const MAX_RAW_SCORE = 25;
// Official WHO-5 cutoff: a percentage score below 50 suggests poor wellbeing
// and warrants further exploration — never a diagnosis on its own.
const SUPPORT_THRESHOLD = 50;

/**
 * Calculates the official WHO-5 result from raw question responses.
 * rawScore = sum of the 5 item scores (0-25)
 * percentage = rawScore * 4 (0-100)
 *
 * This score is a wellbeing snapshot only. It must never be used to assert
 * that the user has anxiety, depression, stress, or a sleep disorder — a low
 * score only means further exploration (via a targeted screening) may help.
 */
export function calculateWHO5(answers: Record<number, number>): AssessmentResult {
  const rawScore = WHO5_QUESTIONS.reduce((total, question) => total + (answers[question.id] ?? 0), 0);
  const percentage = rawScore * 4;
  const suggestFurtherExploration = percentage < SUPPORT_THRESHOLD;

  const levelKey = percentage >= 70 ? "high" : percentage >= SUPPORT_THRESHOLD ? "moderate" : "low";
  const level = levelKey === "high" ? "High wellbeing" : levelKey === "moderate" ? "Moderate wellbeing" : "Low wellbeing";
  const tone = levelKey === "high" ? "positive" : levelKey === "moderate" ? "neutral" : "caution";

  return {
    userId: null,
    assessmentType: "who5",
    assessmentLabel: ASSESSMENTS.who5.label,
    snapshotTitle: ASSESSMENTS.who5.snapshotTitle,
    responses: answers,
    rawScore,
    maxScore: MAX_RAW_SCORE,
    percentage,
    levelKey,
    level,
    tone,
    interpretation: getInterpretation(levelKey),
    safetyFlag: false,
    suggestFurtherExploration,
    suggestProfessionalSupport: false,
    completedAt: new Date().toISOString(),
  };
}

type Who5Level = "high" | "moderate" | "low";

function getInterpretation(level: Who5Level): string {
  switch (level) {
    case "high":
      return "Your responses show a generally positive sense of wellbeing right now.";
    case "moderate":
      return "Your wellbeing seems steady right now, with some room for a bit more rest and care.";
    case "low":
    default:
      return "Your wellbeing may need a little more attention right now. Taking a closer look at what's been affecting you lately may help.";
  }
}
