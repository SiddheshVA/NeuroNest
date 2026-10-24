import { useEffect, useState } from "react";
import { MessageSquareQuote, RefreshCw } from "lucide-react";

import { StarRow } from "@/components/professionals/professional-bits";
import {
  formatRating,
  formatReviewMonth,
  getProfessionalReviews,
  ProfessionalsApiError,
  type PublicProfessional,
  type PublicReview,
} from "@/lib/professionals-client";

type State =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; items: PublicReview[]; page: number; totalPages: number; loadingMore: boolean; moreError: boolean };

const errorMessage = (e: unknown) =>
  e instanceof ProfessionalsApiError && e.status === 0 ? e.message : "Reviews could not be loaded. Please try again.";

/**
 * The profile's "Reviews": the average and count, then the visible reviews newest first, a page at a time.
 * Reviewers only ever appear as "Verified client" and the month.
 */
function ProfessionalReviews({ professional: p }: { professional: PublicProfessional }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [retryCount, setRetryCount] = useState(0);
  const hasReviews = p.ratingCount > 0 && p.ratingAvg !== null;

  useEffect(() => {
    if (!hasReviews) return;
    const controller = new AbortController();
    getProfessionalReviews(p.id, 0, controller.signal)
      .then((page) =>
        setState({ status: "ready", items: page.items, page: 0, totalPages: page.totalPages, loadingMore: false, moreError: false }),
      )
      .catch((e) => {
        if (!controller.signal.aborted) setState({ status: "error", message: errorMessage(e) });
      });
    return () => controller.abort();
  }, [p.id, hasReviews, retryCount]);

  function showMore() {
    if (state.status !== "ready") return;
    const next = state.page + 1;
    setState({ ...state, loadingMore: true, moreError: false });
    getProfessionalReviews(p.id, next)
      .then((page) =>
        setState((current) =>
          current.status === "ready"
            ? { ...current, items: [...current.items, ...page.items], page: next, totalPages: page.totalPages, loadingMore: false }
            : current,
        ),
      )
      .catch(() =>
        setState((current) => (current.status === "ready" ? { ...current, loadingMore: false, moreError: true } : current)),
      );
  }

  const reviews = `${p.ratingCount} ${p.ratingCount === 1 ? "review" : "reviews"}`;

  return (
    <section
      id="reviews"
      aria-labelledby="reviews-heading"
      className="flex flex-col gap-5 rounded-2xl bg-card p-6 ring-1 ring-foreground/10 sm:p-8"
    >
      <h2 id="reviews-heading" className="font-heading text-lg font-semibold text-foreground">
        Reviews
      </h2>

      {!hasReviews ? (
        <div className="flex items-start gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
            <MessageSquareQuote className="size-5" aria-hidden="true" />
          </span>
          <div>
            <p className="text-sm font-medium text-foreground">No reviews yet</p>
            <p className="text-sm text-muted-foreground">
              Clients who book a session through NeuroNest can rate it afterwards.
            </p>
          </div>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="font-heading text-4xl font-semibold text-foreground" aria-hidden="true">
              {formatRating(p.ratingAvg!)}
            </span>
            <div className="flex flex-col gap-0.5">
              <StarRow rating={p.ratingAvg!} />
              <span className="text-sm text-muted-foreground" aria-hidden="true">
                Based on {reviews}
              </span>
            </div>
            <span className="sr-only">
              Rated {formatRating(p.ratingAvg!)} out of 5, based on {reviews}.
            </span>
          </div>

          {state.status === "loading" ? (
            <p className="text-sm text-muted-foreground" aria-live="polite">
              Loading reviews.
            </p>
          ) : state.status === "error" ? (
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
              <span>{state.message}</span>
              <button
                type="button"
                onClick={() => {
                  setState({ status: "loading" });
                  setRetryCount((n) => n + 1);
                }}
                className="inline-flex items-center gap-1.5 rounded-sm font-medium underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                <RefreshCw className="size-4" aria-hidden="true" />
                Try again
              </button>
            </div>
          ) : (
            <>
              <ul className="flex flex-col divide-y divide-border">
                {state.items.map((review, index) => (
                  <li key={`${review.month}-${index}`} className="flex flex-col gap-1.5 py-4 first:pt-0 last:pb-0">
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                      <StarRow rating={review.rating} />
                      <span className="sr-only">Rated {review.rating} out of 5.</span>
                      <span className="text-sm text-muted-foreground">
                        Verified client · {formatReviewMonth(review.month)}
                      </span>
                    </div>
                    {review.comment ? (
                      <p className="text-sm leading-relaxed break-words whitespace-pre-line text-foreground sm:text-base">
                        {review.comment}
                      </p>
                    ) : null}
                  </li>
                ))}
              </ul>
              {state.page + 1 < state.totalPages ? (
                <div className="flex flex-col items-start gap-2">
                  <button
                    type="button"
                    onClick={showMore}
                    disabled={state.loadingMore}
                    className="inline-flex h-9 cursor-pointer items-center rounded-full border border-border bg-background px-4 text-sm font-medium text-foreground outline-none transition-colors duration-150 hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-default disabled:opacity-60"
                  >
                    {state.loadingMore ? "Loading…" : "Show more reviews"}
                  </button>
                  {state.moreError ? (
                    <p className="text-sm text-destructive" role="alert">
                      More reviews could not be loaded. Please try again.
                    </p>
                  ) : null}
                </div>
              ) : null}
            </>
          )}
        </>
      )}
    </section>
  );
}

export { ProfessionalReviews };
