import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";
import { Wallet } from "lucide-react";

import { ProPortalFrame } from "@/components/pro/pro-portal-nav";
import { DisabledAccountNotice, ErrorNotice, LoadingBlock, secondaryButtonClass } from "@/components/pro/pro-ui";
import { formatIstDateTime } from "@/lib/ist-time";
import { formatAmount, REPORT_CURRENCY, type PaymentTotals } from "@/lib/money";
import { ProApiError, useProApi } from "@/lib/pro-client";
import type { ProEarnings, ProEarningsItem } from "@/lib/pro-home";

const PAGE_SIZE = 10;

type State = { key: string; status: "error"; message: string } | { key: string; status: "ready"; data: ProEarnings };

// Every amount is shown exactly as the server sent it (in paise); nothing is worked out here.
const money = (paise: number, currency: string = REPORT_CURRENCY) => formatAmount(paise, currency) ?? "-";

function TotalsBlock({ title, totals }: { title: string; totals: PaymentTotals }) {
  return (
    <section className="flex flex-col gap-4 rounded-2xl bg-card p-5 ring-1 ring-foreground/10" aria-label={title}>
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="font-heading text-lg font-semibold text-foreground">{title}</h2>
        <span className="text-sm text-muted-foreground">
          {totals.paidSessions} paid {totals.paidSessions === 1 ? "session" : "sessions"}
        </span>
      </div>
      <div className="flex flex-col">
        <span className="text-xs text-muted-foreground">Your share</span>
        <span className="font-heading text-3xl font-semibold tracking-tight text-primary">
          {money(totals.professionalSharePaise)}
        </span>
      </div>
      <dl className="grid grid-cols-2 gap-3 border-t border-border pt-3">
        <div className="flex flex-col">
          <dt className="text-xs text-muted-foreground">Fees collected</dt>
          <dd className="text-sm font-medium text-foreground">{money(totals.grossPaise)}</dd>
        </div>
        <div className="flex flex-col">
          <dt className="text-xs text-muted-foreground">NeuroNest commission</dt>
          <dd className="text-sm font-medium text-foreground">{money(totals.commissionPaise)}</dd>
        </div>
      </dl>
    </section>
  );
}

function SessionRow({ item }: { item: ProEarningsItem }) {
  return (
    <li className="grid grid-cols-2 gap-3 rounded-2xl bg-card p-4 ring-1 ring-foreground/10 sm:grid-cols-5">
      <div className="col-span-2 flex flex-col sm:col-span-1">
        <span className="text-xs text-muted-foreground">Session</span>
        <span className="text-sm text-foreground">{item.sessionTime ? formatIstDateTime(item.sessionTime) : "-"}</span>
      </div>
      <div className="flex flex-col">
        <span className="text-xs text-muted-foreground">Paid on</span>
        <span className="text-sm text-foreground">{item.paidAt ? formatIstDateTime(item.paidAt) : "-"}</span>
      </div>
      <div className="flex flex-col">
        <span className="text-xs text-muted-foreground">Fee</span>
        <span className="text-sm text-foreground">{money(item.amountPaise, item.currency)}</span>
      </div>
      <div className="flex flex-col">
        <span className="text-xs text-muted-foreground">Commission</span>
        <span className="text-sm text-foreground">{money(item.commissionPaise, item.currency)}</span>
      </div>
      <div className="flex flex-col">
        <span className="text-xs text-muted-foreground">Your share</span>
        <span className="text-sm font-semibold text-foreground">{money(item.professionalSharePaise, item.currency)}</span>
      </div>
    </li>
  );
}

/** /pro/earnings: the professional's own paid sessions, from GET /api/pro/earnings. No user details. */
export function ProEarningsPage() {
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
      .earnings(page, PAGE_SIZE)
      .then((data) => {
        if (!cancelled) setState({ key, status: "ready", data });
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
        setState({
          key,
          status: "error",
          message: e instanceof Error && e.message ? e.message : "Your earnings could not be loaded. Please try again.",
        });
      });
    return () => {
      cancelled = true;
    };
  }, [ready, api, page, key]);

  const go = (nextPage: number) => setParams(nextPage > 0 ? new URLSearchParams({ page: String(nextPage) }) : new URLSearchParams());

  if (disabled) {
    return (
      <ProPortalFrame title="Earnings">
        <DisabledAccountNotice />
      </ProPortalFrame>
    );
  }

  const current = state && state.key === key ? state : null;
  return (
    <ProPortalFrame title="Earnings" intro="Your share of paid sessions after NeuroNest's commission.">
      {!current ? (
        <LoadingBlock label="Loading your earnings." />
      ) : current.status === "error" ? (
        <ErrorNotice message={current.message} onRetry={() => setReloadCount((n) => n + 1)} />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2">
            <TotalsBlock title="This month" totals={current.data.thisMonth} />
            <TotalsBlock title="All time" totals={current.data.allTime} />
          </div>

          <section className="flex flex-col gap-3" aria-labelledby="paid-sessions">
            <h2 id="paid-sessions" className="font-heading text-xl font-semibold text-foreground">
              Paid sessions
            </h2>
            {current.data.payments.items.length === 0 ? (
              <div className="flex items-center gap-3 rounded-2xl bg-card p-6 text-sm text-foreground ring-1 ring-foreground/10">
                <Wallet className="size-5 text-muted-foreground" aria-hidden="true" />
                No paid sessions yet.
              </div>
            ) : (
              <>
                <ul className="flex flex-col gap-3">
                  {current.data.payments.items.map((item) => (
                    <SessionRow key={item.paymentId} item={item} />
                  ))}
                </ul>
                {current.data.payments.totalPages > 1 ? (
                  <nav className="flex flex-wrap items-center justify-between gap-3" aria-label="Pages">
                    <button type="button" className={secondaryButtonClass} disabled={page === 0} onClick={() => go(page - 1)}>
                      Newer payments
                    </button>
                    <span className="text-sm text-muted-foreground">
                      Page {page + 1} of {current.data.payments.totalPages}
                    </span>
                    <button
                      type="button"
                      className={secondaryButtonClass}
                      disabled={page + 1 >= current.data.payments.totalPages}
                      onClick={() => go(page + 1)}
                    >
                      Older payments
                    </button>
                  </nav>
                ) : null}
              </>
            )}
          </section>
        </>
      )}
    </ProPortalFrame>
  );
}
