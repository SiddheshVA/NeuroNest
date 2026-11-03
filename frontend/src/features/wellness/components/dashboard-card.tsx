import Link from "next/link";
import { HeartPulse, Loader2, RotateCcw } from "lucide-react";

import { COMMON, DASHBOARD, ERRORS, HISTORY, TREND } from "../copy.ts";
import { useRecentCheckins } from "../hooks.ts";
import { WELLNESS_ROUTES, sleepFlag } from "../results.ts";
import type { CheckinItem } from "../types.ts";
import { SparklineTrend } from "./charts.tsx";
import { ghostButton, h2, primaryButton } from "./styles.ts";
import { WellnessSurface } from "./shell.tsx";
import { BandPill, Card, CardTint, Pill } from "./ui.tsx";

/** How many snapshots the trend shows. */
const TREND_COUNT = 6;

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

const SLEEP_FLAG_TEXT = { concern: HISTORY.sleepConcern, ok: HISTORY.sleepOk, guidance: HISTORY.sleepGuidance };

/**
 * Self-contained Dashboard card: the latest snapshot, a trend of up to the last six, or a friendly prompt when
 * there is none. It carries its own scoped tokens and fonts (WellnessSurface), so the Dashboard is unchanged.
 */
export function WellnessDashboardCard() {
  const { state, retry } = useRecentCheckins(TREND_COUNT);

  return (
    <WellnessSurface>
      <Card aria-labelledby="wellness-snapshot-card" className="flex flex-col gap-5">
        <div className="flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-[12px] bg-(--wn-brand-soft) text-(--wn-brand)">
            <HeartPulse className="size-5" strokeWidth={2} aria-hidden="true" />
          </span>
          <h2 id="wellness-snapshot-card" className={h2}>
            {DASHBOARD.title}
          </h2>
        </div>

        {state.status === "loading" ? (
          <div className="flex justify-center py-6 text-(--wn-ink-2)">
            <Loader2 className="size-5 motion-safe:animate-spin" strokeWidth={2} aria-label={COMMON.loading} />
          </div>
        ) : state.status === "error" ? (
          <div className="flex flex-col items-start gap-3">
            <p className="text-[15px] text-(--wn-ink-2)">{DASHBOARD.failed}</p>
            <button type="button" onClick={retry} className={ghostButton}>
              <RotateCcw className="size-4" strokeWidth={2} aria-hidden="true" />
              {ERRORS.retry}
            </button>
          </div>
        ) : state.data.length === 0 ? (
          <CardTint className="flex flex-col items-start gap-3">
            <div>
              <p className="text-[16px] font-bold text-(--wn-ink)">{DASHBOARD.none}</p>
              <p className="text-[15px] text-(--wn-ink-2)">{DASHBOARD.noneText}</p>
            </div>
            <Link href={WELLNESS_ROUTES.checkin} className={primaryButton}>
              {DASHBOARD.start}
            </Link>
          </CardTint>
        ) : (
          <LatestAndTrend items={state.data} />
        )}
      </Card>
    </WellnessSurface>
  );
}

function LatestAndTrend({ items }: { items: CheckinItem[] }) {
  const latest = items[0];
  // Oldest to newest, left to right.
  const bands = items.map((i) => i.result.mood.risk_band).reverse();
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2.5">
        <span className="text-sm font-semibold text-(--wn-ink-2)">{DASHBOARD.latest}</span>
        <BandPill band={latest.result.mood.risk_band} />
        <Pill variant="brand">{SLEEP_FLAG_TEXT[sleepFlag(latest.result.sleep, latest.suggestedActions)]}</Pill>
        <span className="text-sm text-(--wn-ink-2)">
          <time dateTime={latest.createdAt}>{formatDate(latest.createdAt)}</time>
        </span>
      </div>
      <SparklineTrend bands={bands} summary={TREND.summary(bands)} />
      <div className="flex flex-wrap gap-3">
        <Link href={WELLNESS_ROUTES.checkin} className={primaryButton}>
          {DASHBOARD.again}
        </Link>
        <Link href={WELLNESS_ROUTES.history} className={ghostButton}>
          {DASHBOARD.history}
        </Link>
      </div>
    </div>
  );
}
