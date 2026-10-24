import { useState } from "react";
import { Star } from "lucide-react";

import { primaryButtonClass, secondaryButtonClass } from "@/components/pro/pro-ui";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { formatIstDateTime } from "@/lib/ist-time";
import {
  createReview,
  deleteReview,
  MAX_COMMENT_LENGTH,
  ReviewApiError,
  updateReview,
  type MyReview,
  type ReviewableBooking,
} from "@/lib/reviews-client";
import { cn } from "@/lib/utils";

const HELPER_TEXT = "Please don't share personal or health details.";

function errorText(error: unknown) {
  if (error instanceof ReviewApiError) {
    if (error.status === 401) return "Please sign in again to rate your session.";
    // 400, 404 and 409 come with a message written for people (not eligible, contact details, too long...).
    return error.message;
  }
  return "Your review could not be saved. Please try again.";
}

/**
 * "Rate your session" and "Edit your review" for one past appointment: 1-5 stars (required) and an optional
 * comment. The server decides whether the review is allowed; its answer is shown here as written.
 * onSaved gets the saved review, or null after a delete.
 */
export function ReviewDialog({
  entry,
  open,
  onOpenChange,
  onSaved,
}: {
  entry: ReviewableBooking;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved: (review: MyReview | null) => void;
}) {
  const existing = entry.review;
  const [rating, setRating] = useState(existing?.rating ?? 0);
  const [hovered, setHovered] = useState<number | null>(null);
  const [comment, setComment] = useState(existing?.comment ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const shown = hovered ?? rating;
  const name = entry.professional.fullName;

  async function save() {
    if (rating < 1) {
      setError("Please choose a rating from 1 to 5 stars.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const saved = existing
        ? await updateReview(entry.bookingId, rating, comment)
        : await createReview(entry.bookingId, rating, comment);
      onSaved(saved);
      onOpenChange(false);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await deleteReview(entry.bookingId);
      onSaved(null);
      onOpenChange(false);
    } catch (e) {
      setConfirmingDelete(false);
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !busy && onOpenChange(next)}>
      {/* No fade, scale or slide: the popup and its backdrop simply appear. */}
      <DialogContent className="transition-none" backdropClassName="transition-none">
        {confirmingDelete ? (
          <>
            <DialogHeader>
              <DialogTitle>Delete your review?</DialogTitle>
              <DialogDescription>
                It will be removed from {name}&apos;s profile and rating. You can rate this session again later.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <button type="button" className={secondaryButtonClass} onClick={() => setConfirmingDelete(false)} disabled={busy}>
                Keep it
              </button>
              <button type="button" className={primaryButtonClass} onClick={() => void remove()} disabled={busy}>
                {busy ? "Deleting…" : "Delete review"}
              </button>
            </DialogFooter>
          </>
        ) : (
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              void save();
            }}
          >
            <DialogHeader>
              <DialogTitle>{existing ? "Edit your review" : "Rate your session"}</DialogTitle>
              <DialogDescription>
                Your session with {name} on {formatIstDateTime(entry.scheduledTime)}. Reviews show as
                &ldquo;Verified client&rdquo; with the month, never your name.
              </DialogDescription>
            </DialogHeader>

            <fieldset className="flex flex-col gap-1.5">
              <legend className="mb-1.5 text-sm font-medium text-foreground">Your rating (required)</legend>
              <div className="flex items-center gap-1" onMouseLeave={() => setHovered(null)}>
                {[1, 2, 3, 4, 5].map((n) => (
                  <label
                    key={n}
                    className="cursor-pointer rounded-md p-1 has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-ring/50"
                    onMouseEnter={() => setHovered(n)}
                  >
                    <input
                      type="radio"
                      name="rating"
                      value={n}
                      checked={rating === n}
                      onChange={() => {
                        setRating(n);
                        setError(null);
                      }}
                      className="sr-only"
                    />
                    <Star
                      className={cn(
                        "size-7 transition-colors duration-150",
                        n <= shown ? "fill-amber-500 text-amber-600" : "text-muted-foreground/60",
                      )}
                      aria-hidden="true"
                    />
                    <span className="sr-only">
                      {n} {n === 1 ? "star" : "stars"}
                    </span>
                  </label>
                ))}
                <span className="ml-2 text-sm text-muted-foreground" aria-hidden="true">
                  {rating > 0 ? `${rating} out of 5` : "Choose 1 to 5"}
                </span>
              </div>
            </fieldset>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="review-comment" className="text-sm font-medium text-foreground">
                Comment (optional)
              </label>
              <textarea
                id="review-comment"
                value={comment}
                maxLength={MAX_COMMENT_LENGTH}
                rows={4}
                onChange={(event) => setComment(event.target.value)}
                aria-describedby="review-comment-help review-comment-count"
                className="w-full rounded-xl border border-input bg-background px-3 py-2 text-sm text-foreground outline-none transition-colors duration-150 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
              />
              <div className="flex flex-wrap items-start justify-between gap-2 text-xs text-muted-foreground">
                <span id="review-comment-help">{HELPER_TEXT}</span>
                <span id="review-comment-count" className="tabular-nums">
                  {comment.length}/{MAX_COMMENT_LENGTH}
                </span>
              </div>
            </div>

            {error ? (
              <p role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
                {error}
              </p>
            ) : null}

            <DialogFooter className="sm:items-center">
              {existing ? (
                <button
                  type="button"
                  onClick={() => setConfirmingDelete(true)}
                  disabled={busy}
                  className="cursor-pointer rounded-sm text-sm font-medium text-destructive underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50 sm:mr-auto"
                >
                  Delete review
                </button>
              ) : null}
              <button type="button" className={secondaryButtonClass} onClick={() => onOpenChange(false)} disabled={busy}>
                Cancel
              </button>
              <button type="submit" className={primaryButtonClass} disabled={busy}>
                {busy ? "Saving…" : existing ? "Save changes" : "Submit review"}
              </button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
