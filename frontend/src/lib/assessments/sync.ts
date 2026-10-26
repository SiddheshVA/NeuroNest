import { apiFetch } from "@/lib/api";
import { getCurrentUserId } from "@/lib/clerkAuthBridge";
import type { AnyAssessmentResult, AssessmentType } from "./types";

// Saves a completed screening to the Spring Boot backend
// (POST /api/sessions/{sessionId}/assessments/{type}) so it shows up on the
// dashboard. The result page itself is still rendered from the locally
// computed result — the backend re-scores the same raw answers on its side.
// SCI isn't supported by the backend (it has no sleep endpoint), so it stays
// local-only.
const BACKEND_TYPES: Partial<Record<AssessmentType, number>> = {
  who5: 5,
  phq9: 9,
  gad7: 7,
  pss10: 10,
  k10: 10,
};

// The session results are saved under, tagged with who created it: the signed-in Clerk user's id, or
// null for a guest. A session is only reused by the same person, so after signing in (or out) the next
// result starts a new session, created with the current token, and a signed-in person's results land
// in a session that belongs to their account. Earlier sessions and their results are left as they are.
const SESSION_KEY = "nuronest:assessment-session";

interface StoredSession {
  id: string;
  owner: string | null;
}

async function createSession(owner: string | null): Promise<string> {
  const res = await apiFetch("/sessions", { method: "POST" });
  if (!res.ok) throw new Error(`Could not start a session (${res.status})`);
  const { id } = (await res.json()) as { id: string };
  try {
    const stored: StoredSession = { id, owner };
    window.sessionStorage.setItem(SESSION_KEY, JSON.stringify(stored));
  } catch {
    // no-op: a new session is simply created next time
  }
  return id;
}

function existingSession(owner: string | null): string | null {
  try {
    const raw = window.sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    let stored: Partial<StoredSession>;
    try {
      stored = JSON.parse(raw) as Partial<StoredSession>;
    } catch {
      // Stored before sessions were tagged: a plain id, created by whoever was here then. Only a guest
      // may keep using it.
      return owner === null ? raw : null;
    }
    return typeof stored.id === "string" && (stored.owner ?? null) === owner ? stored.id : null;
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

/** One crisis line as the backend sends it (com.neuronest.backend.safety.CrisisResource). */
export interface ServerCrisisResource {
  name: string;
  /** One or more numbers in one string, e.g. "14416 or 1800-891-4416". */
  phone: string;
  availability: string | null;
}

/** What the backend answers when it saves a result (its AssessmentResponse). */
export interface ServerAssessmentResponse {
  id: string;
  score: number;
  severityBand: string;
  riskFlag: boolean;
  /** Present only when riskFlag is true. */
  crisisResources: ServerCrisisResource[] | null;
}

/** Saves the result and returns the backend's answer, or null when it isn't saved (SCI, or an error). */
export async function syncAssessmentToBackend(result: AnyAssessmentResult): Promise<ServerAssessmentResponse | null> {
  const itemCount = BACKEND_TYPES[result.assessmentType];
  if (!itemCount) return null;

  // Likert answers are keyed by question id 1..N; the backend wants them in order.
  const answers = result.responses as Record<number, number>;
  const responses = Array.from({ length: itemCount }, (_, i) => answers[i + 1] ?? 0);

  try {
    const owner = getCurrentUserId();
    let sessionId = existingSession(owner) ?? (await createSession(owner));
    let res = await post(sessionId, result.assessmentType, responses);

    // The stored session may belong to a different login state (e.g. the
    // user signed in or out since) — start a fresh one and retry once.
    if (res.status === 403 || res.status === 404) {
      sessionId = await createSession(owner);
      res = await post(sessionId, result.assessmentType, responses);
    }
    if (!res.ok) throw new Error(`Backend returned ${res.status}`);
    return (await res.json()) as ServerAssessmentResponse;
  } catch (error) {
    console.warn("[assessments] could not save result to the server", error);
    return null;
  }
}
