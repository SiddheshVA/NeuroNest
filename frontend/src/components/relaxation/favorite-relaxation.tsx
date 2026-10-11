import Link from "next/link";

import { useFavoriteItems } from "@/lib/relaxation/use-relaxation-activity";
import { RelaxationItemThumbnail } from "@/components/relaxation/relaxation-item-thumbnail";
import { FavoriteButton } from "@/components/relaxation/favorite-button";

const HUB_FAVORITES_LIMIT = 3;

/** The newest saved favorites; renders nothing until something is saved. */
function FavoriteRelaxation() {
  const favorites = useFavoriteItems();
  if (favorites.length === 0) return null;

  return (
    <section aria-labelledby="favorites-heading">
      <div className="flex items-center justify-between">
        <h2
          id="favorites-heading"
          className="font-heading text-base font-semibold text-foreground sm:text-lg"
        >
          Your favorites
        </h2>
        <Link
          href="/relaxation/favorites"
          className="rounded-sm text-sm font-medium text-primary outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          View all
        </Link>
      </div>

      <div className="mt-3 -mx-4 flex gap-3 overflow-x-auto px-4 pb-1 sm:mx-0 sm:grid sm:grid-cols-3 sm:gap-3 sm:px-0">
        {favorites.slice(0, HUB_FAVORITES_LIMIT).map((item) => (
          <div
            key={item.key}
            className="group relative w-40 shrink-0 overflow-hidden rounded-2xl ring-1 ring-foreground/10 sm:w-auto"
          >
            <Link
              href={item.href}
              aria-label={`Open ${item.title}`}
              className="block outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <RelaxationItemThumbnail
                item={item}
                className="aspect-square w-full rounded-none transition-transform duration-300 group-hover:scale-105"
                sizes="(min-width: 640px) 33vw, 160px"
                iconClassName="size-9"
              />
              <div className="relative bg-card p-3">
                <h3 className="truncate font-heading text-sm font-semibold text-foreground">
                  {item.title}
                </h3>
                <p className="truncate text-xs text-muted-foreground">
                  {item.category} &middot; {item.duration}
                </p>
              </div>
            </Link>
            <FavoriteButton
              itemKey={item.key}
              title={item.title}
              variant="overlay"
              className="absolute top-2.5 right-2.5"
            />
          </div>
        ))}
      </div>
    </section>
  );
}

export { FavoriteRelaxation };
