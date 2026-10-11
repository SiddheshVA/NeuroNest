import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { useAuth } from "@clerk/clerk-react";

import {
  popularPickItems,
  quickResetItems,
  relaxationCatalog,
  type RelaxationItem,
  type RelaxationItemKind,
} from "@/data/relaxation";
import {
  GUEST_OWNER,
  addNeuroSuggestion,
  clearRelaxationData,
  completeActivity,
  getFavorites,
  getHistory,
  getNeuroSuggestions,
  getRecommendationSet,
  saveRecommendationSet,
  startActivity,
  subscribeRelaxationActivity,
  toggleFavorite,
  updateActivity,
  type ActivityEntry,
  type ActivityOwner,
  type FavoriteEntry,
  type NeuroSuggestion,
  type RecommendationSet,
} from "@/lib/relaxation/activity-store";
import {
  SAVED_PICKS,
  activeNeuroSuggestions,
  assembleRecommendations,
  countOpenedItems,
  isExpired,
  MIN_ITEMS_FOR_PERSONAL,
  pickNeuroItemKey,
  rankActivityPicks,
  replaceOpenedPicks,
  wasOpened,
  type RecommendationMode,
} from "@/lib/relaxation/recommendations";

// React bindings for activity-store. Components use these hooks; only the
// store touches storage.

const SAVE_INTERVAL_MS = 5000;

const NO_HISTORY: ActivityEntry[] = [];
const NO_FAVORITES: FavoriteEntry[] = [];
const NO_SUGGESTIONS: NeuroSuggestion[] = [];
const catalogByKey = new Map(relaxationCatalog.map((item) => [item.key, item]));

/** Current storage bucket; null until Clerk knows whether someone is signed in. */
export function useActivityOwner(): ActivityOwner | null {
  const { isLoaded, userId } = useAuth();
  if (!isLoaded) return null;
  return userId ?? GUEST_OWNER;
}

function useHistory(owner: ActivityOwner | null): ActivityEntry[] {
  return useSyncExternalStore(
    subscribeRelaxationActivity,
    () => (owner ? getHistory(owner) : NO_HISTORY),
    () => NO_HISTORY,
  );
}

function useFavoriteEntries(owner: ActivityOwner | null): FavoriteEntry[] {
  return useSyncExternalStore(
    subscribeRelaxationActivity,
    () => (owner ? getFavorites(owner) : NO_FAVORITES),
    () => NO_FAVORITES,
  );
}

function isUnfinished(entry: ActivityEntry) {
  return wasOpened(entry) && !entry.completed && entry.progressSeconds < entry.durationSeconds;
}

// ---- Favorites ----

/** Saved items, newest first; items no longer in the catalog are skipped. */
export function useFavoriteItems(): RelaxationItem[] {
  const favorites = useFavoriteEntries(useActivityOwner());
  return useMemo(
    () =>
      favorites
        .map((favorite) => catalogByKey.get(favorite.itemKey))
        .filter((item): item is RelaxationItem => Boolean(item)),
    [favorites],
  );
}

export function useFavorite(itemKey: string) {
  const owner = useActivityOwner();
  const favorites = useFavoriteEntries(owner);
  const isFavorite = favorites.some((favorite) => favorite.itemKey === itemKey);
  const toggle = useCallback(() => {
    if (owner) toggleFavorite(owner, itemKey);
  }, [owner, itemKey]);
  return { isFavorite, toggle, isReady: owner !== null };
}

// ---- Last opened / resume ----

export type LastOpenedEntry = { item: RelaxationItem; entry: ActivityEntry };

/**
 * The last thing opened in the Relaxation Hub, of any type (sleep sounds
 * included, finished or not). Entries under 5 seconds are skipped as
 * accidental clicks. Null when nothing has been opened.
 */
export function useLastOpenedItem(): LastOpenedEntry | null {
  const history = useHistory(useActivityOwner());
  return useMemo(() => findLastOpened(history), [history]);
}

function findLastOpened(history: ActivityEntry[]): LastOpenedEntry | null {
  for (const entry of history) {
    if (!wasOpened(entry)) continue;
    const item = catalogByKey.get(entry.itemKey);
    if (item) return { item, entry };
  }
  return null;
}

/** Saved position (seconds) of this item's latest attempt, if unfinished. */
export function useResumePosition(itemKey: string): number | null {
  const history = useHistory(useActivityOwner());
  return useMemo(() => {
    const latest = history.find((entry) => entry.itemKey === itemKey);
    return latest && isUnfinished(latest) ? latest.progressSeconds : null;
  }, [history, itemKey]);
}

// ---- Recommendations ----

function useRecommendationSet(owner: ActivityOwner | null): RecommendationSet | null {
  return useSyncExternalStore(
    subscribeRelaxationActivity,
    () => (owner ? getRecommendationSet(owner) : null),
    () => null,
  );
}

function useNeuroSuggestionEntries(owner: ActivityOwner | null): NeuroSuggestion[] {
  return useSyncExternalStore(
    subscribeRelaxationActivity,
    () => (owner ? getNeuroSuggestions(owner) : NO_SUGGESTIONS),
    () => NO_SUGGESTIONS,
  );
}

const popularKeys = [...popularPickItems, ...quickResetItems].map((item) => item.key);

export type RecommendedEntry = { item: RelaxationItem; reason?: string };

/**
 * "Recommended for you": up to 2 Neuro chat suggestions, then activity-based
 * picks once at least 3 items have been opened (popular picks before that).
 * The activity set is saved for 24 hours; opening one of its items replaces
 * only that one.
 */
export function useRecommendations(): { mode: RecommendationMode; entries: RecommendedEntry[] } {
  const owner = useActivityOwner();
  const history = useHistory(owner);
  const favorites = useFavoriteEntries(owner);
  const savedSet = useRecommendationSet(owner);
  const suggestions = useNeuroSuggestionEntries(owner);
  // Clock for expiry checks while rendering; refreshed whenever the hub remounts.
  const [now] = useState(() => Date.now());

  const lastOpenedKey = useMemo(() => findLastOpened(history)?.item.key ?? null, [history]);
  const openedItemCount = useMemo(() => countOpenedItems(history), [history]);
  const neuroKeys = useMemo(
    () =>
      activeNeuroSuggestions(suggestions, history, now)
        .map((suggestion) => suggestion.itemKey)
        .filter((itemKey) => catalogByKey.has(itemKey)),
    [suggestions, history, now],
  );

  // Keep the saved set fresh: a new one when missing or older than 24 hours,
  // otherwise swap out only the picks opened since it was chosen.
  useEffect(() => {
    if (!owner || openedItemCount < MIN_ITEMS_FOR_PERSONAL) return;
    const currentTime = Date.now();
    const excludeKeys = new Set([...(lastOpenedKey ? [lastOpenedKey] : []), ...neuroKeys]);
    const context = { history, favorites, catalog: relaxationCatalog, now: currentTime, excludeKeys };

    if (!savedSet || isExpired(savedSet.createdAt, currentTime)) {
      saveRecommendationSet(owner, {
        createdAt: new Date(currentTime).toISOString(),
        picks: rankActivityPicks(context, SAVED_PICKS),
      });
      return;
    }
    const replaced = replaceOpenedPicks(savedSet, context);
    if (replaced) saveRecommendationSet(owner, replaced);
  }, [owner, openedItemCount, history, favorites, savedSet, lastOpenedKey, neuroKeys]);

  return useMemo(() => {
    const assembled = assembleRecommendations({
      openedItemCount,
      neuroKeys,
      savedPicks: savedSet?.picks ?? [],
      popularKeys,
      lastOpenedKey,
    });
    return {
      mode: assembled.mode,
      entries: assembled.picks
        .map((pick): RecommendedEntry | null => {
          const item = catalogByKey.get(pick.itemKey);
          return item ? { item, reason: pick.reason } : null;
        })
        .filter((entry): entry is RecommendedEntry => entry !== null),
    };
  }, [openedItemCount, neuroKeys, savedSet, lastOpenedKey]);
}

/**
 * For the Neuro chat: turns a "relaxation" recommendation into one real item
 * and saves it as a suggestion for 24 hours. The texts are read only to choose
 * the item; only its id, type and the time are stored.
 */
export function useSaveNeuroSuggestion() {
  const owner = useActivityOwner();
  return useCallback(
    (texts: string[]) => {
      if (!owner) return;
      const itemKey = pickNeuroItemKey(texts, getHistory(owner), Date.now());
      const item = catalogByKey.get(itemKey);
      if (item) addNeuroSuggestion(owner, { itemKey, kind: item.kind });
    },
    [owner],
  );
}

// ---- Clearing ----

export function useClearRelaxationData() {
  const owner = useActivityOwner();
  return {
    clear: useCallback(() => {
      if (owner) clearRelaxationData(owner);
    }, [owner]),
    isGuest: owner === GUEST_OWNER,
    isReady: owner !== null,
  };
}

// ---- Tracking ----

/**
 * Records one timed session (breathing, meditation, Mindful Reset). Call it
 * from the component that owns the session: the entry starts when that
 * component mounts, progress is saved at most every few seconds and on
 * pause, unmount and page close, and it's marked complete when the session is.
 */
export function useTrackActivity({
  itemKey,
  kind,
  elapsedSeconds,
  totalSeconds,
  isActive,
  isComplete,
  startAtSeconds = 0,
}: {
  itemKey: string;
  kind: Exclude<RelaxationItemKind, "sleep">;
  elapsedSeconds: number;
  totalSeconds: number;
  isActive: boolean;
  isComplete: boolean;
  startAtSeconds?: number;
}) {
  const owner = useActivityOwner();
  const entryRef = useRef<{ owner: ActivityOwner; id: string } | null>(null);
  const progressRef = useRef({ elapsedSeconds, totalSeconds });
  const lastSavedAtRef = useRef(0);
  const completedRef = useRef(false);

  useEffect(() => {
    progressRef.current = { elapsedSeconds, totalSeconds };
  });

  useEffect(() => {
    if (!owner || entryRef.current) return;
    entryRef.current = {
      owner,
      id: startActivity(owner, {
        itemKey,
        kind,
        durationSeconds: totalSeconds,
        progressSeconds: startAtSeconds,
      }),
    };
    lastSavedAtRef.current = Date.now();
  }, [owner, itemKey, kind, totalSeconds, startAtSeconds]);

  const flush = useCallback(() => {
    const entry = entryRef.current;
    if (!entry || completedRef.current) return;
    updateActivity(entry.owner, entry.id, {
      progressSeconds: progressRef.current.elapsedSeconds,
      durationSeconds: progressRef.current.totalSeconds,
    });
    lastSavedAtRef.current = Date.now();
  }, []);

  useEffect(() => {
    if (Date.now() - lastSavedAtRef.current >= SAVE_INTERVAL_MS) flush();
  }, [elapsedSeconds, flush]);

  useEffect(() => {
    if (!isActive) flush();
  }, [isActive, flush]);

  useEffect(() => {
    const entry = entryRef.current;
    if (!isComplete || !entry || completedRef.current) return;
    completedRef.current = true;
    completeActivity(entry.owner, entry.id, progressRef.current.totalSeconds);
  }, [isComplete]);

  useEffect(() => {
    window.addEventListener("pagehide", flush);
    return () => {
      window.removeEventListener("pagehide", flush);
      flush();
    };
  }, [flush]);
}

/**
 * Records listening to a looping sleep sound. An entry starts when a sound
 * starts playing; only seconds actually playing count, measured against the
 * sleep timer, and it's complete once the timer's worth has been heard.
 * Switching sounds starts a new entry.
 */
export function useTrackListening({
  itemKey,
  isPlaying,
  timerSeconds,
}: {
  itemKey: string;
  isPlaying: boolean;
  timerSeconds: number;
}) {
  const owner = useActivityOwner();
  const timerRef = useRef(timerSeconds);
  const entryRef = useRef<{
    owner: ActivityOwner;
    itemKey: string;
    id: string;
    listenedSeconds: number;
    savedSeconds: number;
    completed: boolean;
  } | null>(null);

  useEffect(() => {
    timerRef.current = timerSeconds;
  }, [timerSeconds]);

  useEffect(() => {
    if (!owner || !isPlaying) return;

    const current = entryRef.current;
    if (!current || current.completed || current.itemKey !== itemKey || current.owner !== owner) {
      entryRef.current = {
        owner,
        itemKey,
        id: startActivity(owner, { itemKey, kind: "sleep", durationSeconds: timerRef.current }),
        listenedSeconds: 0,
        savedSeconds: 0,
        completed: false,
      };
    }

    function save() {
      const entry = entryRef.current;
      if (!entry || entry.completed) return;
      updateActivity(entry.owner, entry.id, {
        progressSeconds: entry.listenedSeconds,
        durationSeconds: timerRef.current,
      });
      entry.savedSeconds = entry.listenedSeconds;
    }

    const interval = window.setInterval(() => {
      const entry = entryRef.current;
      if (!entry || entry.completed) return;
      entry.listenedSeconds += 1;
      if (entry.listenedSeconds >= timerRef.current) {
        entry.completed = true;
        completeActivity(entry.owner, entry.id, entry.listenedSeconds);
      } else if (entry.listenedSeconds - entry.savedSeconds >= SAVE_INTERVAL_MS / 1000) {
        save();
      }
    }, 1000);

    window.addEventListener("pagehide", save);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener("pagehide", save);
      save();
    };
  }, [owner, isPlaying, itemKey]);
}
