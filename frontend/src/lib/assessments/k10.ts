import type { AssessmentOption, AssessmentQuestion, AssessmentResult } from "./types";
import { ASSESSMENTS } from "./registry";

// Kessler et al., 2003. Stem: "In the past 4 weeks..."
export const K10_QUESTIONS: AssessmentQuestion[] = [
  { id: 1, area: "Tiredness", text: "About how often did you feel tired out for no good reason?" },
  { id: 2, area: "Nervousness", text: "About how often did you feel nervous?" },
  { id: 3, area: "Calming down", text: "About how often did you feel so nervous that nothing could calm you down?" },
  { id: 4, area: "Hopelessness", text: "About how often did you feel hopeless?" },
  { id: 5, area: "Restlessness", text: "About how often did you feel restless or fidgety?" },
  { id: 6, area: "Sitting still", text: "About how often did you feel so restless you could not sit still?" },
  { id: 7, area: "Low mood", text: "About how often did you feel depressed?" },
  { id: 8, area: "Effort", text: "About how often did you feel that everything was an effort?" },
  { id: 9, area: "Cheering up", text: "About how often did you feel so sad that nothing could cheer you up?" },
  { id: 10, area: "Self-worth", text: "About how often did you feel worthless?" },
];

export const K10_OPTIONS: AssessmentOption[] = [
  { label: "None of the time", score: 1 },
  { label: "A little of the time", score: 2 },
  { label: "Some of the time", score: 3 },
  { label: "Most of the time", score: 4 },
  { label: "All of the time", score: 5 },
];

const MAX_RAW_SCORE = 50;

type K10Level = "likely-well" | "mild" | "moderate" | "severe";

/**
 * Calculates the K10 general distress result.
 * Each item scores 1-5, so totalScore range: 10-50. Higher = more distress.
 */
export function calculateK10(answers: Record<number, number>): AssessmentResult {
  const rawScore = K10_QUESTIONS.reduce((total, question) => total + (answers[question.id] ?? 1), 0);
  const percentage = Math.round((rawScore / MAX_RAW_SCORE) * 100);

  const levelKey: K10Level =
    rawScore <= 19 ? "likely-well" : rawScore <= 24 ? "mild" : rawScore <= 29 ? "moderate" : "severe";
  const level =
    levelKey === "likely-well"
      ? "Likely well"
      : levelKey === "mild"
        ? "Mild distress"
        : levelKey === "moderate"
          ? "Moderate distress"
          : "Severe distress";
  const tone = levelKey === "likely-well" ? "positive" : levelKey === "severe" ? "concern" : "caution";
  const suggestProfessionalSupport = levelKey === "severe";

  return {
    userId: null,
    assessmentType: "k10",
    assessmentLabel: ASSESSMENTS.k10.label,
    snapshotTitle: ASSESSMENTS.k10.snapshotTitle,
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

function getInterpretation(level: K10Level): string {
  switch (level) {
    case "likely-well":
      return "Your answers suggest you have not been feeling much general distress lately. Keep doing what helps you feel steady.";
    case "mild":
      return "Your answers suggest you have been feeling some mild distress lately. Small steps like slow breathing, journaling or talking things through may help.";
    case "moderate":
      return "Your answers suggest you have been feeling a fair amount of distress lately. Grounding, relaxation and talking with someone you trust may help.";
    case "severe":
    default:
      return "Your answers suggest you have been feeling a lot of distress lately. You do not have to carry this alone. Talking with a professional may help.";
  }
}
