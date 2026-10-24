import { useState } from "react";
import { Star } from "lucide-react";

import { secondaryButtonClass } from "@/components/pro/pro-ui";
import { StarRow } from "@/components/professionals/professional-bits";
import { ReviewDialog } from "@/components/reviews/review-dialog";
import type { MyReview, ReviewableBooking } from "@/lib/reviews-client";
import { cn } from "@/lib/utils";

/**
 * For one past confirmed appointment: "Rate your session" when there is no review yet, otherwise the stars
 * and "Edit". A review hidden by NeuroNest shows its stars and a short note, and cannot be changed.
 */
export function SessionReviewControl({
  entry,
  onChanged,
  className,
}: {
  entry: ReviewableBooking;
  onChanged: (review: MyReview | null) => void;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const review = entry.review;

  return (
    <div className={cn("flex flex-wrap items-center gap-3", className)}>
      {review ? (
        <>
          <span className="inline-flex items-center gap-2 text-sm text-foreground">
            <span className="text-muted-foreground">Your rating</span>
            <StarRow rating={review.rating} />
            <span className="sr-only">{review.rating} out of 5</span>
          </span>
          {review.status === "HIDDEN" ? (
            <span className="text-sm text-muted-foreground">Hidden by NeuroNest, so it can no longer be changed.</span>
          ) : (
            <button
              type="button"
              onClick={() => setOpen(true)}
              className="cursor-pointer rounded-sm text-sm font-medium text-primary underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              Edit<span className="sr-only"> your review of {entry.professional.fullName}</span>
            </button>
          )}
        </>
      ) : (
        <button type="button" onClick={() => setOpen(true)} className={cn(secondaryButtonClass, "w-fit gap-2")}>
          <Star className="size-4" aria-hidden="true" />
          Rate your session<span className="sr-only"> with {entry.professional.fullName}</span>
        </button>
      )}

      {/* Mounted only while open, so it always starts from the latest review. */}
      {open ? <ReviewDialog entry={entry} open onOpenChange={setOpen} onSaved={onChanged} /> : null}
    </div>
  );
}
