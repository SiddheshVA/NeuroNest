import type { RelaxationItemKind } from "@/data/relaxation";

// The ONLY module that reads or writes Relaxation Hub activity: play history,
// favorites, the saved "Recommended for you" set and Neuro chat suggestions.
// Data lives in localStorage, namespaced per owner: the signed-in Clerk user
// id, or "guest" when signed out. Every storage access is wrapped so blocked,
// full or corrupt storage just falls back to the in-memory copy for this
// visit instead of breaking the page.
//
// Backend sync later: every change already funnels through notifyChange(),
// the same way saveAssessmentResult() hands off to syncAssessmentToBackend()
// in lib/assessments. A future sync module plugs in there; components only
// ever call the functions exported below, so they won't need to change.

const KEY_PREFIX = "nuronest:relaxation:v1:";
const MAX_HISTORY_ENTRIES = 50;
const MAX_NEURO_SUGGESTIONS = 5;

export const GUEST_OWNER = "guest";

/** Clerk user id when signed in, GUEST_OWNER otherwise. */
export type ActivityOwner = string;

export type ActivityEntry = {
  id: string;
  /** Key from the shared relaxation catalog, e.g. "breathing:box-breathing". */
  itemKey: string;
  kind: RelaxationItemKind;
  startedAt: string;
  updatedAt: string;
  progressSeconds: number;
  durationSeconds: number;
  completed: boolean;
};

export type FavoriteEntry = {
  itemKey: string;
  addedAt: string;
};

export type RecommendationPick = {
  itemKey: string;
  reason: string;
};

/** The saved "Recommended for you" set, kept stable for a while once chosen. */
export type RecommendationSet = {
  createdAt: string;
  picks: RecommendationPick[];
};

/** An item Neuro pointed to in chat. Never holds any chat text. */
export type NeuroSuggestion = {
  itemKey: string;
  kind: RelaxationItemKind;
  suggestedAt: string;
};

type OwnerData = {
  history: ActivityEntry[];
  favorites: FavoriteEntry[];
  recommendations: RecommendationSet | null;
  neuroSuggestions: NeuroSuggestion[];
};

export type ActivityChange =
  | { type: "activity-started" | "activity-updated" | "activity-completed"; owner: ActivityOwner; entry: ActivityEntry }
  | { type: "favorite-added" | "favorite-removed"; owner: ActivityOwner; itemKey: string }
  | { type: "recommendations-saved"; owner: ActivityOwner; set: RecommendationSet }
  | { type: "neuro-suggestion-added"; owner: ActivityOwner; suggestion: NeuroSuggestion }
  | { type: "cleared"; owner: ActivityOwner };

type Part = "history" | "favorites" | "recommendations" | "neuro-suggestions";

const PARTS: Part[] = ["history", "favorites", "recommendations", "neuro-suggestions"];
const EMPTY: OwnerData = { history: [], favorites: [], recommendations: null, neuroSuggestions: [] };
const cache = new Map<ActivityOwner, OwnerData>();
const listeners = new Set<() => void>();

function storageKey(owner: ActivityOwner, part: Part) {
  return `${KEY_PREFIX}${owner}:${part}`;
}

function readJson(key: string): unknown {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as unknown) : null;
  } catch {
    return null;
  }
}

function readArray(key: string): unknown[] {
  const value = readJson(key);
  return Array.isArray(value) ? value : [];
}

function isActivityEntry(value: unknown): value is ActivityEntry {
  const entry = value as ActivityEntry;
  return (
    typeof entry === "object" &&
    entry !== null &&
    typeof entry.id === "string" &&
    typeof entry.itemKey === "string" &&
    typeof entry.kind === "string" &&
    typeof entry.startedAt === "string" &&
    typeof entry.updatedAt === "string" &&
    typeof entry.progressSeconds === "number" &&
    typeof entry.durationSeconds === "number" &&
    typeof entry.completed === "boolean"
  );
}

function isFavoriteEntry(value: unknown): value is FavoriteEntry {
  const entry = value as FavoriteEntry;
  return (
    typeof entry === "object" &&
    entry !== null &&
    typeof entry.itemKey === "string" &&
    typeof entry.addedAt === "string"
  );
}

function isRecommendationSet(value: unknown): value is RecommendationSet {
  const set = value as RecommendationSet;
  return (
    typeof set === "object" &&
    set !== null &&
    typeof set.createdAt === "string" &&
    Array.isArray(set.picks) &&
    set.picks.every(
      (pick) =>
        typeof pick === "object" &&
        pick !== null &&
        typeof pick.itemKey === "string" &&
        typeof pick.reason === "string",
    )
  );
}

function isNeuroSuggestion(value: unknown): value is NeuroSuggestion {
  const suggestion = value as NeuroSuggestion;
  return (
    typeof suggestion === "object" &&
    suggestion !== null &&
    typeof suggestion.itemKey === "string" &&
    typeof suggestion.kind === "string" &&
    typeof suggestion.suggestedAt === "string"
  );
}

function load(owner: ActivityOwner): OwnerData {
  if (typeof window === "undefined") return EMPTY;
  const cached = cache.get(owner);
  if (cached) return cached;

  const recommendations = readJson(storageKey(owner, "recommendations"));
  const data: OwnerData = {
    history: readArray(storageKey(owner, "history")).filter(isActivityEntry),
    favorites: readArray(storageKey(owner, "favorites")).filter(isFavoriteEntry),
    recommendations: isRecommendationSet(recommendations) ? recommendations : null,
    neuroSuggestions: readArray(storageKey(owner, "neuro-suggestions")).filter(isNeuroSuggestion),
  };
  cache.set(owner, data);
  return data;
}

function writeValue(key: string, value: unknown) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage blocked or full: the in-memory copy still serves this visit.
  }
}

// New values on every change so useSyncExternalStore snapshots change identity.
function save(owner: ActivityOwner, next: OwnerData, change: ActivityChange) {
  const previous = load(owner);
  cache.set(owner, next);
  if (next.history !== previous.history) writeValue(storageKey(owner, "history"), next.history);
  if (next.favorites !== previous.favorites) writeValue(storageKey(owner, "favorites"), next.favorites);
  if (next.recommendations !== previous.recommendations) {
    writeValue(storageKey(owner, "recommendations"), next.recommendations);
  }
  if (next.neuroSuggestions !== previous.neuroSuggestions) {
    writeValue(storageKey(owner, "neuro-suggestions"), next.neuroSuggestions);
  }
  notifyChange(change);
}

function notifyChange(_change: ActivityChange) {
  // Sync hook: a future backend sync receives _change here.
  listeners.forEach((listener) => listener());
}

function createId() {
  try {
    return crypto.randomUUID();
  } catch {
    return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
}

// ---- Reads ----

/** Newest first. Stable reference until the owner's history changes. */
export function getHistory(owner: ActivityOwner): ActivityEntry[] {
  return load(owner).history;
}

/** Newest first. Stable reference until the owner's favorites change. */
export function getFavorites(owner: ActivityOwner): FavoriteEntry[] {
  return load(owner).favorites;
}

export function isFavorite(owner: ActivityOwner, itemKey: string): boolean {
  return load(owner).favorites.some((favorite) => favorite.itemKey === itemKey);
}

/** Stable reference until it's replaced. */
export function getRecommendationSet(owner: ActivityOwner): RecommendationSet | null {
  return load(owner).recommendations;
}

/** Newest first. Stable reference until a suggestion is added. */
export function getNeuroSuggestions(owner: ActivityOwner): NeuroSuggestion[] {
  return load(owner).neuroSuggestions;
}

// ---- Play tracking ----

export function startActivity(
  owner: ActivityOwner,
  input: { itemKey: string; kind: RelaxationItemKind; durationSeconds: number; progressSeconds?: number },
): string {
  const now = new Date().toISOString();
  const entry: ActivityEntry = {
    id: createId(),
    itemKey: input.itemKey,
    kind: input.kind,
    startedAt: now,
    updatedAt: now,
    progressSeconds: Math.max(0, Math.round(input.progressSeconds ?? 0)),
    durationSeconds: Math.max(0, Math.round(input.durationSeconds)),
    completed: false,
  };
  const data = load(owner);
  save(
    owner,
    { ...data, history: [entry, ...data.history].slice(0, MAX_HISTORY_ENTRIES) },
    { type: "activity-started", owner, entry },
  );
  return entry.id;
}

function patchActivity(
  owner: ActivityOwner,
  id: string,
  patch: Partial<Pick<ActivityEntry, "progressSeconds" | "durationSeconds" | "completed">>,
  type: "activity-updated" | "activity-completed",
) {
  const data = load(owner);
  const index = data.history.findIndex((entry) => entry.id === id);
  if (index === -1) return;

  const current = data.history[index];
  const updated: ActivityEntry = {
    ...current,
    progressSeconds:
      patch.progressSeconds === undefined
        ? current.progressSeconds
        : Math.max(0, Math.round(patch.progressSeconds)),
    durationSeconds:
      patch.durationSeconds === undefined
        ? current.durationSeconds
        : Math.max(0, Math.round(patch.durationSeconds)),
    completed: patch.completed ?? current.completed,
    updatedAt: new Date().toISOString(),
  };
  if (
    updated.progressSeconds === current.progressSeconds &&
    updated.durationSeconds === current.durationSeconds &&
    updated.completed === current.completed
  ) {
    return;
  }

  const history = [...data.history];
  history[index] = updated;
  save(owner, { ...data, history }, { type, owner, entry: updated });
}

export function updateActivity(
  owner: ActivityOwner,
  id: string,
  progress: { progressSeconds: number; durationSeconds?: number },
) {
  patchActivity(owner, id, progress, "activity-updated");
}

export function completeActivity(owner: ActivityOwner, id: string, progressSeconds?: number) {
  patchActivity(owner, id, { progressSeconds, completed: true }, "activity-completed");
}

// ---- Favorites ----

/** Returns whether the item is a favorite after the toggle. */
export function toggleFavorite(owner: ActivityOwner, itemKey: string): boolean {
  const data = load(owner);
  if (data.favorites.some((favorite) => favorite.itemKey === itemKey)) {
    save(
      owner,
      { ...data, favorites: data.favorites.filter((favorite) => favorite.itemKey !== itemKey) },
      { type: "favorite-removed", owner, itemKey },
    );
    return false;
  }
  save(
    owner,
    { ...data, favorites: [{ itemKey, addedAt: new Date().toISOString() }, ...data.favorites] },
    { type: "favorite-added", owner, itemKey },
  );
  return true;
}

// ---- Recommendations ----

export function saveRecommendationSet(owner: ActivityOwner, set: RecommendationSet) {
  const data = load(owner);
  save(owner, { ...data, recommendations: set }, { type: "recommendations-saved", owner, set });
}

/** Saves only the item id, type and time. The same item again just refreshes its time. */
export function addNeuroSuggestion(
  owner: ActivityOwner,
  input: { itemKey: string; kind: RelaxationItemKind },
) {
  const suggestion: NeuroSuggestion = {
    itemKey: input.itemKey,
    kind: input.kind,
    suggestedAt: new Date().toISOString(),
  };
  const data = load(owner);
  const neuroSuggestions = [
    suggestion,
    ...data.neuroSuggestions.filter((existing) => existing.itemKey !== input.itemKey),
  ].slice(0, MAX_NEURO_SUGGESTIONS);
  save(owner, { ...data, neuroSuggestions }, { type: "neuro-suggestion-added", owner, suggestion });
}

// ---- Clearing ----

/** Removes all relaxation data (history, favorites, recommendations, Neuro suggestions) for this owner only. */
export function clearRelaxationData(owner: ActivityOwner) {
  if (typeof window !== "undefined") {
    try {
      PARTS.forEach((part) => window.localStorage.removeItem(storageKey(owner, part)));
    } catch {
      // no-op: the in-memory copy is cleared below either way
    }
  }
  cache.set(owner, { history: [], favorites: [], recommendations: null, neuroSuggestions: [] });
  notifyChange({ type: "cleared", owner });
}

// ---- Subscriptions (for useSyncExternalStore) ----

export function subscribeRelaxationActivity(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// Another tab changed our data: drop the cached copy so the next read reloads it.
if (typeof window !== "undefined") {
  window.addEventListener("storage", (event) => {
    if (event.key === null) {
      cache.clear();
    } else if (event.key.startsWith(KEY_PREFIX)) {
      const owner = event.key
        .slice(KEY_PREFIX.length)
        .replace(/:(history|favorites|recommendations|neuro-suggestions)$/, "");
      cache.delete(owner);
    } else {
      return;
    }
    listeners.forEach((listener) => listener());
  });
}
