import type { ScoreRange } from "@/lib/assessments/ranges";

interface ScoreRangeCardProps {
  ranges: ScoreRange[];
  currentScore: number;
}

/**
 * "Understanding your score" box, reused across every assessment result: one horizontal bar with a
 * segment per band (equal widths, so every label stays readable on a phone), the person's band
 * filled in, and each band's name and range below. Generic on purpose — it only knows about
 * {min, max, label} ranges and a score to place within them, never about which assessment it's showing.
 */
export function ScoreRangeCard({ ranges, currentScore }: ScoreRangeCardProps) {
  return (
    <section
      aria-labelledby="score-range-heading"
      className="mb-8 rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8"
    >
      <h2 id="score-range-heading" className="mb-5 font-heading text-lg font-normal text-[#1C352D] sm:text-xl">
        Understanding your score
      </h2>
      <ol
        className="grid gap-x-2.5 sm:gap-x-1.5"
        style={{ gridTemplateColumns: `repeat(${ranges.length}, minmax(0, 1fr))` }}
      >
        {ranges.map((range, index) => {
          const isCurrent = currentScore >= range.min && currentScore <= range.max;
          const rounded = index === 0 ? "rounded-l-full" : index === ranges.length - 1 ? "rounded-r-full" : "";
          return (
            <li key={range.levelKey} aria-current={isCurrent ? "true" : undefined} className="flex flex-col gap-2">
              <span
                aria-hidden="true"
                className={`h-3 ${rounded} ${isCurrent ? "bg-[#233E33]" : "bg-[#E2E6DD]"}`}
              />
              <span className="flex flex-col gap-0.5 text-[0.7rem] leading-snug min-[400px]:text-xs sm:text-sm">
                <span className={isCurrent ? "font-semibold text-[#1C352D]" : "text-[#4A5750]"}>{range.label}</span>
                <span className="tabular-nums text-[#4A5750]">
                  {range.min}–{range.max}
                </span>
                {isCurrent && (
                  <span className="text-[0.7rem] font-semibold uppercase tracking-[0.1em] text-[#233E33]">
                    Your range
                  </span>
                )}
              </span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
