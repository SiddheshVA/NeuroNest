import { apiFetch } from "@/lib/api";
import { calculateGAD7 } from "@/lib/assessments/gad7";
import { calculateK10 } from "@/lib/assessments/k10";
import { calculatePHQ9 } from "@/lib/assessments/phq9";
import { calculatePSS10 } from "@/lib/assessments/pss10";
import { calculateWHO5 } from "@/lib/assessments/who5";
import type { AnyAssessmentResult, AssessmentType } from "@/lib/assessments/types";

/** One saved check-in as GET /api/me/dashboard returns it (the backend's AssessmentResult). */
export interface SavedAssessment {
  id: string;
  instrument: string;
  score: number;
  severityBand: string;
  riskFlag: boolean;
  itemResponses: number[];
  createdAt: string;
}

export interface DashboardData {
  name: string;
  email: string;
  totalSessions: number;
  /** All of the signed-in user's results, newest first. */
  assessments: SavedAssessment[];
}

/** A saved check-in scored again by the same frontend code the results screen uses. */
export interface CheckIn {
  saved: SavedAssessment;
  result: AnyAssessmentResult;
}

export async function fetchDashboard(): Promise<DashboardData> {
  const res = await apiFetch("/me/dashboard");
  if (!res.ok) throw new Error(String(res.status));
  return (await res.json()) as DashboardData;
}

// The backend stores the five instruments it scores (SCI stays local-only, see lib/assessments/sync.ts).
const SCORERS: Record<string, { type: AssessmentType; calculate: (answers: Record<number, number>) => AnyAssessmentResult }> = {
  WHO5: { type: "who5", calculate: calculateWHO5 },
  PHQ9: { type: "phq9", calculate: calculatePHQ9 },
  GAD7: { type: "gad7", calculate: calculateGAD7 },
  PSS10: { type: "pss10", calculate: calculatePSS10 },
  K10: { type: "k10", calculate: calculateK10 },
};

/**
 * Scores a saved result's own answers with the results screen's calculate* function, so the band,
 * score scale and interpretation match what the person saw. Null for an unknown instrument or
 * answers that don't fit it.
 */
export function toCheckIn(saved: SavedAssessment): CheckIn | null {
  const scorer = SCORERS[saved.instrument];
  if (!scorer || !Array.isArray(saved.itemResponses) || saved.itemResponses.length === 0) return null;
  // The answers were sent in question order (ids 1..N), see syncAssessmentToBackend.
  const answers: Record<number, number> = {};
  saved.itemResponses.forEach((value, index) => {
    answers[index + 1] = value;
  });
  const result = scorer.calculate(answers);
  return { saved, result: { ...result, completedAt: saved.createdAt } };
}

/** All check-ins that could be scored, newest first (the order the backend sends). */
export function toCheckIns(assessments: SavedAssessment[]): CheckIn[] {
  return assessments.map(toCheckIn).filter((checkIn): checkIn is CheckIn => checkIn !== null);
}

export function checkInHref(id: string) {
  return `/dashboard/check-ins/${encodeURIComponent(id)}`;
}
