import Link from "next/link";
import { HeartPulse, Loader2, RotateCcw } from "lucide-react";

import { COMMON, DASHBOARD, ERRORS, HISTORY } from "../copy.ts";
import { useLatestCheckin } from "../hooks.ts";
import { WELLNESS_ROUTES, sleepFlag } from "../results.ts";
import { BandChip } from "./result-parts.tsx";
import { card, muted, primaryButton, secondaryButton } from "./styles.ts";

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

const SLEEP_FLAG_TEXT = { concern: HISTORY.sleepConcern, ok: HISTORY.sleepOk, guidance: HISTORY.sleepGuidance };

/** Self-contained Dashboard card: the latest wellness check-in, or a friendly prompt when there is none. */
export function WellnessDashboardCard() {
  const { state, retry } = useLatestCheckin();

  return (
    <section className={card} aria-labelledby="wellness-checkin-card">
      <div className="mb-4 flex items-center gap-2">
        <span className="flex size-9 items-center justify-center rounded-full bg-[#ECEFE6] text-[#233E33]">
          <HeartPulse className="size-5" aria-hidden="true" />
        </span>
        <h2 id="wellness-checkin-card" className="text-lg font-semibold text-[#1C352D]">
          {DASHBOARD.title}
        </h2>
      </div>

      {state.status === "loading" ? (
        <div className="flex justify-center py-6 text-[#4A5750]">
          <Loader2 className="size-5 motion-safe:animate-spin" aria-label={COMMON.loading} />
        </div>
      ) : state.status === "error" ? (
        <div className="flex flex-col items-start gap-3">
          <p className={`text-sm ${muted}`}>{DASHBOARD.failed}</p>
          <button type="button" onClick={retry} className={secondaryButton}>
            <RotateCcw className="size-4" aria-hidden="true" />
            {ERRORS.retry}
          </button>
        </div>
      ) : state.data === null ? (
        <div className="flex flex-col items-start gap-3 rounded-2xl bg-[#FBF9F4] p-5">
          <div>
            <p className="text-sm font-semibold text-[#1C352D]">{DASHBOARD.none}</p>
            <p className={`text-sm ${muted}`}>{DASHBOARD.noneText}</p>
          </div>
          <Link href={WELLNESS_ROUTES.checkin} className={primaryButton}>
            {DASHBOARD.start}
          </Link>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-3">
            <span className={`text-sm ${muted}`}>{DASHBOARD.latest}</span>
            <BandChip band={state.data.result.mood.risk_band} />
            <span className="rounded-full bg-[#F5F7F2] px-3 py-1 text-xs font-medium text-[#3D4A43]">
              {SLEEP_FLAG_TEXT[sleepFlag(state.data.result.sleep, state.data.suggestedActions)]}
            </span>
          </div>
          <p className={`text-xs ${muted}`}>
            <time dateTime={state.data.createdAt}>{formatDate(state.data.createdAt)}</time>
          </p>
          <div className="flex flex-wrap gap-3">
            <Link href={WELLNESS_ROUTES.history} className={secondaryButton}>
              {DASHBOARD.history}
            </Link>
            <Link href={WELLNESS_ROUTES.checkin} className={secondaryButton}>
              {DASHBOARD.again}
            </Link>
          </div>
        </div>
      )}
    </section>
  );
}
