import { useState } from "react";
import Link from "next/link";
import { Heart, Trash2 } from "lucide-react";

import {
  useClearRelaxationData,
  useFavoriteItems,
} from "@/lib/relaxation/use-relaxation-activity";
import { RelaxationTopbar } from "@/components/relaxation/relaxation-topbar";
import { RelaxationItemCard } from "@/components/relaxation/relaxation-item-card";
import { Button } from "@/components/ui/button";

function ClearHistoryPanel() {
  const { clear, isGuest, isReady } = useClearRelaxationData();
  const [step, setStep] = useState<"idle" | "confirm" | "done">("idle");
  const whose = isGuest ? "this browser's guest" : "your account's";

  return (
    <section
      aria-labelledby="clear-history-heading"
      className="flex flex-col gap-3 rounded-2xl bg-muted/50 p-5"
    >
      <h2 id="clear-history-heading" className="text-sm font-semibold text-foreground">
        Your relaxation history
      </h2>
      <p className="text-sm leading-relaxed text-muted-foreground">
        Favorites and what you&apos;ve played are saved in this browser only, separately for each
        signed-in account and for guests.
      </p>

      {step === "confirm" ? (
        <div className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10">
          <p className="text-sm text-foreground">
            This removes {whose} favorites and play history from this browser. It can&apos;t be
            undone.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="destructive"
              onClick={() => {
                clear();
                setStep("done");
              }}
            >
              Yes, clear it
            </Button>
            <Button variant="outline" onClick={() => setStep("idle")}>
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-3">
          <Button
            variant="outline"
            className="w-fit gap-1.5"
            disabled={!isReady}
            onClick={() => setStep("confirm")}
          >
            <Trash2 data-icon="inline-start" aria-hidden="true" />
            Clear my relaxation history
          </Button>
          {step === "done" ? (
            <p role="status" className="text-sm text-muted-foreground">
              Cleared.
            </p>
          ) : null}
        </div>
      )}
    </section>
  );
}

function FavoritesPage() {
  const favorites = useFavoriteItems();

  return (
    <div className="flex flex-col gap-8">
      <RelaxationTopbar currentPage="Your favorites" />

      <div className="flex flex-col gap-2">
        <h1 className="font-heading text-2xl font-semibold text-foreground">Your favorites</h1>
        <p className="text-sm text-muted-foreground">
          Everything you&apos;ve saved with the heart, newest first.
        </p>
      </div>

      {favorites.length === 0 ? (
        <div className="flex flex-col items-start gap-3 rounded-2xl bg-card p-6 ring-1 ring-foreground/10">
          <span className="flex size-10 items-center justify-center rounded-full bg-rose-50 text-rose-500">
            <Heart className="size-5" aria-hidden="true" />
          </span>
          <p className="text-sm text-muted-foreground">
            No favorites yet. Tap the heart on any exercise, meditation or sound to save it here.
          </p>
          <Button nativeButton={false} render={<Link href="/relaxation" />}>
            Explore the Relaxation Hub
          </Button>
        </div>
      ) : (
        <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {favorites.map((item) => (
            <li key={item.key}>
              <RelaxationItemCard item={item} />
            </li>
          ))}
        </ul>
      )}

      <ClearHistoryPanel />
    </div>
  );
}

export { FavoritesPage };
