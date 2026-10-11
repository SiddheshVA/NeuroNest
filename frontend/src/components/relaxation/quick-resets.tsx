import type { RelaxationItem } from "@/data/relaxation";
import { RelaxationItemCard } from "@/components/relaxation/relaxation-item-card";

function QuickResets({ items }: { items: RelaxationItem[] }) {
  return (
    <section aria-labelledby="quick-resets-heading">
      <div>
        <h2
          id="quick-resets-heading"
          className="font-heading text-base font-semibold text-foreground sm:text-lg"
        >
          Quick resets
        </h2>
        <p className="mt-1 text-xs text-muted-foreground sm:text-sm">
          Short activities for when you need an instant break.
        </p>
      </div>

      {items.length === 0 ? (
        <p className="mt-3 text-sm text-muted-foreground">
          Nothing here for this mood. Try another one, or tap it again to see everything.
        </p>
      ) : (
        <div className="mt-3 -mx-4 flex gap-2.5 overflow-x-auto px-4 pb-1 sm:mx-0 sm:grid sm:grid-cols-2 sm:gap-3 sm:px-0 lg:grid-cols-4">
          {items.map((item) => (
            <RelaxationItemCard
              key={item.key}
              item={item}
              playTone="soft"
              className="w-64 shrink-0 sm:w-auto"
            />
          ))}
        </div>
      )}
    </section>
  );
}

export { QuickResets };
