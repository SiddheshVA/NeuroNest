import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";
import { MessageSquareQuote } from "lucide-react";

import { ProPortalFrame } from "@/components/pro/pro-portal-nav";
import { DisabledAccountNotice, ErrorNotice, secondaryButtonClass } from "@/components/pro/pro-ui";
import { StarRow } from "@/components/professionals/professional-bits";
import { ProApiError, useProApi, type ProReviews } from "@/lib/pro-client";
import { formatRating, formatReviewMonth } from "@/lib/professionals-client";

const PAGE_SIZE = 10;

type State = { key: string; status: "error"; message: string } | { key: string; status: "ready"; data: ProReviews };

/**
 * /pro/reviews: the professional's own average and visible reviews, from GET /api/pro/me/reviews. Read-only:
 * there is nothing here to edit, hide or delete, and reviewers only appear as "Verified client".
 */
export function ProReviewsPage() {
  const { isLoaded, isSignedIn } = useAuth();
  const { api, disabled } = useProApi();
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number.parseInt(params.get("page") ?? "0", 10) || 0);
  const [reloadCount, setReloadCount] = useState(0);
  const [state, setState] = useState<State | null>(null);
  const key = `${page}|${reloadCount}`;
  const ready = isLoaded && isSignedIn;

  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    api
      .reviews(page, PAGE_SIZE)
      .then((data) => {
        if (!cancelled) setState({ key, status: "ready", data });
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
        setState({
          key,
          status: "error",
          message: e instanceof Error && e.message ? e.message : "Your reviews could not be loaded. Please try again.",
        });
      });
    return () => {
      cancelled = true;
    };
  }, [ready, api, page, key]);

  const go = (nextPage: number) => setParams(nextPage > 0 ? new URLSearchParams({ page: String(nextPage) }) : new URLSearchParams());

  if (disabled) {
    return (
      <ProPortalFrame title="Reviews">
        <DisabledAccountNotice />
      </ProPortalFrame>
    );
  }

  const current = state && state.key === key ? state : null;
  const reviews = current?.status === "ready" ? current.data.reviews : null;

  return (
    <ProPortalFrame
      title="Reviews"
      intro="What clients said after a session booked through NeuroNest. Reviews are shown as they were written; NeuroNest moderates them."
    >
      {!current ? (
        <p className="text-sm text-muted-foreground" aria-live="polite">
          Loading your reviews.
        </p>
      ) : current.status === "error" ? (
        <ErrorNotice message={current.message} onRetry={() => setReloadCount((n) => n + 1)} />
      ) : (
        <>
          <section className="flex flex-col gap-2 rounded-2xl bg-card p-5 ring-1 ring-foreground/10" aria-label="Your rating">
            {current.data.ratingCount > 0 && current.data.ratingAvg !== null ? (
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-heading text-4xl font-semibold text-foreground" aria-hidden="true">
                  {formatRating(current.data.ratingAvg)}
                </span>
                <div className="flex flex-col gap-0.5">
                  <StarRow rating={current.data.ratingAvg} />
                  <span className="text-sm text-muted-foreground" aria-hidden="true">
                    Based on {current.data.ratingCount} {current.data.ratingCount === 1 ? "review" : "reviews"}
                  </span>
                </div>
                <span className="sr-only">
                  Your average is {formatRating(current.data.ratingAvg)} out of 5, based on {current.data.ratingCount}{" "}
                  {current.data.ratingCount === 1 ? "review" : "reviews"}.
                </span>
              </div>
            ) : (
              <div className="flex items-center gap-3 text-sm text-foreground">
                <MessageSquareQuote className="size-5 text-muted-foreground" aria-hidden="true" />
                <span>
                  No reviews yet. Clients can rate a session after it has taken place; your listing shows as
                  &ldquo;New&rdquo; until then.
                </span>
              </div>
            )}
          </section>

          {reviews && reviews.items.length > 0 ? (
            <section className="flex flex-col gap-3" aria-labelledby="your-reviews">
              <h2 id="your-reviews" className="font-heading text-xl font-semibold text-foreground">
                Your reviews
              </h2>
              <ul className="flex flex-col gap-3">
                {reviews.items.map((review, index) => (
                  <li
                    key={`${page}-${index}`}
                    className="flex flex-col gap-1.5 rounded-2xl bg-card p-4 ring-1 ring-foreground/10"
                  >
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                      <StarRow rating={review.rating} />
                      <span className="sr-only">Rated {review.rating} out of 5.</span>
                      <span className="text-sm text-muted-foreground">
                        Verified client · {formatReviewMonth(review.month)}
                      </span>
                    </div>
                    {review.comment ? (
                      <p className="text-sm leading-relaxed break-words whitespace-pre-line text-foreground">{review.comment}</p>
                    ) : (
                      <p className="text-sm text-muted-foreground">No comment.</p>
                    )}
                  </li>
                ))}
              </ul>
              {reviews.totalPages > 1 ? (
                <nav className="flex flex-wrap items-center justify-between gap-3" aria-label="Pages">
                  <button type="button" className={secondaryButtonClass} disabled={page === 0} onClick={() => go(page - 1)}>
                    Newer reviews
                  </button>
                  <span className="text-sm text-muted-foreground">
                    Page {page + 1} of {reviews.totalPages}
                  </span>
                  <button
                    type="button"
                    className={secondaryButtonClass}
                    disabled={page + 1 >= reviews.totalPages}
                    onClick={() => go(page + 1)}
                  >
                    Older reviews
                  </button>
                </nav>
              ) : null}
            </section>
          ) : null}
        </>
      )}
    </ProPortalFrame>
  );
}
