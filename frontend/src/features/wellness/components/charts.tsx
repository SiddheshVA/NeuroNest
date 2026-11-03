// Inline SVG / CSS graphics (no chart library): the band gauge, range meters and the trend sparkline. Each one
// has a text alternative (role="img" + aria-label), and the band is always also written out.
import { RESULT } from "../copy.ts";
import { BANDS, bandIndex, meterPercent } from "../results.ts";
import { bandTone } from "./styles.ts";

/** Segment widths of the gauge (Low, Moderate, High). Visual only; they are not probabilities. */
const GAUGE_WIDTHS = [34, 46, 20];

/** Three rounded segments with a "You" marker above the active band. */
export function BandGauge({ band }: { band: string }) {
  const active = bandIndex(band);
  return (
    <div role="img" aria-label={RESULT.scaleLabel(band)} className="pt-7">
      <div className="flex gap-1.5" aria-hidden="true">
        {BANDS.map((b, i) => (
          <div key={b} className="relative flex flex-col gap-2" style={{ width: `${GAUGE_WIDTHS[i]}%` }}>
            {i === active ? (
              <span className="absolute -top-7 left-1/2 flex -translate-x-1/2 flex-col items-center text-xs font-bold text-(--wn-ink)">
                {RESULT.you}
                <svg width="10" height="6" viewBox="0 0 10 6" className="mt-0.5 fill-(--wn-ink)">
                  <path d="M0 0h10L5 6z" />
                </svg>
              </span>
            ) : null}
            <span
              className={`block h-3.5 rounded-full ${i === active ? "" : "opacity-35"}`}
              style={{ background: bandTone[b].fill }}
            />
            <span className={`text-xs ${i === active ? "font-bold text-(--wn-ink)" : "text-(--wn-ink-2)"}`}>{b}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** A range meter: 12px track, the healthy zone in green, the user's value as a white dot with an ink ring. */
export function Meter({
  label,
  valueText,
  value,
  scale,
  minLabel,
  maxLabel,
  zoneLabel,
  ariaLabel,
}: {
  label: string;
  valueText: string;
  value: number;
  scale: { min: number; max: number; zoneFrom: number; zoneTo: number };
  minLabel: string;
  maxLabel: string;
  zoneLabel: string;
  ariaLabel: string;
}) {
  const at = meterPercent(value, scale);
  const zoneStart = meterPercent(scale.zoneFrom, scale);
  const zoneEnd = meterPercent(scale.zoneTo, scale);
  return (
    <div role="img" aria-label={ariaLabel} className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between gap-3 text-sm" aria-hidden="true">
        <span className="font-semibold text-(--wn-ink)">{label}</span>
        <span className="font-bold tabular-nums text-(--wn-ink)">{valueText}</span>
      </div>
      <div className="relative h-3 rounded-full bg-(--wn-line)" aria-hidden="true">
        <span
          className="absolute inset-y-0 rounded-full bg-(--wn-low-fill)/45"
          style={{ left: `${zoneStart}%`, width: `${zoneEnd - zoneStart}%` }}
        />
        <span
          className="absolute top-1/2 size-[18px] -translate-x-1/2 -translate-y-1/2 rounded-full border-4 border-(--wn-ink) bg-white"
          style={{ left: `${at}%` }}
        />
      </div>
      <div className="flex justify-between text-xs text-(--wn-ink-2)" aria-hidden="true">
        <span>{minLabel}</span>
        <span>{maxLabel}</span>
      </div>
      <p className="flex items-center gap-1.5 text-xs font-semibold text-(--wn-low-text)" aria-hidden="true">
        <span className="h-2 w-4 rounded-full bg-(--wn-low-fill)/45" />
        {zoneLabel}
      </p>
    </div>
  );
}

/** Band trend over the last snapshots: guide lines for High / Moderate / Low, band-coloured dots, last ringed. */
export function SparklineTrend({ bands, summary }: { bands: string[]; summary: string }) {
  const W = 260;
  const H = 84;
  const left = 64;
  const right = 12;
  const rowY = (i: number) => 14 + (2 - i) * 28; // High on top.
  const points = bands
    .map((b, i) => ({ band: b, idx: bandIndex(b), i }))
    .filter((p) => p.idx >= 0)
    .map((p, n, all) => ({
      ...p,
      x: all.length === 1 ? (left + W - right) / 2 : left + (n * (W - left - right)) / (all.length - 1),
      y: rowY(p.idx),
    }));
  return (
    <svg role="img" aria-label={summary} viewBox={`0 0 ${W} ${H}`} className="h-auto w-full max-w-[420px]">
      {BANDS.map((b, i) => (
        <g key={b}>
          <line x1={left - 6} x2={W - right + 6} y1={rowY(i)} y2={rowY(i)} stroke="var(--wn-line)" strokeWidth="1" strokeDasharray="3 4" />
          <text x={0} y={rowY(i) + 4} fontSize="11" fill="var(--wn-ink-2)">
            {b}
          </text>
        </g>
      ))}
      {points.length > 1 ? (
        <polyline
          points={points.map((p) => `${p.x},${p.y}`).join(" ")}
          fill="none"
          stroke="var(--wn-brand)"
          strokeWidth="2"
          strokeLinejoin="round"
          strokeLinecap="round"
        />
      ) : null}
      {points.map((p, n) => (
        <g key={`${p.i}-${p.band}`}>
          {n === points.length - 1 ? <circle cx={p.x} cy={p.y} r="8" fill="none" stroke="var(--wn-ink)" strokeWidth="2" /> : null}
          <circle cx={p.x} cy={p.y} r="5" fill={bandTone[p.band].fill} stroke="white" strokeWidth="1.5" />
        </g>
      ))}
    </svg>
  );
}
