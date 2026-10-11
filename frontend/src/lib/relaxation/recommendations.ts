import type { RelaxationItem } from "@/data/relaxation";
import type {
  ActivityEntry,
  FavoriteEntry,
  NeuroSuggestion,
  RecommendationPick,
  RecommendationSet,
} from "@/lib/relaxation/activity-store";

// Pure "Recommended for you" logic: no React, no storage, catalog and clock
// passed in. use-relaxation-activity.ts wires it to the activity store.

const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

/** Less activity than this counts as an accidental click, not "opened". */
export const MIN_OPENED_SECONDS = 5;
/** Personal picks need at least this many different items opened. */
export const MIN_ITEMS_FOR_PERSONAL = 3;
/** A saved set (and a Neuro suggestion) lasts this long. */
export const RECOMMENDATION_TTL_MS = DAY_MS;
/** Items opened this recently aren't recommended again. */
const RECENT_MS = 72 * HOUR_MS;
/** Activity loses half its weight every week. */
const HALF_LIFE_DAYS = 7;
/** Saved per set: 4 shown plus spares so de-duplicating never leaves gaps. */
export const SAVED_PICKS = 6;
export const SHOWN_PICKS = 4;
export const MAX_NEURO_SHOWN = 2;
const MAX_PER_KIND = 2;

export const NEURO_REASON = "Suggested by Neuro in your chat";
export const POPULAR_REASON = "Popular pick";

export function wasOpened(entry: ActivityEntry) {
  return entry.progressSeconds >= MIN_OPENED_SECONDS;
}

function timeOf(iso: string) {
  const time = Date.parse(iso);
  return Number.isNaN(time) ? 0 : time;
}

/** Number of different items opened at least once. */
export function countOpenedItems(history: ActivityEntry[]): number {
  return new Set(history.filter(wasOpened).map((entry) => entry.itemKey)).size;
}

function openedSince(history: ActivityEntry[], itemKey: string, since: number) {
  return history.some(
    (entry) => entry.itemKey === itemKey && wasOpened(entry) && timeOf(entry.startedAt) >= since,
  );
}

// ---- Activity-based ranking ----

type Signal = { weight: number; how: "liked" | "finished" | "tried" };

export type RankContext = {
  history: ActivityEntry[];
  favorites: FavoriteEntry[];
  catalog: RelaxationItem[];
  now: number;
  /** Never recommend these (e.g. the "You were here" item, picks already shown). */
  excludeKeys: Set<string>;
};

/**
 * Scores every catalog item by how well it matches the categories and mood
 * tags the user uses most (recent activity weighs more; completed items and
 * favorites more still), skips anything opened in the last 72 hours, and
 * keeps some variety. Each pick carries a reason grounded in real activity.
 */
export function rankActivityPicks(context: RankContext, limit: number): RecommendationPick[] {
  const { history, favorites, catalog, now, excludeKeys } = context;
  const byKey = new Map(catalog.map((item) => [item.key, item]));
  const kindWeight = new Map<string, number>();
  const tagWeight = new Map<string, number>();
  const signals = new Map<string, Signal>();
  const rank: Record<Signal["how"], number> = { liked: 3, finished: 2, tried: 1 };

  function addSignal(itemKey: string, weight: number, how: Signal["how"]) {
    const item = byKey.get(itemKey);
    if (!item) return;
    kindWeight.set(item.kind, (kindWeight.get(item.kind) ?? 0) + weight);
    item.tags.forEach((tag) => tagWeight.set(tag, (tagWeight.get(tag) ?? 0) + weight));
    const current = signals.get(itemKey);
    signals.set(itemKey, {
      weight: (current?.weight ?? 0) + weight,
      how: current && rank[current.how] >= rank[how] ? current.how : how,
    });
  }

  for (const entry of history) {
    if (!wasOpened(entry)) continue;
    const ageDays = Math.max(0, now - timeOf(entry.startedAt)) / DAY_MS;
    const recency = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
    addSignal(entry.itemKey, recency * (entry.completed ? 1.5 : 1), entry.completed ? "finished" : "tried");
  }
  const favoriteKeys = new Set(favorites.map((favorite) => favorite.itemKey));
  favoriteKeys.forEach((itemKey) => addSignal(itemKey, 2, "liked"));

  const scored = catalog
    .filter((item) => !excludeKeys.has(item.key) && !openedSince(history, item.key, now - RECENT_MS))
    .map((item, index) => {
      const score =
        (kindWeight.get(item.kind) ?? 0) +
        item.tags.reduce((sum, tag) => sum + (tagWeight.get(tag) ?? 0), 0) +
        (favoriteKeys.has(item.key) ? 1.5 : 0);
      return { item, index, score };
    })
    .sort((a, b) => b.score - a.score || a.index - b.index);

  const picks: RecommendationPick[] = [];
  const perKind = new Map<string, number>();
  const reasonUses = new Map<string, number>();
  for (const { item } of scored) {
    if (picks.length >= limit) break;
    if ((perKind.get(item.kind) ?? 0) >= MAX_PER_KIND) continue;
    perKind.set(item.kind, (perKind.get(item.kind) ?? 0) + 1);
    picks.push({ itemKey: item.key, reason: reasonFor(item, favoriteKeys, signals, byKey, reasonUses) });
  }
  return picks;
}

/**
 * Names the past item this pick is most like. Similarity counts more than
 * how much that item was used, and a source already named in this set counts
 * less, so the reasons don't all point at the same thing.
 */
function reasonFor(
  item: RelaxationItem,
  favoriteKeys: Set<string>,
  signals: Map<string, Signal>,
  byKey: Map<string, RelaxationItem>,
  reasonUses: Map<string, number>,
): string {
  if (favoriteKeys.has(item.key)) return "From your favorites";

  let best: { source: RelaxationItem; signal: Signal; strength: number } | null = null;
  for (const [sourceKey, signal] of signals) {
    const source = byKey.get(sourceKey);
    if (!source || sourceKey === item.key) continue;
    const similarity =
      (source.kind === item.kind ? 1 : 0) + source.tags.filter((tag) => item.tags.includes(tag)).length;
    const strength =
      (similarity * similarity * signal.weight) / (1 + (reasonUses.get(sourceKey) ?? 0));
    if (similarity > 0 && (!best || strength > best.strength)) best = { source, signal, strength };
  }

  if (!best) return "Something new to try";
  reasonUses.set(best.source.key, (reasonUses.get(best.source.key) ?? 0) + 1);
  if (best.signal.how === "liked") return `Because you liked ${best.source.title}`;
  if (best.signal.how === "finished") return `Because you finished ${best.source.title}`;
  return `Similar to ${best.source.title}`;
}

// ---- Keeping the saved set stable ----

export function isExpired(createdAt: string, now: number) {
  return now - timeOf(createdAt) >= RECOMMENDATION_TTL_MS;
}

/**
 * Replaces only the picks the user has opened since the set was chosen; the
 * rest keep their place. Returns null when nothing needed replacing.
 */
export function replaceOpenedPicks(
  set: RecommendationSet,
  context: Omit<RankContext, "excludeKeys"> & { excludeKeys?: Set<string> },
): RecommendationSet | null {
  const since = timeOf(set.createdAt);
  const opened = set.picks.filter((pick) => openedSince(context.history, pick.itemKey, since));
  if (opened.length === 0) return null;

  const exclude = new Set([...(context.excludeKeys ?? []), ...set.picks.map((pick) => pick.itemKey)]);
  const replacements = rankActivityPicks({ ...context, excludeKeys: exclude }, opened.length);
  let next = 0;
  const picks = set.picks
    .map((pick) => (opened.includes(pick) ? replacements[next++] : pick))
    .filter((pick): pick is RecommendationPick => Boolean(pick));
  return { createdAt: set.createdAt, picks };
}

// ---- Neuro chat suggestions ----

const NEURO_RULES: { pattern: RegExp; itemKeys: string[] }[] = [
  {
    pattern: /\b(sleep|slept|insomnia|bed|bedtime|night)/,
    itemKeys: ["sleep:gentle-rain", "breathing:4-7-8-breathing", "meditation:evening-calm"],
  },
  { pattern: /\b(breath|breathe|breathing|panic)/, itemKeys: ["breathing:box-breathing", "breathing:extended-exhale"] },
  { pattern: /\b(meditat|mindful)/, itemKeys: ["meditation:mindful-breathing", "meditation:open-awareness"] },
  {
    pattern: /\b(tension|tense|tight|muscle|body)/,
    itemKeys: ["mindful-reset:progressive-muscle-relaxation", "mindful-reset:body-scan"],
  },
  {
    pattern: /\b(overwhelm|racing|anxious|ground)/,
    itemKeys: ["mindful-reset:5-4-3-2-1-grounding", "breathing:cyclic-sighing"],
  },
];
const NEURO_FALLBACK = ["breathing:box-breathing"];

/**
 * Picks one real item for a Neuro "relaxation" recommendation by looking for
 * keywords in the texts given (read here only, never stored). The first rule
 * that matches wins; within it, the first item not opened in the last 24
 * hours. No match falls back to Box Breathing.
 */
export function pickNeuroItemKey(texts: string[], history: ActivityEntry[], now: number): string {
  const haystack = texts.join(" ").toLowerCase();
  const rule = NEURO_RULES.find((candidate) => candidate.pattern.test(haystack));
  const itemKeys = rule?.itemKeys ?? NEURO_FALLBACK;
  return itemKeys.find((itemKey) => !openedSince(history, itemKey, now - DAY_MS)) ?? itemKeys[0];
}

/** Unexpired suggestions not opened since they were made, newest first. */
export function activeNeuroSuggestions(
  suggestions: NeuroSuggestion[],
  history: ActivityEntry[],
  now: number,
): NeuroSuggestion[] {
  return suggestions.filter(
    (suggestion) =>
      !isExpired(suggestion.suggestedAt, now) &&
      !openedSince(history, suggestion.itemKey, timeOf(suggestion.suggestedAt)),
  );
}

// ---- Final list ----

export type RecommendationMode = "popular" | "neuro-popular" | "personal";

export type AssembledRecommendations = {
  mode: RecommendationMode;
  picks: { itemKey: string; reason?: string }[];
};

/**
 * Up to 2 Neuro suggestions first, then activity picks (enough activity) or
 * popular picks (not enough), with no duplicates and never the item shown in
 * "You were here".
 */
export function assembleRecommendations(input: {
  openedItemCount: number;
  neuroKeys: string[];
  savedPicks: RecommendationPick[];
  popularKeys: string[];
  lastOpenedKey: string | null;
}): AssembledRecommendations {
  const used = new Set<string>(input.lastOpenedKey ? [input.lastOpenedKey] : []);
  const picks: AssembledRecommendations["picks"] = [];

  function add(itemKey: string, reason?: string) {
    if (picks.length >= SHOWN_PICKS || used.has(itemKey)) return;
    used.add(itemKey);
    picks.push({ itemKey, reason });
  }

  input.neuroKeys.slice(0, MAX_NEURO_SHOWN).forEach((itemKey) => add(itemKey, NEURO_REASON));
  const hasNeuro = picks.length > 0;

  if (input.openedItemCount >= MIN_ITEMS_FOR_PERSONAL) {
    input.savedPicks.forEach((pick) => add(pick.itemKey, pick.reason));
    return { mode: "personal", picks };
  }
  if (!hasNeuro) {
    input.popularKeys.forEach((itemKey) => add(itemKey));
    return { mode: "popular", picks };
  }
  input.popularKeys.forEach((itemKey) => add(itemKey, POPULAR_REASON));
  return { mode: "neuro-popular", picks };
}
