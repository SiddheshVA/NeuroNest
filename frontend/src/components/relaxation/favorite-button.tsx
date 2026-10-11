import { Heart } from "lucide-react";

import { useFavorite } from "@/lib/relaxation/use-relaxation-activity";
import { cn } from "@/lib/utils";

/**
 * Heart toggle for any relaxation item, saved via the activity store.
 * "icon" sits on cards, "overlay" on photos, "pill" is the labelled
 * version used on detail pages.
 */
function FavoriteButton({
  itemKey,
  title,
  variant = "icon",
  className,
}: {
  itemKey: string;
  title: string;
  variant?: "icon" | "overlay" | "pill";
  className?: string;
}) {
  const { isFavorite, toggle, isReady } = useFavorite(itemKey);
  const label = isFavorite ? `Remove ${title} from favorites` : `Add ${title} to favorites`;

  return (
    <button
      type="button"
      aria-pressed={isFavorite}
      aria-label={label}
      title={label}
      disabled={!isReady}
      onClick={toggle}
      className={cn(
        "flex shrink-0 items-center justify-center gap-1.5 outline-none transition-all focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50",
        variant === "icon" &&
          "size-8 rounded-full text-rose-500 hover:bg-rose-50",
        variant === "overlay" &&
          "size-8 rounded-full bg-white/90 text-rose-500 shadow-sm hover:scale-105",
        variant === "pill" &&
          "h-12 rounded-full border border-border bg-card px-5 text-sm font-medium text-foreground hover:bg-muted/60",
        className,
      )}
    >
      <Heart
        className={cn("size-4", isFavorite && "fill-current", variant === "pill" && "text-rose-500")}
        aria-hidden="true"
      />
      {variant === "pill" ? (isFavorite ? "Saved to favorites" : "Save to favorites") : null}
    </button>
  );
}

export { FavoriteButton };
