import { useEffect, useId, useRef, useState } from "react";
import { FlaskConical, Loader2, SlidersHorizontal } from "lucide-react";

import { WHAT_IF } from "../copy.ts";
import { WHAT_IF_DEBOUNCE_MS, WHAT_IF_ENABLED } from "../feature-flags.ts";
import { withWhatIf } from "../questionnaire.ts";
import { bandChange, clamp } from "../results.ts";
import type { CheckinRequest, CheckinResponse } from "../types.ts";
import { card, focusRing, ghostButton, h2 } from "./styles.ts";
import { BandPill, Pill } from "./ui.tsx";

const SLIDERS = {
  sleep: { min: 5, max: 9, step: 0.5 },
  stress: { min: 1, max: 10, step: 1 },
  movement: { min: 0, max: 90, step: 5 },
} as const;

function Slider({
  label,
  valueText,
  value,
  onChange,
  range,
  disabled,
  className = "",
}: {
  label: string;
  valueText: string;
  value: number;
  onChange: (v: number) => void;
  range: { min: number; max: number; step: number };
  disabled: boolean;
  className?: string;
}) {
  const id = useId();
  return (
    <div className={`flex flex-col gap-2 ${className}`}>
      <label htmlFor={id} className="flex items-baseline justify-between gap-3 text-[15px] font-semibold text-(--wn-ink)">
        {label}
        <span className="font-bold tabular-nums text-(--wn-brand-dark)" aria-hidden="true">
          {valueText}
        </span>
      </label>
      <input
        id={id}
        type="range"
        min={range.min}
        max={range.max}
        step={range.step}
        value={clamp(value, range.min, range.max)}
        disabled={disabled}
        aria-valuetext={valueText}
        onChange={(e) => onChange(Number(e.target.value))}
        className={`h-11 w-full cursor-pointer accent-(--wn-brand) disabled:cursor-not-allowed disabled:opacity-50 ${focusRing}`}
      />
    </div>
  );
}

/**
 * "What if I slept more / stressed less / moved more?" Live when WHAT_IF_ENABLED is on and a `preview`
 * function is given. `preview` must score WITHOUT saving (api.ts previewCheckin, ?save=false): what-if must
 * never create saved check-ins. Re-runs are debounced and only the latest answer is shown. The outcome is
 * always the API's own answer; nothing is scored in the browser.
 */
export function WhatIfPanel({
  base,
  baseBand,
  preview,
}: {
  base: CheckinRequest;
  /** The snapshot's own band, to say whether the what-if is the same, lower or higher. */
  baseBand: string;
  preview?: (request: CheckinRequest) => Promise<CheckinResponse>;
}) {
  const live = WHAT_IF_ENABLED && preview !== undefined;
  const [sleep, setSleep] = useState(base.sleepDuration);
  const [stress, setStress] = useState(base.stressLevel);
  const [movement, setMovement] = useState(base.physicalActivityMinutes);
  const [allOpen, setAllOpen] = useState(false);
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

  // On phones only the first slider shows until "Open all sliders" is pressed.
  const phoneHidden = allOpen ? "" : "max-[600px]:hidden";
  const outcomeBand = state.status === "ready" ? state.response.result.mood.risk_band : null;
  const change = outcomeBand ? bandChange(baseBand, outcomeBand) : null;

  return (
    <section className={card} aria-labelledby="what-if-title" aria-disabled={!live || undefined}>
      <div className="mb-1 flex flex-wrap items-center gap-2.5">
        <FlaskConical className="size-5 text-(--wn-brand)" strokeWidth={2} aria-hidden="true" />
        <h2 id="what-if-title" className={h2}>
          {WHAT_IF.title}
        </h2>
      </div>
      <p className="mb-6 text-[15px] text-(--wn-ink-2)">{live ? WHAT_IF.intro : WHAT_IF.disabled}</p>

      <div className="grid grid-cols-[1.4fr_1fr] gap-8 max-[760px]:grid-cols-1">
        <div className="flex flex-col gap-5">
          <Slider
            label={WHAT_IF.sleep}
            valueText={WHAT_IF.sleepValue(clamp(sleep, SLIDERS.sleep.min, SLIDERS.sleep.max))}
            value={sleep}
            onChange={setSleep}
            range={SLIDERS.sleep}
            disabled={!live}
          />
          <Slider
            label={WHAT_IF.stress}
            valueText={WHAT_IF.stressValue(stress)}
            value={stress}
            onChange={setStress}
            range={SLIDERS.stress}
            disabled={!live}
            className={phoneHidden}
          />
          <Slider
            label={WHAT_IF.movement}
            valueText={WHAT_IF.movementValue(clamp(movement, SLIDERS.movement.min, SLIDERS.movement.max))}
            value={movement}
            onChange={setMovement}
            range={SLIDERS.movement}
            disabled={!live}
            className={phoneHidden}
          />
          {!allOpen ? (
            <button type="button" className={`${ghostButton} hidden self-start max-[600px]:inline-flex`} onClick={() => setAllOpen(true)}>
              <SlidersHorizontal className="size-4" strokeWidth={2} aria-hidden="true" />
              {WHAT_IF.openAll}
            </button>
          ) : null}
        </div>

        <div className="flex flex-col gap-3 rounded-[14px] border border-(--wn-line) bg-(--wn-bg) p-5" aria-live="polite">
          <p className="text-sm font-semibold text-(--wn-ink-2)">{state.status === "idle" ? WHAT_IF.now : WHAT_IF.result}</p>
          {state.status === "loading" ? (
            <p className="flex items-center gap-2 text-[15px] text-(--wn-ink-2)">
              <Loader2 className="size-4 motion-safe:animate-spin" strokeWidth={2} aria-hidden="true" />
              {WHAT_IF.updating}
            </p>
          ) : state.status === "error" ? (
            <p className="text-[15px] font-semibold text-(--wn-high-text)">{WHAT_IF.failed}</p>
          ) : outcomeBand ? (
            <>
              <div>
                <BandPill band={outcomeBand} large />
              </div>
              <p className="text-[15px] text-(--wn-ink)">{change ? WHAT_IF[change] : null}</p>
            </>
          ) : (
            <>
              <div>
                <BandPill band={baseBand} large />
              </div>
              <p className="text-[15px] text-(--wn-ink-2)">{live ? WHAT_IF.start : WHAT_IF.disabled}</p>
            </>
          )}
          <div className="mt-auto">
            <Pill variant="ghost">{WHAT_IF.badge}</Pill>
          </div>
        </div>
      </div>
    </section>
  );
}
