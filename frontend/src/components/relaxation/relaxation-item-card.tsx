import Link from "next/link";
import { Play } from "lucide-react";

import type { RelaxationItem } from "@/data/relaxation";
import { RelaxationItemThumbnail } from "@/components/relaxation/relaxation-item-thumbnail";
import { FavoriteButton } from "@/components/relaxation/favorite-button";
import { cn } from "@/lib/utils";

/**
 * Compact card for one relaxation item: the link opens the item, the heart
 * beside it saves it. They're siblings, since a button can't sit inside a link.
 */
function RelaxationItemCard({
  item,
  reason,
  playTone = "soft",
  className,
}: {
  item: RelaxationItem;
  /** Why it's shown, e.g. "Because you liked Box Breathing". */
  reason?: string;
  playTone?: "soft" | "solid";
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex items-center gap-1 rounded-xl bg-card p-2 ring-1 ring-foreground/10 transition-colors hover:bg-muted/50",
        className,
      )}
    >
      <Link
        href={item.href}
        aria-label={`Open ${item.title}`}
        className="group flex min-w-0 flex-1 items-center gap-2.5 rounded-lg text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <RelaxationItemThumbnail item={item} className="size-11" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-foreground">{item.title}</p>
          <p className="truncate text-xs text-muted-foreground">
            {item.category} &middot; {item.duration}
          </p>
          {reason ? (
            <p className="truncate text-xs font-medium text-primary">{reason}</p>
          ) : null}
        </div>
        <span
          className={cn(
            "flex size-8 shrink-0 items-center justify-center rounded-full transition-transform group-hover:scale-105",
            playTone === "solid"
              ? "bg-primary text-primary-foreground"
              : "bg-primary/10 text-primary",
          )}
          aria-hidden="true"
        >
          <Play className="size-3.5" />
        </span>
      </Link>
      <FavoriteButton itemKey={item.key} title={item.title} />
    </div>
  );
}

export { RelaxationItemCard };
