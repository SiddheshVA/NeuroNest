import { useEffect, useRef, useState } from "react";
import { FlaskConical, Loader2 } from "lucide-react";

import { WHAT_IF } from "../copy.ts";
import { WHAT_IF_DEBOUNCE_MS, WHAT_IF_ENABLED } from "../feature-flags.ts";
import { withWhatIf } from "../questionnaire.ts";
import type { CheckinRequest, CheckinResponse } from "../types.ts";
import { BandChip, DriverList } from "./result-parts.tsx";
import { card, muted } from "./styles.ts";

/**
 * "What if I slept more / stressed less / moved more?" Live only when WHAT_IF_ENABLED is on AND a `preview`
 * function is given that scores WITHOUT saving. The backend has no such option yet, so nobody passes one and
 * the panel renders disabled: what-if must never create saved check-ins.
 */
export function WhatIfPanel({
  base,
  preview,
}: {
  base: CheckinRequest;
  preview?: (request: CheckinRequest) => Promise<CheckinResponse>;
}) {
  const live = WHAT_IF_ENABLED && preview !== undefined;
  const [sleep, setSleep] = useState(base.sleepDuration);
  const [stress, setStress] = useState(base.stressLevel);
  const [movement, setMovement] = useState(base.physicalActivityMinutes);
  const [state, setState] = useState<
    { status: "idle" } | { status: "loading" } | { status: "error" } | { status: "ready"; response: CheckinResponse }
  >({ status: "idle" });
  const latest = useRef(0);

  const changed =
    sleep !== base.sleepDuration || stress !== base.stressLevel || movement !== base.physicalActivityMinutes;

  useEffect(() => {
    if (!live || !changed || !preview) return;
    const call = ++latest.current;
    const timer = window.setTimeout(() => {
      setState({ status: "loading" });
      preview(withWhatIf(base, { sleepDuration: sleep, stressLevel: stress, physicalActivityMinutes: movement }))
        .then((response) => call === latest.current && setState({ status: "ready", response }))
        .catch(() => call === latest.current && setState({ status: "error" }));
    }, WHAT_IF_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [live, changed, preview, base, sleep, stress, movement]);

  const slider = (label: string, value: number, set: (v: number) => void, min: number, max: number, step: number) => (
    <label className="flex flex-col gap-1.5 text-sm">
      <span className="flex justify-between font-medium text-[#1C352D]">
        {label}
        <span className="tabular-nums">{value}</span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={!live}
        onChange={(e) => set(Number(e.target.value))}
        className="w-full cursor-pointer accent-[#233E33] disabled:cursor-not-allowed disabled:opacity-50"
      />
    </label>
  );

  return (
    <section className={card} aria-labelledby="what-if-title" aria-disabled={!live || undefined}>
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <FlaskConical className="size-5 text-[#233E33]" aria-hidden="true" />
        <h3 id="what-if-title" className="text-lg font-semibold text-[#1C352D]">
          {WHAT_IF.title}
        </h3>
        <span className="rounded-full border border-dashed border-[#9AA59C] px-2.5 py-0.5 text-xs font-medium text-[#4A5750]">
          {WHAT_IF.badge}
        </span>
      </div>
      <p className={`mb-5 text-sm ${muted}`}>{live ? WHAT_IF.intro : WHAT_IF.disabled}</p>
      <div className="grid gap-5 sm:grid-cols-3">
        {slider(WHAT_IF.sleep, sleep, setSleep, 4.5, 8.5, 1)}
        {slider(WHAT_IF.stress, stress, setStress, 1, 10, 1)}
        {slider(WHAT_IF.movement, movement, setMovement, 10, 75, 5)}
      </div>
      {live ? (
        <div className="mt-5" aria-live="polite">
          {state.status === "loading" ? (
            <p className={`flex items-center gap-2 text-sm ${muted}`}>
              <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />
              {WHAT_IF.updating}
            </p>
          ) : state.status === "error" ? (
            <p className="text-sm text-[#8A3324]">{WHAT_IF.failed}</p>
          ) : state.status === "ready" ? (
            <div className="flex flex-col gap-3">
              <BandChip band={state.response.result.mood.risk_band} />
              <DriverList drivers={state.response.result.mood.drivers} kind="mood" />
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
