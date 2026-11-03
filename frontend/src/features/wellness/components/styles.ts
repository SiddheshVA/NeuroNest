// Design tokens and shared class names for the Wellness Snapshot screens. The tokens are CSS custom properties
// set by WellnessShell / WellnessSurface on the feature's own wrapper, so nothing outside the feature changes.
// Light only: the screens keep this calm light look inside their own surface. Motion is only added with
// motion-safe:, so reduced-motion users get none.

import type { CSSProperties } from "react";

/** Colour, radius and font tokens (checked for AA text contrast on their soft backgrounds). */
export const WELLNESS_TOKENS = {
  "--wn-bg": "#F2F6F5",
  "--wn-surface": "#FFFFFF",
  "--wn-ink": "#0F2A2E",
  "--wn-ink-2": "#47605F",
  "--wn-line": "#D9E3E1",
  "--wn-brand": "#0F6E6E",
  "--wn-brand-dark": "#0A5252",
  "--wn-brand-soft": "#DDF0EE",
  "--wn-tint-line": "#BFE0DC",
  "--wn-dash": "#B9CBC8",
  "--wn-low-text": "#14604F",
  "--wn-low-soft": "#DFF1EC",
  "--wn-low-fill": "#2E9C85",
  "--wn-mod-text": "#7A4A00",
  "--wn-mod-soft": "#FBEBD0",
  "--wn-mod-fill": "#DB9A33",
  "--wn-high-text": "#7E2A4C",
  "--wn-high-soft": "#F6E1EA",
  "--wn-high-fill": "#B5547D",
  "--wn-font-head": '"Bricolage Grotesque", ui-sans-serif, system-ui, sans-serif',
  "--wn-font-body": '"Plus Jakarta Sans", ui-sans-serif, system-ui, sans-serif',
  colorScheme: "light",
} as CSSProperties;

/** 2px brand outline, offset 2px, on keyboard focus. */
export const focusRing =
  "outline-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-solid focus-visible:outline-(--wn-brand)";

export const fontHead = "font-(family-name:--wn-font-head)";
export const fontBody = "font-(family-name:--wn-font-body)";

// ---- Type ----
export const h1 = `${fontHead} text-[40px] leading-[1.1] font-bold tracking-[-0.01em] text-(--wn-ink) max-[600px]:text-[32px]`;
export const h2 = `${fontHead} text-[22px] leading-tight font-semibold text-(--wn-ink)`;
export const h3 = `${fontHead} text-[18px] leading-snug font-semibold text-(--wn-ink)`;
export const eyebrow = "text-[13px] font-bold uppercase tracking-[0.08em] text-(--wn-brand)";
export const body = "text-base text-(--wn-ink)";
export const fine = "text-sm text-(--wn-ink-2)";
export const muted = "text-(--wn-ink-2)";

// ---- Surfaces ----
const shadow = "shadow-[0_1px_2px_rgba(15,42,46,.06),0_10px_28px_rgba(15,42,46,.06)]";
export const card = `rounded-[18px] border border-(--wn-line) bg-(--wn-surface) p-7 ${shadow} max-[600px]:p-[22px]`;
export const cardTint = "rounded-[18px] border border-(--wn-tint-line) bg-(--wn-brand-soft) p-5";
export const cardShadow = shadow;

// ---- Buttons (min 44px tall) ----
const buttonBase = `inline-flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-full px-5 text-[15px] font-semibold motion-safe:transition-colors motion-safe:duration-150 disabled:cursor-not-allowed disabled:opacity-50 ${focusRing}`;
export const primaryButton = `${buttonBase} bg-(--wn-brand) text-white hover:bg-(--wn-brand-dark)`;
export const ghostButton = `${buttonBase} border-[1.5px] border-(--wn-line) bg-(--wn-surface) text-(--wn-ink) hover:border-(--wn-brand) hover:text-(--wn-brand-dark)`;
export const dangerButton = `${buttonBase} bg-(--wn-high-text) text-white hover:brightness-90`;
export const linkButton = `inline-flex min-h-11 cursor-pointer items-center gap-1.5 rounded-sm font-semibold text-(--wn-brand-dark) underline underline-offset-4 hover:text-(--wn-brand) ${focusRing}`;
export const dangerLink = `inline-flex min-h-11 cursor-pointer items-center gap-1.5 rounded-sm font-semibold text-(--wn-high-text) underline underline-offset-4 hover:text-(--wn-high-fill) disabled:cursor-not-allowed disabled:opacity-50 ${focusRing}`;
export const iconButton = `inline-flex size-11 shrink-0 cursor-pointer items-center justify-center rounded-full motion-safe:transition-colors ${focusRing}`;

/** Band styles: text + soft background + fill for dots and gauge segments. Always paired with the band word. */
export const bandTone: Record<string, { pill: string; dot: string; fill: string; soft: string }> = {
  Low: {
    pill: "bg-(--wn-low-soft) text-(--wn-low-text)",
    dot: "bg-(--wn-low-fill)",
    fill: "var(--wn-low-fill)",
    soft: "bg-(--wn-low-soft) text-(--wn-low-text)",
  },
  Moderate: {
    pill: "bg-(--wn-mod-soft) text-(--wn-mod-text)",
    dot: "bg-(--wn-mod-fill)",
    fill: "var(--wn-mod-fill)",
    soft: "bg-(--wn-mod-soft) text-(--wn-mod-text)",
  },
  High: {
    pill: "bg-(--wn-high-soft) text-(--wn-high-text)",
    dot: "bg-(--wn-high-fill)",
    fill: "var(--wn-high-fill)",
    soft: "bg-(--wn-high-soft) text-(--wn-high-text)",
  },
};
