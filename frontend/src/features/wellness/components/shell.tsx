// The Wellness Snapshot's own surface: scoped design tokens, fonts and page background. Fonts are self-hosted
// (@fontsource) and imported only here; their families are applied only inside these wrappers.
import "@fontsource/bricolage-grotesque/500.css";
import "@fontsource/bricolage-grotesque/600.css";
import "@fontsource/bricolage-grotesque/700.css";
import "@fontsource/bricolage-grotesque/800.css";
import "@fontsource/plus-jakarta-sans/400.css";
import "@fontsource/plus-jakarta-sans/500.css";
import "@fontsource/plus-jakarta-sans/600.css";
import "@fontsource/plus-jakarta-sans/700.css";

import type { ReactNode } from "react";

import { WELLNESS_TOKENS, fontBody } from "./styles.ts";

/** A full Wellness Snapshot page: tokens, fonts, background, top spacing and the 1120px container. */
export function WellnessShell({ children, top }: { children: ReactNode; top?: ReactNode }) {
  return (
    <div style={WELLNESS_TOKENS} className={`min-h-screen bg-(--wn-bg) text-base text-(--wn-ink) antialiased ${fontBody}`}>
      <div className="mx-auto w-full max-w-[1184px] px-8 pb-16 pt-8 max-[600px]:px-5 max-[600px]:pt-5">
        {top}
        {children}
      </div>
    </div>
  );
}

/** Tokens and fonts without the page background, for a feature piece inside another page (Dashboard card). */
export function WellnessSurface({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <div style={WELLNESS_TOKENS} className={`text-(--wn-ink) ${fontBody} ${className}`}>
      {children}
    </div>
  );
}
