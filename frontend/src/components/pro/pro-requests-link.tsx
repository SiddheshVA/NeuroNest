import { useEffect, useState } from "react";
import Link from "next/link";
import { CalendarClock, ChevronRight } from "lucide-react";

import { useProApi, type ProMe } from "@/lib/pro-client";
import type { ProBookingSummary } from "@/lib/pro-bookings";

type Counts = { summary: ProBookingSummary; hasRequests: boolean };

/**
 * The way into /pro/requests from the profile page, with the number of requests waiting for a reply.
 * Always shown for an approved professional; for anyone else only once they have any request. The
 * counts are fetched again whenever the page gets focus.
 */
export function ProRequestsLink({ me }: { me: ProMe }) {
  const { api } = useProApi();
  const verified = me.profile.verificationStatus === "VERIFIED";
  const [counts, setCounts] = useState<Counts | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = () => {
      Promise.all([api.bookingSummary(), verified ? Promise.resolve(null) : api.bookings("", 0, 1)])
        .then(([summary, page]) => {
          if (!cancelled) setCounts({ summary, hasRequests: verified || (page?.totalElements ?? 0) > 0 });
        })
        .catch(() => {
          // The link is a convenience: if the counts cannot be loaded, the last ones stay.
        });
    };
    const onVisible = () => {
      if (document.visibilityState === "visible") load();
    };
    load();
    window.addEventListener("focus", load);
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      window.removeEventListener("focus", load);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [api, verified]);

  if (!verified && !counts?.hasRequests) return null;
  const pending = counts?.summary.pending ?? 0;
  const awaitingUser = counts?.summary.awaitingUser ?? 0;

  return (
    <Link
      href="/pro/requests"
      className="flex items-center gap-4 rounded-2xl bg-card p-5 ring-1 ring-primary/30 transition-colors hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
        <CalendarClock className="size-5" aria-hidden="true" />
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="flex flex-wrap items-center gap-2">
          <span className="font-heading text-lg font-semibold text-foreground">Appointment requests</span>
          {pending > 0 ? (
            <span className="inline-flex min-w-6 items-center justify-center rounded-full bg-primary px-2 py-0.5 text-xs font-semibold text-primary-foreground">
              {pending}
              <span className="sr-only"> {pending === 1 ? "request is" : "requests are"} waiting for your reply</span>
            </span>
          ) : null}
        </span>
        {pending > 0 ? (
          <span className="text-sm text-muted-foreground">
            {pending === 1 ? "1 request is waiting for your reply." : `${pending} requests are waiting for your reply.`}
          </span>
        ) : null}
        {awaitingUser > 0 ? <span className="text-sm text-muted-foreground">{awaitingUser} waiting for the person</span> : null}
      </span>
      <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
    </Link>
  );
}
