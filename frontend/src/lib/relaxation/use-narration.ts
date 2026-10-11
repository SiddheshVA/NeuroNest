import { useCallback, useEffect, useRef, useState } from "react";

/**
 * One thing to narrate. `key` must change whenever a new step starts (including a
 * restart of the same step), which is what triggers speaking.
 */
export type NarrationCue = { key: string; text: string };

/** Mirrors the activity's own timer: narration never runs a timer of its own. */
export type NarrationPlayback = "playing" | "paused" | "stopped";

export type Narration = {
  /** False when the browser has no speech synthesis: hide the controls, stay silent. */
  supported: boolean;
  muted: boolean;
  toggleMute: () => void;
  /** Speaks the current step again from the start. */
  replay: () => void;
  canReplay: boolean;
};

const RATE = 0.85;
const PITCH = 1;
// Browsers don't expose a voice's gender; these name hints cover common English female voices.
const FEMALE_VOICE_HINT =
  /female|woman|zira|samantha|susan|hazel|heera|veena|neerja|karen|moira|tessa|fiona|serena|victoria|allison|ava|kate|libby|sonia|aria|jenny/i;

function getSpeechSynthesis(): SpeechSynthesis | null {
  if (typeof window === "undefined" || !("speechSynthesis" in window)
      || typeof window.SpeechSynthesisUtterance === "undefined") {
    return null;
  }
  return window.speechSynthesis;
}

/** An en-IN voice, else an English female voice, else the browser default (null). */
export function pickNarrationVoice(voices: SpeechSynthesisVoice[]): SpeechSynthesisVoice | null {
  const lang = (voice: SpeechSynthesisVoice) => voice.lang.replace("_", "-").toLowerCase();
  return voices.find((voice) => lang(voice) === "en-in")
    ?? voices.find((voice) => lang(voice).startsWith("en") && FEMALE_VOICE_HINT.test(voice.name))
    ?? null;
}

/**
 * Narrates activity steps with the browser's Web Speech API.
 *
 * - Speaks `cue.text` when `cue.key` changes while playing; the activity's step timer
 *   provides the silence that follows.
 * - Cancels before every new utterance, so speech never overlaps.
 * - Pauses/resumes with the activity; cancels when it stops or the component unmounts.
 * - Every speech call is guarded: if anything fails, the activity simply runs silently.
 *
 * Components depend only on this hook's inputs and `Narration` result, so it can later be
 * backed by pre-recorded audio without changing them.
 */
export function useNarration({
  cue,
  playback,
}: {
  cue: NarrationCue | null;
  playback: NarrationPlayback;
}): Narration {
  const [synth] = useState(getSpeechSynthesis);
  const [muted, setMuted] = useState(false);
  const voiceRef = useRef<SpeechSynthesisVoice | null>(null);
  // Keeps the current utterance referenced: some browsers garbage-collect it mid-speech.
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null);
  const spokenKeyRef = useRef<string | null>(null);

  const cueKey = cue?.key ?? null;
  const cueText = cue?.text ?? "";

  // Voices often load after the page does; pick again whenever the list changes.
  useEffect(() => {
    if (!synth) return;
    const pick = () => {
      try {
        voiceRef.current = pickNarrationVoice(synth.getVoices());
      } catch {
        voiceRef.current = null;
      }
    };
    pick();
    synth.addEventListener?.("voiceschanged", pick);
    return () => synth.removeEventListener?.("voiceschanged", pick);
  }, [synth]);

  const speak = useCallback(
    (text: string) => {
      if (!synth || !text) return;
      try {
        synth.cancel();
        // Chrome keeps the queue paused after cancel() if it was paused; unpause first.
        if (synth.paused) synth.resume();
        const utterance = new SpeechSynthesisUtterance(text);
        utterance.rate = RATE;
        utterance.pitch = PITCH;
        const voice = voiceRef.current;
        if (voice) {
          utterance.voice = voice;
          utterance.lang = voice.lang;
        }
        utteranceRef.current = utterance;
        synth.speak(utterance);
      } catch {
        // Narration is optional; never let it break the activity.
      }
    },
    [synth],
  );

  // A new step started: speak it once. The key is only "used up" while playing, so a step
  // shown before the timer starts is still spoken when it does.
  useEffect(() => {
    if (!synth || playback !== "playing" || cueKey === null || cueKey === spokenKeyRef.current) {
      return;
    }
    spokenKeyRef.current = cueKey;
    if (muted) {
      try {
        synth.cancel();
      } catch {
        // ignore
      }
      return;
    }
    speak(cueText);
  }, [synth, playback, cueKey, cueText, muted, speak]);

  // Follow the activity: pause, resume from the same point, or stop.
  useEffect(() => {
    if (!synth) return;
    try {
      if (playback === "paused") {
        if (synth.speaking && !synth.paused) synth.pause();
      } else if (playback === "playing") {
        if (synth.paused) synth.resume();
      } else {
        synth.cancel();
      }
    } catch {
      // ignore
    }
  }, [synth, playback]);

  // Exit, end, navigation away: stop speaking. Forgetting the spoken key lets a remount
  // (e.g. React Strict Mode's dev double-mount) speak the current step again.
  useEffect(
    () => () => {
      spokenKeyRef.current = null;
      try {
        synth?.cancel();
      } catch {
        // ignore
      }
    },
    [synth],
  );

  const toggleMute = useCallback(() => {
    if (!muted) {
      try {
        synth?.cancel();
      } catch {
        // ignore
      }
    }
    setMuted(!muted);
  }, [muted, synth]);

  const canReplay = synth !== null && !muted && playback === "playing" && cueText !== "";
  const replay = useCallback(() => {
    if (canReplay) speak(cueText);
  }, [canReplay, cueText, speak]);

  return { supported: synth !== null, muted, toggleMute, replay, canReplay };
}
