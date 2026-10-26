import { useCallback, useEffect, useRef, useState } from "react";

import { transcribeRecording, type Transcript } from "@/lib/voice/voice-client";

/*
 * Voice input: the mic is recorded in the page, then sent once to the backend,
 * which turns it into text and detects the language (Hindi, Marathi or
 * English). The recording is not stored anywhere. Stopping is deliberately
 * gentle, since someone may pause, cry or struggle to find words:
 *   - the room's background level is measured in the first half second, and
 *     anything clearly above it counts as speech (soft speech included),
 *   - 3s after the last speech it stops and sends the message ("Keep talking"
 *     or more speech cancels it),
 *   - it stops without sending after 10s if nothing was said yet,
 *   - and never runs longer than 2 minutes (no auto-send either).
 */

const SILENCE_AFTER_SPEECH_MS = 3_000;
/** "Pausing…" appears once a pause has lasted this long, so it doesn't flicker between words. */
const PAUSE_SHOW_AFTER_MS = 400;
const SILENCE_BEFORE_SPEECH_MS = 10_000;
const MAX_RECORDING_MS = 120_000;
const CALIBRATION_MS = 500;
const TICK_MS = 50;
/** Speech must last this many ticks in a row, so a click or a bump doesn't count. */
const SPEECH_TICKS = 2;
/** The lowest level that counts as speech, however quiet the room. Kept low for soft voices. */
const MIN_SPEECH_LEVEL = 0.006;

// Containers the backend accepts, in order of preference; Safari records MP4.
const MIME_TYPES = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4"];

function recordingSupported() {
  return (
    typeof window !== "undefined" &&
    typeof window.MediaRecorder !== "undefined" &&
    Boolean(navigator.mediaDevices?.getUserMedia)
  );
}

function pickMimeType() {
  return MIME_TYPES.find((type) => window.MediaRecorder.isTypeSupported?.(type)) ?? "";
}

function prefersReducedMotion() {
  return (
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

/** Shown softly under the input after a recording ends without text. */
export type SpeechInputNotice = "no-speech" | "denied" | "unavailable";

/** Why a recording ended: only "auto-send" (the pause after speech) sends the message. */
type StopReason = "manual" | "auto-send" | "idle" | "cap";

/** idle -> listening (recording) -> understanding (being transcribed) -> idle. */
export type SpeechInputPhase = "idle" | "listening" | "understanding";

/** Everything one recording needs released when it ends. */
interface Session {
  stream: MediaStream;
  recorder: MediaRecorder;
  context: AudioContext | null;
  timer: ReturnType<typeof setInterval> | null;
  chunks: Blob[];
  reason: StopReason;
  /** Speech heard since the last "Keep talking" (or the start). */
  heardSpeech: boolean;
  startedAt: number;
  waitingSince: number;
  lastSpeechAt: number;
  speechTicks: number;
  calibration: number[];
  threshold: number | null;
  level: number;
}

export function useSpeechInput({
  onTranscript,
}: {
  /** A recording was understood: its text and language, and whether to send it now. */
  onTranscript: (transcript: Transcript, autoSend: boolean) => void;
}) {
  const [supported] = useState(recordingSupported);
  const [phase, setPhase] = useState<SpeechInputPhase>("idle");
  const [notice, setNotice] = useState<SpeechInputNotice | null>(null);
  const [pausing, setPausing] = useState(false);
  // True while the level animation follows the voice (not under reduced motion).
  const [levelLive, setLevelLive] = useState(false);
  /** The element whose `--voice-level` CSS variable follows the mic (0–1). */
  const levelTargetRef = useRef<HTMLElement | null>(null);
  /** Ref callback for that element (the listening overlay's rings). */
  const setLevelTarget = useCallback((element: HTMLElement | null) => {
    levelTargetRef.current = element;
  }, []);

  const sessionRef = useRef<Session | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);
  const onTranscriptRef = useRef(onTranscript);
  useEffect(() => {
    onTranscriptRef.current = onTranscript;
  }, [onTranscript]);

  /** Stops the mic and the level meter (the recorder must already be stopping). */
  const release = useCallback((session: Session) => {
    if (session.timer) clearInterval(session.timer);
    session.timer = null;
    session.stream.getTracks().forEach((track) => track.stop());
    void session.context?.close().catch(() => {});
    session.context = null;
  }, []);

  const finishWith = useCallback((nextNotice: SpeechInputNotice | null) => {
    if (!mountedRef.current) return;
    setPhase("idle");
    setPausing(false);
    setLevelLive(false);
    setNotice(nextNotice);
  }, []);

  const stopFor = useCallback(
    (reason: StopReason) => {
      const session = sessionRef.current;
      if (!session || session.recorder.state === "inactive") return;
      session.reason = reason;
      if (session.timer) clearInterval(session.timer);
      session.timer = null;
      // onstop (below) sends what was recorded.
      session.recorder.stop();
    },
    [],
  );

  /** The user's own stop: the text goes in the box and nothing is sent. */
  const stop = useCallback(() => stopFor("manual"), [stopFor]);

  /** Cancels a pending auto-send and keeps listening (stopping after 10s of quiet). */
  const keepTalking = useCallback(() => {
    const session = sessionRef.current;
    if (!session || session.recorder.state !== "recording") return;
    session.heardSpeech = false;
    session.waitingSince = performance.now();
    setPausing(false);
  }, []);

  const transcribe = useCallback(
    async (recording: Blob, reason: StopReason) => {
      if (recording.size === 0) {
        finishWith("no-speech");
        return;
      }
      const controller = new AbortController();
      abortRef.current = controller;
      try {
        const transcript = await transcribeRecording(recording, controller.signal);
        if (controller.signal.aborted || !mountedRef.current) return;
        if (!transcript.text) {
          finishWith("no-speech");
          return;
        }
        // The words go in the box in the same render that ends "Understanding…".
        onTranscriptRef.current(transcript, reason === "auto-send");
        finishWith(null);
      } catch {
        // Failed or cancelled: nothing is sent; the soft "couldn't catch that" note shows.
        if (!controller.signal.aborted) finishWith("no-speech");
      } finally {
        if (abortRef.current === controller) abortRef.current = null;
      }
    },
    [finishWith],
  );

  /** One level reading: drives the animation and the start/pause/stop rules. */
  const tick = useCallback(
    (session: Session, analyser: AnalyserNode, samples: Float32Array<ArrayBuffer>, animate: boolean) => {
      analyser.getFloatTimeDomainData(samples);
      let sum = 0;
      for (const sample of samples) sum += sample * sample;
      const rms = Math.sqrt(sum / samples.length);
      const now = performance.now();

      if (animate) {
        // Ignore background hiss; ordinary speech reaches roughly 0.6–1.
        const target = Math.min(1, Math.max(0, (rms - 0.01) * 8));
        // Rise a little quickly, settle slowly: calm rather than jittery.
        session.level += (target - session.level) * (target > session.level ? 0.35 : 0.12);
        levelTargetRef.current?.style.setProperty("--voice-level", session.level.toFixed(3));
      }

      if (now - session.startedAt >= MAX_RECORDING_MS) {
        stopFor("cap");
        return;
      }

      if (session.threshold === null) {
        session.calibration.push(rms);
        if (now - session.startedAt < CALIBRATION_MS) return;
        // The quieter readings are the room, even if the user started speaking at once.
        const sorted = [...session.calibration].sort((a, b) => a - b);
        const ambient = sorted[Math.floor(sorted.length / 4)] ?? 0;
        session.threshold = Math.max(MIN_SPEECH_LEVEL, ambient * 1.8 + 0.004);
      }

      if (rms > session.threshold) {
        session.speechTicks += 1;
        if (session.speechTicks >= SPEECH_TICKS) {
          session.heardSpeech = true;
          session.lastSpeechAt = now;
          setPausing(false);
        }
        return;
      }
      session.speechTicks = 0;

      if (!session.heardSpeech) {
        if (now - session.waitingSince >= SILENCE_BEFORE_SPEECH_MS) stopFor("idle");
        return;
      }
      const quietFor = now - session.lastSpeechAt;
      if (quietFor >= SILENCE_AFTER_SPEECH_MS) stopFor("auto-send");
      else if (quietFor >= PAUSE_SHOW_AFTER_MS) setPausing(true);
    },
    [stopFor],
  );

  const start = useCallback(async () => {
    if (!recordingSupported() || sessionRef.current || abortRef.current) return;
    setNotice(null);
    setPausing(false);
    setPhase("listening");

    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true },
      });
    } catch (error) {
      const denied =
        error instanceof DOMException &&
        (error.name === "NotAllowedError" || error.name === "SecurityError");
      finishWith(denied ? "denied" : "unavailable");
      return;
    }
    if (!mountedRef.current) {
      stream.getTracks().forEach((track) => track.stop());
      return;
    }

    let recorder: MediaRecorder;
    try {
      const mimeType = pickMimeType();
      recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    } catch {
      stream.getTracks().forEach((track) => track.stop());
      finishWith("unavailable");
      return;
    }

    const now = performance.now();
    const session: Session = {
      stream,
      recorder,
      context: null,
      timer: null,
      chunks: [],
      reason: "manual",
      heardSpeech: false,
      startedAt: now,
      waitingSince: now,
      lastSpeechAt: now,
      speechTicks: 0,
      calibration: [],
      threshold: null,
      level: 0,
    };
    sessionRef.current = session;

    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) session.chunks.push(event.data);
    };
    recorder.onstop = () => {
      release(session);
      if (sessionRef.current !== session) return; // Abandoned (page left): nothing is sent.
      sessionRef.current = null;
      setPausing(false);
      setLevelLive(false);
      setPhase("understanding");
      // Some browsers label an audio-only recording "video/webm"; it is audio either way.
      const type = (recorder.mimeType || session.chunks[0]?.type || "audio/webm").replace(/^video\//, "audio/");
      void transcribe(new Blob(session.chunks, { type }), session.reason);
    };

    const AudioContextClass =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    try {
      recorder.start(1_000);
    } catch {
      sessionRef.current = null;
      release(session);
      finishWith("unavailable");
      return;
    }

    if (AudioContextClass) {
      try {
        const context = new AudioContextClass();
        session.context = context;
        const analyser = context.createAnalyser();
        analyser.fftSize = 1024;
        context.createMediaStreamSource(stream).connect(analyser);
        const samples = new Float32Array(analyser.fftSize);
        const animate = !prefersReducedMotion();
        setLevelLive(animate);
        session.timer = setInterval(() => tick(session, analyser, samples, animate), TICK_MS);
        return;
      } catch {
        // No level meter: fall through to fixed timing below.
      }
    }
    // Without a level meter there is no silence detection: only the user's stop or the cap ends it.
    session.timer = setInterval(() => {
      if (performance.now() - session.startedAt >= MAX_RECORDING_MS) stopFor("cap");
    }, 1_000);
  }, [finishWith, release, stopFor, tick, transcribe]);

  // Leaving the page ends any recording at once: mic off, nothing sent.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      abortRef.current?.abort();
      abortRef.current = null;
      const session = sessionRef.current;
      sessionRef.current = null;
      if (session) {
        if (session.recorder.state !== "inactive") session.recorder.stop();
        release(session);
      }
    };
  }, [release]);

  const clearNotice = useCallback(() => setNotice(null), []);

  return {
    supported,
    phase,
    listening: phase === "listening",
    notice,
    pausing,
    levelLive,
    setLevelTarget,
    clearNotice,
    start: () => void start(),
    stop,
    keepTalking,
  };
}
