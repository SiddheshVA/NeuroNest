import { useEffect, useState, type RefObject } from "react";

/**
 * Drives a "voice level" animation from the microphone while mounted. The
 * level (0–1, smoothed so it looks calm) is written to the `--voice-level` CSS
 * variable on `targetRef`, without re-rendering. Audio is only measured in the
 * page, never recorded or sent. Every track is stopped on unmount.
 *
 * Returns false when the level isn't available (no permission, no Web Audio,
 * or reduced motion), so the caller can fall back to a steady pulse.
 */
export function useMicLevel(targetRef: RefObject<HTMLElement | null>) {
  const [live, setLive] = useState(false);

  useEffect(() => {
    const reducedMotion =
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const AudioContextClass =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (reducedMotion || !AudioContextClass || !navigator.mediaDevices?.getUserMedia) return;

    let cancelled = false;
    let stream: MediaStream | null = null;
    let context: AudioContext | null = null;
    let frame = 0;
    let level = 0;

    navigator.mediaDevices
      .getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
      .then((granted) => {
        if (cancelled) {
          granted.getTracks().forEach((track) => track.stop());
          return;
        }
        stream = granted;
        context = new AudioContextClass();
        const analyser = context.createAnalyser();
        analyser.fftSize = 512;
        context.createMediaStreamSource(granted).connect(analyser);
        const samples = new Float32Array(analyser.fftSize);

        const tick = () => {
          analyser.getFloatTimeDomainData(samples);
          let sum = 0;
          for (const sample of samples) sum += sample * sample;
          const rms = Math.sqrt(sum / samples.length);
          // Ignore background hiss; ordinary speech reaches roughly 0.6–1.
          const target = Math.min(1, Math.max(0, (rms - 0.01) * 8));
          // Rise a little quickly, settle slowly: calm rather than jittery.
          level += (target - level) * (target > level ? 0.2 : 0.06);
          targetRef.current?.style.setProperty("--voice-level", level.toFixed(3));
          frame = requestAnimationFrame(tick);
        };
        frame = requestAnimationFrame(tick);
        setLive(true);
      })
      .catch(() => {
        // No level available: the caller shows a steady pulse instead.
      });

    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
      stream?.getTracks().forEach((track) => track.stop());
      void context?.close().catch(() => {});
    };
  }, [targetRef]);

  return live;
}
