import type { AssessmentOption, AssessmentQuestion, AssessmentResult } from "./types";
import { ASSESSMENTS } from "./registry";

// Espie et al., BMJ Open 2014;4(3):e004183 (appendix). Each item has its own
// answer labels, so every question carries its own options, best answer
// first. Item scores are 0-4; higher = better sleep.
const DURATION_OPTIONS: AssessmentOption[] = [
  { label: "0–15 min", score: 4 },
  { label: "16–30 min", score: 3 },
  { label: "31–45 min", score: 2 },
  { label: "46–60 min", score: 1 },
  { label: "61 min or more", score: 0 },
];

const IMPACT_OPTIONS: AssessmentOption[] = [
  { label: "Not at all", score: 4 },
  { label: "A little", score: 3 },
  { label: "Somewhat", score: 2 },
  { label: "Much", score: 1 },
  { label: "Very much", score: 0 },
];

export const SCI_QUESTIONS: AssessmentQuestion[] = [
  {
    id: 1,
    area: "Falling asleep",
    text: "Thinking about a typical night in the last month, how long does it take you to fall asleep?",
    options: DURATION_OPTIONS,
  },
  {
    id: 2,
    area: "Staying asleep",
    text: "Thinking about a typical night in the last month, if you then wake up during the night, how long are you awake for in total? (Add all the wakenings up.)",
    options: DURATION_OPTIONS,
  },
  {
    id: 3,
    area: "Nights per week",
    text: "Thinking about a typical night in the last month, how many nights a week do you have a problem with your sleep?",
    options: [
      { label: "0–1 nights", score: 4 },
      { label: "2 nights", score: 3 },
      { label: "3 nights", score: 2 },
      { label: "4 nights", score: 1 },
      { label: "5–7 nights", score: 0 },
    ],
  },
  {
    id: 4,
    area: "Sleep quality",
    text: "Thinking about a typical night in the last month, how would you rate your sleep quality?",
    options: [
      { label: "Very good", score: 4 },
      { label: "Good", score: 3 },
      { label: "Average", score: 2 },
      { label: "Poor", score: 1 },
      { label: "Very poor", score: 0 },
    ],
  },
  {
    id: 5,
    area: "Mood and energy",
    text: "Thinking about the past month, to what extent has poor sleep affected your mood, energy, or relationships?",
    options: IMPACT_OPTIONS,
  },
  {
    id: 6,
    area: "Focus",
    text: "Thinking about the past month, to what extent has poor sleep affected your concentration, productivity, or ability to stay awake?",
    options: IMPACT_OPTIONS,
  },
  {
    id: 7,
    area: "How much it bothers you",
    text: "Thinking about the past month, to what extent has poor sleep troubled you in general?",
    options: IMPACT_OPTIONS,
  },
  {
    id: 8,
    area: "How long",
    text: "Finally, how long have you had a problem with your sleep?",
    options: [
      { label: "I don't have a problem / less than 1 month", score: 4 },
      { label: "1–2 months", score: 3 },
      { label: "3–6 months", score: 2 },
      { label: "7–12 months", score: 1 },
      { label: "More than 1 year", score: 0 },
    ],
  },
];

const MAX_RAW_SCORE = 32;
// Espie et al. 2014: a total of 16 or less is the published cut-off for
// probable insomnia disorder. The UI never uses that term; the result is a
// snapshot, not a diagnosis.
const SCI_CUTOFF = 16;

type SciLevel = "steady" | "difficulty";

/**
 * Calculates the SCI sleep result.
 * totalScore range: 0-32. Higher = better sleep.
 */
export function calculateSCI(answers: Record<number, number>): AssessmentResult {
  const rawScore = SCI_QUESTIONS.reduce((total, question) => total + (answers[question.id] ?? 0), 0);
  const percentage = Math.round((rawScore / MAX_RAW_SCORE) * 100);

  const levelKey: SciLevel = rawScore <= SCI_CUTOFF ? "difficulty" : "steady";
  const level = levelKey === "steady" ? "Steady sleep" : "Sleep difficulty";
  const tone = levelKey === "steady" ? "positive" : "caution";
  const suggestProfessionalSupport = levelKey === "difficulty";

  return {
    userId: null,
    assessmentType: "sci",
    assessmentLabel: ASSESSMENTS.sci.label,
    snapshotTitle: ASSESSMENTS.sci.snapshotTitle,
    responses: answers,
    rawScore,
    maxScore: MAX_RAW_SCORE,
    percentage,
    levelKey,
    level,
    tone,
    interpretation: getInterpretation(levelKey),
    safetyFlag: false,
    suggestFurtherExploration: false,
    suggestProfessionalSupport,
    completedAt: new Date().toISOString(),
  };
}

function getInterpretation(level: SciLevel): string {
  switch (level) {
    case "steady":
      return "Your sleep seems to be feeling restful lately.";
    case "difficulty":
    default:
      return "Your sleep may be affecting how rested or refreshed you feel. Talking with a professional may help.";
  }
}
