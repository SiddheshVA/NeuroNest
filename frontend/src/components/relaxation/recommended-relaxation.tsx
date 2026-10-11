import type { RecommendationMode } from "@/lib/relaxation/recommendations";
import type { RecommendedEntry } from "@/lib/relaxation/use-relaxation-activity";
import { RelaxationItemCard } from "@/components/relaxation/relaxation-item-card";

// Wording follows what the list is really built from, so there's never
// "based on your activity" text without activity behind it.
const COPY: Record<RecommendationMode, { title: string; subtitle: string }> = {
  popular: {
    title: "Popular picks",
    subtitle: "A few favorites to get you started.",
  },
  "neuro-popular": {
    title: "Recommended for you",
    subtitle: "From your Neuro chat, plus a few popular picks.",
  },
  personal: {
    title: "Recommended for you",
    subtitle: "Picked from what you've been doing lately.",
  },
};

function RecommendedRelaxation({
  mode,
  entries,
}: {
  mode: RecommendationMode;
  entries: RecommendedEntry[];
}) {
  const copy = COPY[mode];

  return (
    <section aria-labelledby="recommended-heading">
      <div>
        <h2
          id="recommended-heading"
          className="font-heading text-base font-semibold text-foreground sm:text-lg"
        >
          {copy.title}
        </h2>
        <p className="mt-1 text-xs text-muted-foreground sm:text-sm">{copy.subtitle}</p>
      </div>

      {entries.length === 0 ? (
        <p className="mt-3 text-sm text-muted-foreground">
          Nothing here for this mood. Try another one, or tap it again to see everything.
        </p>
      ) : (
        <div className="mt-3 -mx-4 flex gap-3 overflow-x-auto px-4 pb-1 sm:mx-0 sm:grid sm:grid-cols-2 sm:gap-3 sm:px-0 lg:grid-cols-4">
          {entries.map(({ item, reason }) => (
            <RelaxationItemCard
              key={item.key}
              item={item}
              reason={reason}
              playTone="solid"
              className="w-64 shrink-0 sm:w-auto"
            />
          ))}
        </div>
      )}
    </section>
  );
}

export { RecommendedRelaxation };
