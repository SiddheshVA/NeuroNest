import { useId, useState, type ReactNode } from "react";
import { ChevronDown, Info, Minus, Plus } from "lucide-react";

import { NAV, Q } from "../copy.ts";
import { focusRing, muted } from "./styles.ts";

/** A question: label (legend), optional hint, a tap-to-reveal "why we ask", the control and an inline error. */
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
  const errorId = useId();
  return (
    <fieldset className="flex flex-col gap-3" aria-describedby={error ? errorId : undefined}>
      <legend className="mb-1 text-base font-semibold text-[#1C352D]">{label}</legend>
      {hint ? <p className={`-mt-2 text-sm ${muted}`}>{hint}</p> : null}
      {children}
      {extra ? <p className="text-xs text-[#5A6E5A]">{extra}</p> : null}
      {why ? <WhyWeAsk text={why} /> : null}
      {error ? (
        <p id={errorId} role="alert" className="text-sm font-medium text-[#8A3324]">
          {error}
        </p>
      ) : null}
    </fieldset>
  );
}

export function WhyWeAsk({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  return (
    <div>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((o) => !o)}
        className={`inline-flex cursor-pointer items-center gap-1.5 rounded-full px-1 text-xs font-medium text-[#5A6E5A] hover:text-[#233E33] ${focusRing}`}
      >
        <Info className="size-3.5" aria-hidden="true" />
        {open ? NAV.hideWhy : NAV.whyWeAsk}
        <ChevronDown className={`size-3.5 motion-safe:transition-transform ${open ? "rotate-180" : ""}`} aria-hidden="true" />
      </button>
      <p id={id} hidden={!open} className="mt-1.5 rounded-xl bg-[#F5F7F2] px-3 py-2 text-sm text-[#3D4A43]">
        {text}
      </p>
    </div>
  );
}

export interface Chip<V> {
  key: string;
  value: V;
  label: string;
}

/**
 * Single-choice chips built on native radio buttons: arrow keys move between them, screen readers announce
 * the group and the choice. `value` null means nothing chosen (or a chip whose value is null, e.g. Skip).
 */
export function ChipGroup<V>({
  name,
  chips,
  value,
  onChange,
  invalid,
}: {
  name: string;
  chips: Chip<V>[];
  value: V | null;
  onChange: (value: V) => void;
  invalid?: boolean;
}) {
  return (
    <div className="flex flex-wrap gap-2">
      {chips.map((chip) => {
        const checked = chip.value === value;
        return (
          <label
            key={chip.key}
            className={`cursor-pointer select-none rounded-full border px-4 py-2 text-sm font-medium motion-safe:transition-colors motion-safe:duration-150 has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-ring/50 ${
              checked
                ? "border-[#233E33] bg-[#233E33] text-white"
                : `bg-white text-[#1C352D] hover:bg-[#F4F6F0] ${invalid ? "border-[#B5654F]" : "border-[#C8CEBE]"}`
            }`}
          >
            <input
              type="radio"
              name={name}
              className="sr-only"
              checked={checked}
              onChange={() => onChange(chip.value)}
            />
            {chip.label}
          </label>
        );
      })}
    </div>
  );
}

/**
 * A 1-to-max slider. Until the user touches it the answer stays empty (shown as "not answered yet"); any
 * pointer or keyboard interaction, including picking the middle, records it.
 */
export function ScaleSlider({
  label,
  value,
  onChange,
  min = 1,
  max = 10,
  lowLabel,
  highLabel,
  invalid,
  disabled,
}: {
  label: string;
  value: number | null;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
  lowLabel: string;
  highLabel: string;
  invalid?: boolean;
  disabled?: boolean;
}) {
  const mid = Math.round((min + max) / 2);
  const shown = value ?? mid;
  const record = (v: number) => onChange(Math.min(max, Math.max(min, Math.round(v))));
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between">
        <span className={`text-2xl font-semibold tabular-nums ${value === null ? "text-[#9AA59C]" : "text-[#1C352D]"}`}>
          {value === null ? "–" : value}
          <span className={`text-sm font-normal ${muted}`}> / {max}</span>
        </span>
        {value === null ? <span className={`text-xs ${muted}`}>{NAV.sliderPrompt}</span> : null}
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={1}
        value={shown}
        disabled={disabled}
        aria-label={label}
        aria-valuetext={value === null ? NAV.sliderNotAnswered : NAV.sliderValue(value, max)}
        aria-invalid={invalid || undefined}
        onChange={(e) => record(Number(e.target.value))}
        onPointerUp={(e) => record(Number((e.target as HTMLInputElement).value))}
        onKeyUp={(e) => {
          if (["Enter", " ", "Home", "End"].includes(e.key)) record(Number((e.target as HTMLInputElement).value));
        }}
        className={`w-full cursor-pointer accent-[#233E33] disabled:cursor-not-allowed ${value === null ? "opacity-60" : ""} ${focusRing}`}
      />
      <div className={`flex justify-between text-xs ${muted}`} aria-hidden="true">
        <span>{lowLabel}</span>
        <span>{highLabel}</span>
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
  const button = `flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-full border border-[#C8CEBE] bg-white text-[#233E33] hover:bg-[#F4F6F0] disabled:cursor-not-allowed disabled:opacity-40 ${focusRing}`;
  return (
    <div className="flex items-center gap-3">
      <button type="button" className={button} onClick={() => step(-1)} disabled={value !== null && value <= min} aria-label={Q.age.decrease}>
        <Minus className="size-4" aria-hidden="true" />
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
          className={`w-20 rounded-xl border bg-white px-3 py-2 text-center text-xl font-semibold tabular-nums text-[#1C352D] ${
            invalid ? "border-[#B5654F]" : "border-[#C8CEBE]"
          } ${focusRing}`}
        />
        <span className={`text-sm ${muted}`}>{Q.age.unit}</span>
      </label>
      <button type="button" className={button} onClick={() => step(1)} disabled={value !== null && value >= max} aria-label={Q.age.increase}>
        <Plus className="size-4" aria-hidden="true" />
      </button>
    </div>
  );
}
