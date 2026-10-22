import { useCallback, useEffect, useState } from "react";
import { BackLink } from "@/components/navigation/back-link";
import { Brain, Loader2, ShieldCheck, Trash2 } from "lucide-react";

import { Container } from "@/components/layout/container";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Switch } from "@/components/ui/switch";
import {
  MemoryApiError,
  clearMemories,
  deleteMemory,
  getMemorySettings,
  listMemories,
  updateMemorySettings,
  type SavedMemory,
} from "@/lib/memory-client";

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof MemoryApiError ? error.message : fallback;
}

export default function MemoryPage() {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [isSavingSetting, setIsSavingSetting] = useState(false);

  const [memories, setMemories] = useState<SavedMemory[]>([]);
  const [isLoadingMemories, setIsLoadingMemories] = useState(true);
  const [memoriesError, setMemoriesError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const [confirmOpen, setConfirmOpen] = useState(false);
  const [isClearing, setIsClearing] = useState(false);

  const loadMemories = useCallback(async () => {
    setIsLoadingMemories(true);
    setMemoriesError(null);
    try {
      setMemories(await listMemories());
    } catch (error) {
      setMemoriesError(errorMessage(error, "Couldn't load your saved memories."));
    } finally {
      setIsLoadingMemories(false);
    }
  }, []);

  // Mount-time fetch: every setState happens inside a callback, as in NuroApp.
  useEffect(() => {
    let cancelled = false;

    getMemorySettings()
      .then((settings) => {
        if (!cancelled) setEnabled(settings.enabled);
      })
      .catch((error: unknown) => {
        if (!cancelled) setSettingsError(errorMessage(error, "Couldn't load your memory setting."));
      });

    listMemories()
      .then((items) => {
        if (!cancelled) setMemories(items);
      })
      .catch((error: unknown) => {
        if (!cancelled) setMemoriesError(errorMessage(error, "Couldn't load your saved memories."));
      })
      .finally(() => {
        if (!cancelled) setIsLoadingMemories(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const handleToggle = async (next: boolean) => {
    const previous = enabled;
    setEnabled(next);
    setSettingsError(null);
    setIsSavingSetting(true);
    try {
      const saved = await updateMemorySettings(next);
      setEnabled(saved.enabled);
    } catch (error) {
      setEnabled(previous);
      setSettingsError(errorMessage(error, "Couldn't save that change. Please try again."));
    } finally {
      setIsSavingSetting(false);
    }
  };

  const handleDelete = async (id: string) => {
    setDeletingId(id);
    setActionError(null);
    try {
      await deleteMemory(id);
      setMemories((prev) => prev.filter((m) => m.id !== id));
    } catch (error) {
      if (error instanceof MemoryApiError && error.status === 404) {
        // Already gone — just drop it from the list.
        setMemories((prev) => prev.filter((m) => m.id !== id));
      } else {
        setActionError(errorMessage(error, "Couldn't delete that memory. Please try again."));
      }
    } finally {
      setDeletingId(null);
    }
  };

  const handleClearAll = async () => {
    setIsClearing(true);
    setActionError(null);
    try {
      await clearMemories();
      setMemories([]);
      setConfirmOpen(false);
    } catch (error) {
      setConfirmOpen(false);
      setActionError(errorMessage(error, "Couldn't clear your memories. Please try again."));
    } finally {
      setIsClearing(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#FBF9F4] text-[#1C352D]">
      <Container className="py-10 sm:py-14">
        <div className="mx-auto flex max-w-3xl flex-col gap-8">
          <header>
            <BackLink />
            <p className="mt-4 text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">Settings</p>
            <h1 className="mt-2 font-heading text-3xl font-normal sm:text-4xl">Memory</h1>
            <p className="mt-2 text-sm text-[#4A5750]">
              Neuro can remember key things you share — like your goals or what helps you — to support you
              better over time.
            </p>
          </header>

          <section className="rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <label htmlFor="memory-enabled" className="text-base font-semibold">
                  Let Neuro remember things I share
                </label>
                <p className="mt-1 text-sm text-[#4A5750]">
                  When this is off, Neuro won&apos;t save anything new or use what&apos;s already saved.
                </p>
              </div>
              {enabled === null && !settingsError ? (
                <Loader2 className="mt-1 size-5 shrink-0 animate-spin text-[#4A5750]" aria-label="Loading" />
              ) : (
                <Switch
                  id="memory-enabled"
                  className="mt-1"
                  checked={enabled ?? false}
                  disabled={enabled === null || isSavingSetting}
                  onCheckedChange={(next) => void handleToggle(next)}
                />
              )}
            </div>
            {settingsError ? <p className="mt-3 text-sm text-red-700">{settingsError}</p> : null}
            <p className="mt-5 flex items-center gap-2 rounded-2xl bg-[#FBF9F4] px-4 py-3 text-sm text-[#4A5750]">
              <ShieldCheck className="size-4 shrink-0 text-[#233E33]" aria-hidden="true" />
              Neuro never saves anything from crisis conversations.
            </p>
          </section>

          <section className="rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8">
            <div className="mb-5 flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <Brain className="size-5 text-[#233E33]" aria-hidden="true" />
                <h2 className="text-lg font-semibold">What Neuro remembers</h2>
              </div>
              {memories.length > 0 ? (
                <Button variant="destructive" size="sm" onClick={() => setConfirmOpen(true)}>
                  Clear all memories
                </Button>
              ) : null}
            </div>

            {actionError ? (
              <div className="mb-4 rounded-2xl border border-red-200 bg-red-50 p-3 text-sm text-red-800">
                {actionError}
              </div>
            ) : null}

            {isLoadingMemories ? (
              <div className="flex flex-col gap-2">
                {[0, 1, 2].map((i) => (
                  <div
                    key={i}
                    className="h-12 animate-pulse rounded-2xl bg-[#FBF9F4]"
                    style={{ opacity: 1 - i * 0.2 }}
                  />
                ))}
              </div>
            ) : memoriesError ? (
              <div className="flex flex-col items-start gap-2 text-sm text-[#4A5750]">
                <span>{memoriesError}</span>
                <button
                  type="button"
                  onClick={() => void loadMemories()}
                  className="font-medium text-[#233E33] hover:underline"
                >
                  Try again
                </button>
              </div>
            ) : memories.length === 0 ? (
              <p className="text-sm text-[#4A5750]">Nothing saved yet.</p>
            ) : (
              <ul className="flex flex-col divide-y divide-[#EEF0EA]">
                {memories.map((memory) => (
                  <li key={memory.id} className="flex items-start justify-between gap-4 py-3.5">
                    <div className="min-w-0">
                      <p className="text-sm">{memory.content}</p>
                      {formatDate(memory.createdAt) ? (
                        <p className="mt-0.5 text-xs text-[#4A5750]">Saved {formatDate(memory.createdAt)}</p>
                      ) : null}
                    </div>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label="Delete this memory"
                      disabled={deletingId === memory.id}
                      onClick={() => void handleDelete(memory.id)}
                      className="shrink-0 text-[#4A5750] hover:text-destructive"
                    >
                      {deletingId === memory.id ? (
                        <Loader2 className="animate-spin" aria-hidden="true" />
                      ) : (
                        <Trash2 aria-hidden="true" />
                      )}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </Container>

      <Dialog open={confirmOpen} onOpenChange={(open) => !isClearing && setConfirmOpen(open)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Clear all memories?</DialogTitle>
            <DialogDescription>
              Neuro will forget everything it has saved about you. This can&apos;t be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" disabled={isClearing} />}>Cancel</DialogClose>
            <Button
              className="bg-destructive text-white hover:bg-destructive/90"
              disabled={isClearing}
              onClick={() => void handleClearAll()}
            >
              {isClearing ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
              Clear all
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
