import Link from "next/link";
import {
  ArrowDownRight,
  ArrowRight,
  ArrowUpRight,
  BedDouble,
  ChevronRight,
  ClipboardCheck,
  History,
  Stethoscope,
  UserRound,
  Wind,
  type LucideIcon,
} from "lucide-react";

import { RESULT } from "../copy.ts";
import { actionLinks, driverViews, type DriverView } from "../results.ts";
import type { Driver } from "../types.ts";
import { focusRing } from "./styles.ts";

/** One driver: icon tile (Raises = amber, up-right; Eases = green, down-right), label, value text and a tag. */
export function DriverRow({ driver }: { driver: DriverView }) {
  const up = driver.direction === "up";
  const tag = up ? RESULT.driverTag.up : RESULT.driverTag.down;
  return (
    <li className="flex items-center gap-3">
      <span
        aria-hidden="true"
        className={`flex size-10 shrink-0 items-center justify-center rounded-[12px] ${
          up ? "bg-(--wn-mod-soft) text-(--wn-mod-text)" : "bg-(--wn-low-soft) text-(--wn-low-text)"
        }`}
      >
        {up ? <ArrowUpRight className="size-5" strokeWidth={2} /> : <ArrowDownRight className="size-5" strokeWidth={2} />}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-[15px] font-bold text-(--wn-ink)">{driver.label}</span>
        {driver.valueText ? <span className="block text-sm text-(--wn-ink-2)">{driver.valueText}</span> : null}
      </span>
      <span
        className={`shrink-0 rounded-full px-2.5 py-0.5 text-xs font-bold ${
          up ? "bg-(--wn-mod-soft) text-(--wn-mod-text)" : "bg-(--wn-low-soft) text-(--wn-low-text)"
        }`}
      >
        {tag}
        <span className="sr-only">: {driver.phrase}</span>
      </span>
    </li>
  );
}

/** Top three drivers. */
export function DriverList({ drivers, kind = "mood" }: { drivers: Driver[]; kind?: "mood" | "sleep" }) {
  const views = driverViews(drivers, kind);
  if (views.length === 0) return <p className="text-[15px] text-(--wn-ink-2)">{RESULT.driversEmpty}</p>;
  return (
    <ul className="flex flex-col gap-3">
      {views.map((d) => (
        <DriverRow key={d.key} driver={d} />
      ))}
    </ul>
  );
}

const ACTION_ICONS: Record<string, LucideIcon> = {
  ASSESSMENT_PHQ9: ClipboardCheck,
  ASSESSMENT_PSS10: ClipboardCheck,
  FIND_PROFESSIONAL: UserRound,
  RELAXATION: Wind,
  RELAXATION_SLEEP: BedDouble,
  SEE_DOCTOR: Stethoscope,
  KEEP_TRACKING: History,
};

/**
 * Suggested next steps from the backend's codes, as cards (3 columns; compact rows with a chevron on phones).
 * Unknown codes never render. SEE_DOCTOR has no page in the app, so it is a plain card, not a link.
 */
export function ActionGrid({ codes }: { codes: string[] }) {
  const links = actionLinks(codes);
  if (links.length === 0) return null;
  return (
    <ul className="grid grid-cols-3 gap-4 max-[960px]:grid-cols-2 max-[600px]:grid-cols-1 max-[600px]:gap-2.5">
      {links.map((a) => {
        const Icon = ACTION_ICONS[a.code] ?? ArrowRight;
        const inner = (
          <>
            <span
              aria-hidden="true"
              className="flex size-11 shrink-0 items-center justify-center rounded-[12px] bg-(--wn-brand-soft) text-(--wn-brand)"
            >
              <Icon className="size-5" strokeWidth={2} />
            </span>
            <span className="flex min-w-0 flex-1 flex-col gap-1">
              <span className="text-[16px] font-bold text-(--wn-ink)">{a.label}</span>
              <span className="text-sm text-(--wn-ink-2)">{a.description}</span>
            </span>
            {a.href ? (
              <>
                <span aria-hidden="true" className="mt-auto flex items-center gap-1.5 text-sm font-bold text-(--wn-brand-dark) max-[600px]:hidden">
                  {RESULT.actionGo}
                  <ArrowRight className="size-4 motion-safe:transition-transform motion-safe:group-hover:translate-x-0.5" strokeWidth={2} />
                </span>
                <ChevronRight aria-hidden="true" className="hidden size-5 shrink-0 text-(--wn-ink-2) max-[600px]:block" strokeWidth={2} />
              </>
            ) : null}
          </>
        );
        const layout =
          "flex h-full flex-col gap-3 rounded-[18px] border border-(--wn-line) bg-(--wn-surface) p-5 max-[600px]:flex-row max-[600px]:items-center max-[600px]:p-3.5";
        return (
          <li key={a.code}>
            {a.href ? (
              <Link
                href={a.href}
                className={`group ${layout} motion-safe:transition-[border-color,box-shadow] motion-safe:duration-150 hover:border-(--wn-brand) hover:shadow-[0_1px_2px_rgba(15,42,46,.06),0_10px_28px_rgba(15,42,46,.06)] ${focusRing}`}
              >
                {inner}
              </Link>
            ) : (
              <div className={layout}>{inner}</div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
