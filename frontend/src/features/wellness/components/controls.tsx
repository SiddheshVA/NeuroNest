import { useId, type ReactNode } from "react";
import { Info, Minus, Plus } from "lucide-react";

import { NAV, Q } from "../copy.ts";
import { focusRing, h2 } from "./styles.ts";

/**
 * A question: an h2, a one-line helper, the control (a labelled group), a "Why we ask" line and an inline
 * error. The group is named by the heading and described by the helper and the error.
 */
export function Question({
  label,
  hint,
  why,
  error,
  extra,
  children,
}: {
  label: string;
  hint?: string;
  why?: string;
  error?: string;
  /** Small line under the control, e.g. what an optional answer improves. */
  extra?: string;
  children: ReactNode;
}) {
  const headingId = useId();
  const hintId = useId();
  const errorId = useId();
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <h2 id={headingId} className={h2}>
          {label}
        </h2>
        {hint ? (
          <p id={hintId} className="text-[15px] text-(--wn-ink-2)">
            {hint}
          </p>
        ) : null}
      </div>
      <div role="group" aria-labelledby={headingId} aria-describedby={describedBy}>
        {children}
      </div>
      {extra ? <p className="text-sm text-(--wn-ink-2)">{extra}</p> : null}
      {why ? <WhyWeAsk text={why} /> : null}
      {error ? (
        <p id={errorId} role="alert" className="text-sm font-semibold text-(--wn-high-text)">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** "Why we ask" with an info icon, always visible as one short line. */
export function WhyWeAsk({ text }: { text: string }) {
  return (
    <p className="flex gap-2 text-sm text-(--wn-ink-2)">
      <Info className="mt-0.5 size-4 shrink-0 text-(--wn-brand)" strokeWidth={2} aria-hidden="true" />
      <span>
        <span className="font-semibold text-(--wn-ink)">{NAV.whyWeAsk}: </span>
        {text}
      </span>
    </p>
  );
}

export interface Chip<V> {
  key: string;
  value: V;
  label: string;
}

/**
 * Single-choice chips as toggle buttons (aria-pressed); the surrounding Question names the group. `value` null
 * means nothing chosen (or a chip whose value is null, e.g. Skip).
 */
export function ChipGroup<V>({
  chips,
  value,
  onChange,
  invalid,
}: {
  chips: Chip<V>[];
  value: V | null;
  onChange: (value: V) => void;
  invalid?: boolean;
}) {
  return (
    <div className="flex flex-wrap gap-2.5">
      {chips.map((chip) => {
        const selected = chip.value === value;
        return (
          <button
            key={chip.key}
            type="button"
            aria-pressed={selected}
            onClick={() => onChange(chip.value)}
            className={`min-h-[46px] cursor-pointer rounded-[14px] border-[1.5px] px-4 text-[15px] font-semibold motion-safe:transition-colors motion-safe:duration-150 ${focusRing} ${
              selected
                ? "border-(--wn-brand) bg-(--wn-brand-soft) text-(--wn-brand-dark)"
                : `bg-(--wn-surface) text-(--wn-ink) hover:border-(--wn-brand) ${invalid ? "border-(--wn-high-fill)" : "border-(--wn-line)"}`
            }`}
          >
            {chip.label}
          </button>
        );
      })}
    </div>
  );
}

/** 1-to-max as round 46px buttons; the selected one is filled with the brand colour. */
export function ScaleButtons({
  value,
  onChange,
  min = 1,
  max = 10,
  lowLabel,
  highLabel,
  invalid,
}: {
  value: number | null;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  lowLabel: string;
  highLabel: string;
  invalid?: boolean;
}) {
  const values = Array.from({ length: max - min + 1 }, (_, i) => min + i);
  return (
    // A container query: ten in a row when the card has room, otherwise two rows of five.
    <div className="@container flex flex-col gap-2">
      <div className="grid w-fit grid-cols-5 gap-2 @[540px]:grid-cols-10">
        {values.map((v) => {
          const selected = v === value;
          return (
            <button
              key={v}
              type="button"
              aria-pressed={selected}
              aria-label={NAV.scaleValue(v, max)}
              onClick={() => onChange(v)}
              className={`flex size-[46px] cursor-pointer items-center justify-center rounded-full border-[1.5px] text-[15px] font-bold tabular-nums motion-safe:transition-colors motion-safe:duration-150 ${focusRing} ${
                selected
                  ? "border-(--wn-brand) bg-(--wn-brand) text-white"
                  : `bg-(--wn-surface) text-(--wn-ink) hover:border-(--wn-brand) ${invalid ? "border-(--wn-high-fill)" : "border-(--wn-line)"}`
              }`}
            >
              {v}
            </button>
          );
        })}
      </div>
      <div className="flex w-full max-w-[262px] justify-between text-sm text-(--wn-ink-2) @[540px]:max-w-[532px]" aria-hidden="true">
        <span>
          {min} {lowLabel}
        </span>
        <span>
          {highLabel} {max}
        </span>
      </div>
    </div>
  );
}

/** Age: minus / number / plus. The first tap on minus or plus starts from `start`. */
export function AgeStepper({
  value,
  onChange,
  min,
  max,
  start,
  invalid,
}: {
  value: number | null;
  onChange: (value: number | null) => void;
  min: number;
  max: number;
  start: number;
  invalid?: boolean;
}) {
  const step = (delta: number) => {
    const base = value ?? start - delta;
    onChange(Math.min(max, Math.max(min, base + delta)));
  };
  const button = `flex size-[46px] shrink-0 cursor-pointer items-center justify-center rounded-full border-[1.5px] border-(--wn-line) bg-(--wn-surface) text-(--wn-ink) hover:border-(--wn-brand) disabled:cursor-not-allowed disabled:opacity-40 ${focusRing}`;
  return (
    <div className="flex items-center gap-3">
      <button type="button" className={button} onClick={() => step(-1)} disabled={value !== null && value <= min} aria-label={Q.age.decrease}>
        <Minus className="size-4" strokeWidth={2} aria-hidden="true" />
      </button>
      <label className="flex items-baseline gap-2">
        <span className="sr-only">{Q.age.label}</span>
        <input
          type="number"
          inputMode="numeric"
          min={min}
          max={max}
          value={value ?? ""}
          placeholder="–"
          aria-invalid={invalid || undefined}
          onChange={(e) => {
            const raw = e.target.value;
            onChange(raw === "" ? null : Math.trunc(Number(raw)));
          }}
          className={`h-[46px] w-20 rounded-[14px] border-[1.5px] bg-(--wn-surface) px-3 text-center text-xl font-bold tabular-nums text-(--wn-ink) ${
            invalid ? "border-(--wn-high-fill)" : "border-(--wn-line)"
          } ${focusRing}`}
        />
        <span className="text-[15px] text-(--wn-ink-2)">{Q.age.unit}</span>
      </label>
      <button type="button" className={button} onClick={() => step(1)} disabled={value !== null && value >= max} aria-label={Q.age.increase}>
        <Plus className="size-4" strokeWidth={2} aria-hidden="true" />
      </button>
    </div>
  );
}
