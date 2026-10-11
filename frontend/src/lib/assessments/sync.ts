import { apiFetch } from "@/lib/api";
import type { AnyAssessmentResult, AssessmentType } from "./types";

// Saves a completed screening to the Spring Boot backend
// (POST /api/sessions/{sessionId}/assessments/{type}) so it shows up on the
// dashboard. The result page itself is still rendered from the locally
// computed result — the backend re-scores the same raw answers on its side.
// PSQI isn't supported by the backend, so it stays local-only.
const BACKEND_TYPES: Partial<Record<AssessmentType, number>> = {
  who5: 5,
  phq9: 9,
  gad7: 7,
  pss10: 10,
};

const SESSION_KEY = "nuronest:assessment-session";

async function createSession(): Promise<string> {
  const res = await apiFetch("/sessions", { method: "POST" });
  if (!res.ok) throw new Error(`Could not start a session (${res.status})`);
  const { id } = (await res.json()) as { id: string };
  try {
    window.sessionStorage.setItem(SESSION_KEY, id);
  } catch {
    // no-op: a new session is simply created next time
  }
  return id;
}

function existingSession(): string | null {
  try {
    return window.sessionStorage.getItem(SESSION_KEY);
  } catch {
    return null;
  }
}

function post(sessionId: string, type: AssessmentType, responses: number[]) {
  return apiFetch(`/sessions/${encodeURIComponent(sessionId)}/assessments/${type}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ responses }),
  });
}

export async function syncAssessmentToBackend(result: AnyAssessmentResult): Promise<void> {
  const itemCount = BACKEND_TYPES[result.assessmentType];
  if (!itemCount) return;

  // Likert answers are keyed by question id 1..N; the backend wants them in order.
  const answers = result.responses as Record<number, number>;
  const responses = Array.from({ length: itemCount }, (_, i) => answers[i + 1] ?? 0);

  try {
    let sessionId = existingSession() ?? (await createSession());
    let res = await post(sessionId, result.assessmentType, responses);

    // The stored session may belong to a different login state (e.g. the
    // user signed in or out since) — start a fresh one and retry once.
    if (res.status === 403 || res.status === 404) {
      sessionId = await createSession();
      res = await post(sessionId, result.assessmentType, responses);
    }
    if (!res.ok) throw new Error(`Backend returned ${res.status}`);
  } catch (error) {
    console.warn("[assessments] could not save result to the server", error);
  }
}
