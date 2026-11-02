import Link from "next/link";
import { ArrowDownRight, ArrowUpRight, ChevronRight, Stethoscope } from "lucide-react";

import { RESULT } from "../copy.ts";
import { BANDS, actionLinks, bandIndex, driverViews } from "../results.ts";
import type { Driver } from "../types.ts";
import { bandTone, focusRing, muted } from "./styles.ts";

/** The band as a chip: colour plus the written band, never colour alone. */
export function BandChip({ band }: { band: string }) {
  return (
    <span className={`inline-flex rounded-full px-3 py-1 text-xs font-semibold ${bandTone[band] ?? "bg-[#EEF3EC] text-[#233E33]"}`}>
      {band}
    </span>
  );
}

/** A calm three-step scale with the current band marked by position, weight and a written label. */
export function BandScale({ band }: { band: string }) {
  const current = bandIndex(band);
  return (
    <div role="img" aria-label={RESULT.scaleLabel(band)}>
      <ol className="grid grid-cols-3 gap-1.5" aria-hidden="true">
        {BANDS.map((b, i) => {
          const active = i === current;
          return (
            <li key={b} className="flex flex-col items-center gap-1.5">
              <span
                className={`h-3 w-full rounded-full ${bandTone[b]} ${active ? "ring-2 ring-[#233E33] ring-offset-2" : "opacity-60"}`}
              />
              <span className={`text-xs ${active ? "font-semibold text-[#1C352D]" : muted}`}>
                {active ? `▲ ${b}` : b}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/** Top three drivers with up/down arrows and friendly labels. */
export function DriverList({ drivers, kind }: { drivers: Driver[]; kind: "mood" | "sleep" }) {
  const views = driverViews(drivers, kind);
  if (views.length === 0) return <p className={`text-sm ${muted}`}>{RESULT.driversEmpty}</p>;
  return (
    <ul className="flex flex-col gap-2">
      {views.map((d) => (
        <li key={d.key} className="flex items-center gap-3 rounded-xl bg-[#F5F7F2] px-3 py-2.5">
          <span
            className={`flex size-8 shrink-0 items-center justify-center rounded-full ${
              d.direction === "up" ? "bg-[#EFE3C6] text-[#4A3A12]" : "bg-[#DCE8DA] text-[#1F3A2C]"
            }`}
          >
            {d.direction === "up" ? (
              <ArrowUpRight className="size-4" aria-hidden="true" />
            ) : (
              <ArrowDownRight className="size-4" aria-hidden="true" />
            )}
          </span>
          <span className="min-w-0 text-sm">
            <span className="font-semibold text-[#1C352D]">{d.label}</span>{" "}
            <span className={muted}>{d.phrase}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}

/** Suggested next steps from the backend's codes; unknown codes never render. */
export function ActionList({ codes }: { codes: string[] }) {
  const links = actionLinks(codes);
  if (links.length === 0) return null;
  return (
    <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      {links.map((a) => (
        <li key={a.code}>
          {a.href ? (
            <Link
              href={a.href}
              className={`group flex h-full items-center gap-3 rounded-2xl border border-[#E5E8E1] bg-white p-4 motion-safe:transition-colors hover:border-[#CBD4C5] hover:bg-[#F5F7F2] ${focusRing}`}
            >
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-semibold text-[#1C352D]">{a.label}</span>
                <span className={`block text-xs ${muted}`}>{a.description}</span>
              </span>
              <ChevronRight className="size-4 shrink-0 text-[#4A5750] group-hover:text-[#233E33]" aria-hidden="true" />
            </Link>
          ) : (
            <div className="flex h-full items-center gap-3 rounded-2xl border border-[#E5E8E1] bg-[#FBF9F4] p-4">
              <Stethoscope className="size-5 shrink-0 text-[#233E33]" aria-hidden="true" />
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-semibold text-[#1C352D]">{a.label}</span>
                <span className={`block text-xs ${muted}`}>{a.description}</span>
              </span>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
