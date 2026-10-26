/**
 * Voice-mode preferences, remembered in localStorage. Every read and write is
 * guarded: when storage is blocked the defaults apply and nothing breaks.
 */

// The older "nuronest:voice-lang" key (a manual voice-input language) is no longer read:
// the language is now detected from the recording.
// v2: an earlier version switched read-aloud on by itself after using the mic and saved
// that, so the old "nuronest:voice-read-aloud" key is no longer read: everyone starts off.
const READ_ALOUD_KEY = "nuronest:voice-read-aloud-v2";
// v2: the privacy note changed when recordings started going to the backend, so it shows once more.
const NOTE_KEY = "nuronest:voice-note-dismissed-v2";
// The older "nuronest:voice-name" key (a browser voice) is no longer read.
const NEURO_VOICE_KEY = "nuronest:neuro-voice";

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Storage unavailable: the choice just lasts for this visit.
  }
}

export function readReadAloud(): boolean {
  return read(READ_ALOUD_KEY) === "on";
}

export function writeReadAloud(on: boolean) {
  write(READ_ALOUD_KEY, on ? "on" : "off");
}

/** Neuro's spoken voice (Sarvam). Only these two are offered. */
export type NeuroVoice = "aarti" | "anand";

export const NEURO_VOICES: { value: NeuroVoice; label: string; detail: string }[] = [
  { value: "aarti", label: "Aarti", detail: "Warm, calm female voice" },
  { value: "anand", label: "Anand", detail: "Gentle, steady male voice" },
];

export const DEFAULT_NEURO_VOICE: NeuroVoice = "aarti";

// Also kept in memory, so the choice holds for this visit even when storage is blocked.
let currentNeuroVoice: NeuroVoice | null = null;

/** The voice the user chose, or null before they have chosen one. */
export function readNeuroVoice(): NeuroVoice | null {
  if (currentNeuroVoice) return currentNeuroVoice;
  const stored = read(NEURO_VOICE_KEY);
  return NEURO_VOICES.find((option) => option.value === stored)?.value ?? null;
}

export function writeNeuroVoice(voice: NeuroVoice) {
  currentNeuroVoice = voice;
  write(NEURO_VOICE_KEY, voice);
}

export function readVoiceNoteDismissed(): boolean {
  return read(NOTE_KEY) === "1";
}

export function writeVoiceNoteDismissed() {
  write(NOTE_KEY, "1");
}
