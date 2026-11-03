// Small building blocks of the Wellness Snapshot look: cards, pills, insight and disclaimer strips, the inline
// delete confirmation. Styling uses the scoped tokens from styles.ts (set by WellnessShell / WellnessSurface).
import { useEffect, useId, useRef, type ReactNode } from "react";
import { Info, Lightbulb } from "lucide-react";

import { bandTone, card, cardTint, dangerButton, ghostButton, h3 } from "./styles.ts";

export function Card({
  children,
  className = "",
  as: Tag = "section",
  ...rest
}: {
  children: ReactNode;
  className?: string;
  as?: "section" | "div" | "article";
} & React.HTMLAttributes<HTMLElement>) {
  return (
    <Tag className={`${card} ${className}`} {...rest}>
      {children}
    </Tag>
  );
}

export function CardTint({ children, className = "", ...rest }: { children: ReactNode; className?: string } & React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div className={`${cardTint} ${className}`} {...rest}>
      {children}
    </div>
  );
}

export type PillVariant = "low" | "moderate" | "high" | "brand" | "ghost";

const PILL_VARIANTS: Record<PillVariant, string> = {
  low: bandTone.Low.pill,
  moderate: bandTone.Moderate.pill,
  high: bandTone.High.pill,
  brand: "bg-(--wn-brand-soft) text-(--wn-brand-dark)",
  ghost: "border border-(--wn-line) bg-(--wn-surface) text-(--wn-ink-2)",
};

const DOT: Partial<Record<PillVariant, string>> = {
  low: bandTone.Low.dot,
  moderate: bandTone.Moderate.dot,
  high: bandTone.High.dot,
};

export function Pill({
  variant,
  children,
  large = false,
  icon,
  className = "",
}: {
  variant: PillVariant;
  children: ReactNode;
  large?: boolean;
  /** Replaces the band dot (e.g. a check icon on the brand pill). */
  icon?: ReactNode;
  className?: string;
}) {
  const dot = DOT[variant];
  return (
    <span
      className={`inline-flex items-center gap-2 rounded-full font-semibold ${
        large ? "px-4 py-1.5 text-[18px]" : "px-3 py-1 text-[13px]"
      } ${PILL_VARIANTS[variant]} ${className}`}
    >
      {icon ?? (dot ? <span aria-hidden="true" className={`${large ? "size-2.5" : "size-2"} shrink-0 rounded-full ${dot}`} /> : null)}
      {children}
    </span>
  );
}

const BAND_VARIANT: Record<string, PillVariant> = { Low: "low", Moderate: "moderate", High: "high" };

/** The band as a pill: colour dot plus the written band, never colour alone. */
export function BandPill({ band, large }: { band: string; large?: boolean }) {
  return (
    <Pill variant={BAND_VARIANT[band] ?? "ghost"} large={large}>
      {band}
    </Pill>
  );
}

/** A tinted panel with an icon and live text (announced politely when it changes). */
export function InsightPanel({ title, children, icon }: { title?: string; children: ReactNode; icon?: ReactNode }) {
  return (
    <CardTint className="flex gap-3" aria-live="polite">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-(--wn-surface) text-(--wn-brand)">
        {icon ?? <Lightbulb className="size-[18px]" strokeWidth={2} aria-hidden="true" />}
      </span>
      <div className="min-w-0 text-[15px] leading-relaxed text-(--wn-ink)">
        {title ? <p className="font-semibold">{title}</p> : null}
        <p className="text-(--wn-ink-2)">{children}</p>
      </div>
    </CardTint>
  );
}

/** Dashed note with an info icon: the API's disclaimer plus whatever follows it (e.g. a crisis-support link). */
export function DisclaimerStrip({
  children,
  label,
  after,
}: {
  children: ReactNode;
  label?: string;
  after?: ReactNode;
}) {
  return (
    <aside
      aria-label={label}
      className="flex gap-3 rounded-[14px] border border-dashed border-(--wn-dash) px-4 py-3 text-sm leading-relaxed text-(--wn-ink-2)"
    >
      <Info className="mt-0.5 size-[18px] shrink-0 text-(--wn-brand)" strokeWidth={2} aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <div>{children}</div>
        {after}
      </div>
    </aside>
  );
}

/**
 * Inline confirmation (not a modal): title, text, optional extra content, Keep it / Delete. The title is
 * focused when it opens so keyboard and screen-reader users land on it.
 */
export function ConfirmDelete({
  title,
  text,
  children,
  error,
  cancelLabel,
  confirmLabel,
  busyLabel,
  busy,
  confirmDisabled,
  onCancel,
  onConfirm,
}: {
  title: string;
  text: string;
  children?: ReactNode;
  error?: string;
  cancelLabel: string;
  confirmLabel: string;
  busyLabel: string;
  busy: boolean;
  confirmDisabled?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const titleId = useId();
  const textId = useId();
  const titleRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => titleRef.current?.focus(), []);
  return (
    <CardTint role="group" aria-labelledby={titleId} aria-describedby={textId} className="flex flex-col gap-3">
      <h3 ref={titleRef} id={titleId} tabIndex={-1} className={`${h3} outline-none`}>
        {title}
      </h3>
      <p id={textId} className="text-[15px] text-(--wn-ink-2)">
        {text}
      </p>
      {children}
      {error ? (
        <p role="alert" className="text-sm font-semibold text-(--wn-high-text)">
          {error}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-3">
        <button type="button" className={ghostButton} disabled={busy} onClick={onCancel}>
          {cancelLabel}
        </button>
        <button type="button" className={dangerButton} disabled={busy || confirmDisabled} onClick={onConfirm}>
          {busy ? busyLabel : confirmLabel}
        </button>
      </div>
    </CardTint>
  );
}
