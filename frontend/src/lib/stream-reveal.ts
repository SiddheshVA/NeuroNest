/**
 * Shows streamed reply text at a steady reading pace.
 *
 * Providers stream in very different chunk sizes: Groq sends a word or two at a
 * time, but Gemini sends 15–20 words per chunk and a whole reply in well under a
 * second, which looks like the reply appearing all at once. This only paces text
 * that has really arrived; it never invents or delays the start of a reply, and a
 * large backlog is shown faster so the screen never falls far behind.
 */

const FRAME_MS = 16;
/** Minimum speed: ~1.5 characters per frame ≈ 90 characters per second. */
const MIN_CHARS_PER_FRAME = 1.5;
/** A backlog is cleared in about this many frames (~2s), so it never lags far behind. */
const CATCH_UP_FRAMES = 120;

export interface StreamReveal {
  /** Adds newly received text. */
  push(text: string): void;
  /** Everything received so far. */
  received(): string;
  /** Resolves once all received text is on screen. */
  finished(): Promise<void>;
  /** Shows everything immediately and stops (e.g. on error). */
  showAll(): void;
}

export function createStreamReveal(onShow: (visibleText: string) => void): StreamReveal {
  let target = "";
  let shown = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let waiters: Array<() => void> = [];

  const done = () => Math.floor(shown) >= target.length;

  const resolveWaiters = () => {
    const pending = waiters;
    waiters = [];
    pending.forEach((resolve) => resolve());
  };

  const tick = () => {
    timer = null;
    const backlog = target.length - shown;
    if (backlog > 0) {
      shown = Math.min(target.length, shown + Math.max(MIN_CHARS_PER_FRAME, backlog / CATCH_UP_FRAMES));
      onShow(target.slice(0, Math.floor(shown)));
    }
    if (done()) {
      resolveWaiters();
    } else {
      // setTimeout rather than requestAnimationFrame so a background tab still finishes.
      timer = setTimeout(tick, FRAME_MS);
    }
  };

  const schedule = () => {
    if (timer === null && !done()) {
      timer = setTimeout(tick, FRAME_MS);
    }
  };

  return {
    push(text) {
      target += text;
      schedule();
    },
    received: () => target,
    finished() {
      if (done()) return Promise.resolve();
      return new Promise((resolve) => {
        waiters.push(resolve);
        schedule();
      });
    },
    showAll() {
      if (timer !== null) clearTimeout(timer);
      timer = null;
      shown = target.length;
      onShow(target);
      resolveWaiters();
    },
  };
}
