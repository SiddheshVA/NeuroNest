import { type CSSProperties } from "react";
import MicRounded from "@mui/icons-material/MicRounded";

import { cn } from "@/lib/utils";

/** Rings around the mic, from inner to outer: size in px and how far each grows with the voice. */
const RINGS = [
  { size: 104, grow: 0.18, delay: "0ms" },
  { size: 136, grow: 0.28, delay: "400ms" },
  { size: 168, grow: 0.38, delay: "800ms" },
];

/**
 * Shown over the message area while the mic is on: a mic in pulsing rings that
 * follow the voice and, during the pause before auto-send, a "Keep talking"
 * button. Once recording ends it says "Understanding…" while the words are
 * worked out.
 */
export function NuroListeningOverlay({
  understanding,
  levelLive,
  levelRef,
  pausing,
  onStop,
  onKeepTalking,
}: {
  /** The recording has ended and is being turned into text. */
  understanding: boolean;
  /** The rings follow the voice level; otherwise they pulse calmly. */
  levelLive: boolean;
  /** Receives the element whose `--voice-level` follows the mic. */
  levelRef: (element: HTMLElement | null) => void;
  pausing: boolean;
  onStop: () => void;
  onKeepTalking: () => void;
}) {
  const live = levelLive && !understanding;

  return (
    <div className="pointer-events-auto absolute inset-0 z-20 flex flex-col items-center justify-center gap-4 bg-background/75 px-6 text-center backdrop-blur-[1px]">
      <div
        ref={levelRef}
        className="relative flex size-44 items-center justify-center"
        style={{ "--voice-level": 0 } as CSSProperties}
      >
        {RINGS.map((ring) => (
          <span
            key={ring.size}
            aria-hidden="true"
            className={cn(
              "absolute rounded-full bg-primary/10 ring-1 ring-primary/15",
              live
                ? "transition-transform duration-150 ease-out motion-reduce:transition-none"
                : "animate-[neuro-listen-pulse_2.8s_ease-in-out_infinite] motion-reduce:animate-none",
            )}
            style={{
              width: ring.size,
              height: ring.size,
              animationDelay: live ? undefined : ring.delay,
              transform: live ? `scale(calc(1 + var(--voice-level) * ${ring.grow}))` : undefined,
            }}
          />
        ))}

        <button
          type="button"
          onClick={onStop}
          disabled={understanding}
          aria-label="Stop listening"
          className="relative flex size-20 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg transition-colors outline-none hover:bg-primary/90 focus-visible:ring-4 focus-visible:ring-ring/50 disabled:cursor-default disabled:hover:bg-primary"
        >
          <MicRounded sx={{ fontSize: 36 }} />
        </button>
      </div>

      <p className="text-sm font-medium text-primary">
        {understanding ? "Understanding…" : "Listening… take your time"}
      </p>

      {/* Fixed height so the layout doesn't jump when the pause controls appear. */}
      <div className="flex h-9 items-center gap-3">
        {pausing && !understanding ? (
          <>
            <span className="text-xs text-muted-foreground">Pausing…</span>
            <button
              type="button"
              onClick={onKeepTalking}
              className="rounded-full border border-border bg-background px-3.5 py-1.5 text-sm text-foreground transition-colors hover:border-primary/40 hover:bg-primary/5"
            >
              Keep talking
            </button>
          </>
        ) : null}
      </div>
    </div>
  );
}
