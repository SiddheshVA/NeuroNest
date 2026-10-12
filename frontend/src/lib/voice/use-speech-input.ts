import { useCallback, useEffect, useRef, useState } from "react";

import type { RecognitionLang } from "@/lib/voice/voice-prefs";

/*
 * Speech-to-text with the browser's Web Speech API. NeuroNest never receives
 * or stores audio: the browser hands back text only. Stopping is deliberately
 * gentle, since someone may pause, cry or struggle to find words:
 *   - the browser's own end-of-speech stops are ignored (recognition restarts),
 *   - 3s after the last words it stops and asks for the message to be sent
 *     ("Keep talking" or more speech cancels it),
 *   - it stops without sending after 10s if nothing was said yet,
 *   - and never runs longer than 2 minutes (no auto-send either).
 */

const SILENCE_AFTER_SPEECH_MS = 3_000;
/** The pause controls appear once a pause has lasted this long, so they don't flicker between words. */
const COUNTDOWN_SHOW_AFTER_MS = 400;
const SILENCE_BEFORE_SPEECH_MS = 10_000;
const MAX_RECORDING_MS = 120_000;

// Minimal typings: the Web Speech recognition API isn't in TypeScript's DOM lib.
interface RecognitionResult {
  readonly isFinal: boolean;
  readonly [index: number]: { readonly transcript: string } | undefined;
}

interface RecognitionResultEvent extends Event {
  readonly resultIndex: number;
  readonly results: { readonly length: number; readonly [index: number]: RecognitionResult };
}

interface RecognitionErrorEvent extends Event {
  readonly error: string;
}

interface Recognition extends EventTarget {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  start(): void;
  stop(): void;
  abort(): void;
  onresult: ((event: RecognitionResultEvent) => void) | null;
  onerror: ((event: RecognitionErrorEvent) => void) | null;
  onend: (() => void) | null;
}

type RecognitionConstructor = new () => Recognition;

function getRecognitionConstructor(): RecognitionConstructor | null {
  if (typeof window === "undefined") return null;
  const speechWindow = window as unknown as {
    SpeechRecognition?: RecognitionConstructor;
    webkitSpeechRecognition?: RecognitionConstructor;
  };
  return speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition ?? null;
}

/** Shown softly under the input after a recording ends without text. */
export type SpeechInputNotice = "no-speech" | "denied" | "unavailable";

/** Why a recording ended: only "auto-send" (the pause after speech) sends the message. */
type StopReason = "manual" | "auto-send" | "idle" | "cap";

/** The visible part of the pause before auto-send; `key` changes each time it restarts. */
export interface SpeechCountdown {
  key: number;
  durationMs: number;
}

export function useSpeechInput({
  lang,
  onFinalText,
  onStopped,
}: {
  lang: RecognitionLang;
  /** Each finalised piece of speech, in order. */
  onFinalText: (text: string) => void;
  /** Called once when a recording ends, for any reason. */
  onStopped: (result: { autoSend: boolean }) => void;
}) {
  const [supported] = useState(() => getRecognitionConstructor() !== null);
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState("");
  const [notice, setNotice] = useState<SpeechInputNotice | null>(null);
  const [countdown, setCountdown] = useState<SpeechCountdown | null>(null);

  const recognitionRef = useRef<Recognition | null>(null);
  const keepListeningRef = useRef(false);
  const recognisedTextRef = useRef(false);
  const stopReasonRef = useRef<StopReason>("manual");
  const interimRef = useRef("");
  const endNoticeRef = useRef<SpeechInputNotice | null>(null);
  const silenceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const capTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const countdownTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const callbacksRef = useRef({ onFinalText, onStopped });
  useEffect(() => {
    callbacksRef.current = { onFinalText, onStopped };
  }, [onFinalText, onStopped]);

  const clearSilenceTimer = useCallback(() => {
    if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
    if (countdownTimerRef.current) clearTimeout(countdownTimerRef.current);
    silenceTimerRef.current = null;
    countdownTimerRef.current = null;
    setCountdown(null);
  }, []);

  const clearTimers = useCallback(() => {
    clearSilenceTimer();
    if (capTimerRef.current) clearTimeout(capTimerRef.current);
    capTimerRef.current = null;
  }, [clearSilenceTimer]);

  const stopFor = useCallback(
    (reason: StopReason) => {
      if (!recognitionRef.current) return;
      stopReasonRef.current = reason;
      keepListeningRef.current = false;
      clearTimers();
      // stop() (not abort()) so words already spoken are still finalised.
      recognitionRef.current.stop();
    },
    [clearTimers],
  );

  /** The user's own stop: the text stays in the box and nothing is sent. */
  const stop = useCallback(() => stopFor("manual"), [stopFor]);

  const armSilenceTimer = useCallback(
    (ms: number, reason: StopReason) => {
      clearSilenceTimer();
      silenceTimerRef.current = setTimeout(() => stopFor(reason), ms);
      if (reason === "auto-send") {
        countdownTimerRef.current = setTimeout(() => {
          setCountdown({ key: Date.now(), durationMs: ms - COUNTDOWN_SHOW_AFTER_MS });
        }, COUNTDOWN_SHOW_AFTER_MS);
      }
    },
    [clearSilenceTimer, stopFor],
  );

  /** Cancels a pending auto-send and keeps listening (stopping after 10s of quiet). */
  const keepTalking = useCallback(() => {
    if (!recognitionRef.current) return;
    armSilenceTimer(SILENCE_BEFORE_SPEECH_MS, "idle");
  }, [armSilenceTimer]);

  const commitInterim = useCallback(() => {
    const pending = interimRef.current.trim();
    interimRef.current = "";
    setInterim("");
    if (pending) {
      recognisedTextRef.current = true;
      callbacksRef.current.onFinalText(pending);
    }
  }, []);

  const start = useCallback(() => {
    const Constructor = getRecognitionConstructor();
    if (!Constructor || recognitionRef.current) return;

    setNotice(null);
    setInterim("");
    interimRef.current = "";
    recognisedTextRef.current = false;
    stopReasonRef.current = "manual";
    endNoticeRef.current = null;
    keepListeningRef.current = true;

    const recognition = new Constructor();
    recognition.lang = lang;
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onresult = (event) => {
      let finalText = "";
      let interimText = "";
      for (let i = event.resultIndex; i < event.results.length; i += 1) {
        const result = event.results[i];
        const transcript = result[0]?.transcript ?? "";
        if (result.isFinal) finalText += transcript;
        else interimText += transcript;
      }
      interimRef.current = interimText;
      setInterim(interimText);
      if (finalText.trim()) {
        recognisedTextRef.current = true;
        callbacksRef.current.onFinalText(finalText.trim());
      }
      // Any new words restart the pause (and hide a countdown that had begun).
      if (finalText.trim() || interimText.trim()) {
        armSilenceTimer(SILENCE_AFTER_SPEECH_MS, "auto-send");
      }
    };

    recognition.onerror = (event) => {
      if (event.error === "not-allowed" || event.error === "service-not-allowed") {
        endNoticeRef.current = "denied";
        keepListeningRef.current = false;
      } else if (
        event.error === "audio-capture" ||
        event.error === "network" ||
        event.error === "language-not-supported"
      ) {
        endNoticeRef.current = "unavailable";
        keepListeningRef.current = false;
      }
      // "no-speech" and "aborted" are followed by onend, which decides what's next.
    };

    recognition.onend = () => {
      // Words still in progress when the browser ended the session are kept.
      commitInterim();
      if (keepListeningRef.current) {
        try {
          // The browser stopped on a pause; the user didn't, so keep listening.
          recognition.start();
          return;
        } catch {
          // Couldn't restart: end the recording below.
        }
      }
      clearTimers();
      recognitionRef.current = null;
      keepListeningRef.current = false;
      setListening(false);
      const recognised = recognisedTextRef.current;
      setNotice(endNoticeRef.current ?? (recognised ? null : "no-speech"));
      callbacksRef.current.onStopped({
        autoSend:
          stopReasonRef.current === "auto-send" && recognised && endNoticeRef.current === null,
      });
    };

    try {
      recognition.start();
    } catch {
      keepListeningRef.current = false;
      setNotice("unavailable");
      return;
    }
    recognitionRef.current = recognition;
    setListening(true);
    armSilenceTimer(SILENCE_BEFORE_SPEECH_MS, "idle");
    capTimerRef.current = setTimeout(() => stopFor("cap"), MAX_RECORDING_MS);
  }, [armSilenceTimer, clearTimers, commitInterim, lang, stopFor]);

  // Leaving the page ends any recording at once.
  useEffect(
    () => () => {
      keepListeningRef.current = false;
      if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
      if (countdownTimerRef.current) clearTimeout(countdownTimerRef.current);
      if (capTimerRef.current) clearTimeout(capTimerRef.current);
      const recognition = recognitionRef.current;
      if (recognition) {
        recognition.onend = null;
        recognition.abort();
      }
    },
    [],
  );

  const clearNotice = useCallback(() => setNotice(null), []);

  return {
    supported,
    listening,
    interim,
    notice,
    countdown,
    clearNotice,
    start,
    stop,
    keepTalking,
  };
}
