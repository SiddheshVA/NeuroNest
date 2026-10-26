import type { AnyAssessmentResult, AssessmentType } from "./types";
import { syncAssessmentToBackend, type ServerAssessmentResponse } from "./sync";

// The latest result per type is kept in sessionStorage so the result page
// can render it instantly; saveAssessmentResult() also sends it to the
// Spring Boot backend (see sync.ts) so it's persisted for the dashboard.
const STORAGE_PREFIX = "nuronest:assessment:result:";
// The backend's answer for the latest result per type (its riskFlag and crisis lines), kept beside it.
const SERVER_PREFIX = "nuronest:assessment:server:";

/**
 * Where the backend's answer for the latest result stands: still on its way, saved (with the
 * backend's riskFlag and crisis lines), or unavailable (SCI, offline, an error, or a page reloaded
 * before it arrived).
 */
export type ServerResultState =
  | { status: "pending" }
  | { status: "saved"; response: ServerAssessmentResponse }
  | { status: "unavailable" };

const UNAVAILABLE: ServerResultState = { status: "unavailable" };
const PENDING: ServerResultState = { status: "pending" };
const serverCache = new Map<AssessmentType, ServerResultState>();

// Cached per type so loadAssessmentResult() returns a stable reference across
// calls — required for safe use with useSyncExternalStore (a new object every
// call would look like a change on every render and loop).
const cache = new Map<AssessmentType, AnyAssessmentResult | null>();
const listeners = new Set<() => void>();

function notifyListeners(): void {
  listeners.forEach((listener) => listener());
}

export function saveAssessmentResult(result: AnyAssessmentResult): void {
  if (typeof window === "undefined") return;
  const type = result.assessmentType;
  removeServerResult(type);
  serverCache.set(type, PENDING);
  void syncAssessmentToBackend(result).then((response) => {
    // A newer result (or a retake) replaced this one in the meantime: its answer no longer applies.
    if (serverCache.get(type) !== PENDING || loadAssessmentResult(type)?.completedAt !== result.completedAt) return;
    if (response) {
      try {
        window.sessionStorage.setItem(SERVER_PREFIX + type, JSON.stringify(response));
      } catch {
        // no-op: it is still kept for this page below
      }
      serverCache.set(type, { status: "saved", response });
    } else {
      serverCache.set(type, UNAVAILABLE);
    }
    notifyListeners();
  });
  try {
    window.sessionStorage.setItem(STORAGE_PREFIX + result.assessmentType, JSON.stringify(result));
    cache.set(result.assessmentType, result);
    notifyListeners();
  } catch {
    // Ignore storage failures (e.g. private browsing) — the result page will
    // fall back to its empty state.
  }
}

export function loadAssessmentResult(type: AssessmentType): AnyAssessmentResult | null {
  if (typeof window === "undefined") return null;
  if (cache.has(type)) return cache.get(type) ?? null;
  let result: AnyAssessmentResult | null;
  try {
    const raw = window.sessionStorage.getItem(STORAGE_PREFIX + type);
    result = raw ? (JSON.parse(raw) as AnyAssessmentResult) : null;
  } catch {
    result = null;
  }
  cache.set(type, result);
  return result;
}

export function clearAssessmentResult(type: AssessmentType): void {
  if (typeof window !== "undefined") {
    try {
      window.sessionStorage.removeItem(STORAGE_PREFIX + type);
    } catch {
      // no-op
    }
  }
  cache.set(type, null);
  removeServerResult(type);
  serverCache.set(type, UNAVAILABLE);
  notifyListeners();
}

function removeServerResult(type: AssessmentType): void {
  try {
    window.sessionStorage.removeItem(SERVER_PREFIX + type);
  } catch {
    // no-op
  }
}

/** The backend's answer for the latest result of this type; a stable reference for useSyncExternalStore. */
export function loadServerResult(type: AssessmentType): ServerResultState {
  if (typeof window === "undefined") return UNAVAILABLE;
  const cached = serverCache.get(type);
  if (cached) return cached;
  let state: ServerResultState = UNAVAILABLE;
  try {
    const raw = window.sessionStorage.getItem(SERVER_PREFIX + type);
    if (raw) state = { status: "saved", response: JSON.parse(raw) as ServerAssessmentResponse };
  } catch {
    state = UNAVAILABLE;
  }
  serverCache.set(type, state);
  return state;
}

/** Stops waiting for the backend's answer (the result page gives up after a few seconds). */
export function giveUpOnServerResult(type: AssessmentType): void {
  if (serverCache.get(type) !== PENDING) return;
  serverCache.set(type, UNAVAILABLE);
  notifyListeners();
}

/** Subscribes to result changes; for use with useSyncExternalStore. */
export function subscribeAssessmentResults(callback: () => void): () => void {
  listeners.add(callback);
  return () => listeners.delete(callback);
}
