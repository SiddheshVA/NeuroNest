/**
 * Voice-mode preferences, remembered in localStorage. Every read and write is
 * guarded: when storage is blocked the defaults apply and nothing breaks.
 */

export type RecognitionLang = "en-IN" | "hi-IN" | "mr-IN";

export const RECOGNITION_LANGS: { value: RecognitionLang; label: string; short: string }[] = [
  // en-IN copes with Hinglish (Hindi words in Latin script) as well.
  { value: "en-IN", label: "English (India)", short: "EN" },
  { value: "hi-IN", label: "हिन्दी", short: "हि" },
  { value: "mr-IN", label: "मराठी", short: "मरा" },
];

const LANG_KEY = "nuronest:voice-lang";
const READ_ALOUD_KEY = "nuronest:voice-read-aloud";
const NOTE_KEY = "nuronest:voice-note-dismissed";
const VOICE_NAME_KEY = "nuronest:voice-name";

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

// Also kept in memory, so spoken replies follow the selection even when storage is blocked.
let currentRecognitionLang: RecognitionLang | null = null;

export function readRecognitionLang(): RecognitionLang {
  if (currentRecognitionLang) return currentRecognitionLang;
  const stored = read(LANG_KEY);
  return RECOGNITION_LANGS.find((option) => option.value === stored)?.value ?? "en-IN";
}

export function writeRecognitionLang(lang: RecognitionLang) {
  currentRecognitionLang = lang;
  write(LANG_KEY, lang);
}

export function readReadAloud(): boolean {
  return read(READ_ALOUD_KEY) === "on";
}

export function writeReadAloud(on: boolean) {
  write(READ_ALOUD_KEY, on ? "on" : "off");
}

/** The chosen reply voice's name, or null for the automatic choice. */
export function readVoiceName(): string | null {
  return read(VOICE_NAME_KEY) || null;
}

export function writeVoiceName(name: string | null) {
  try {
    if (name) window.localStorage.setItem(VOICE_NAME_KEY, name);
    else window.localStorage.removeItem(VOICE_NAME_KEY);
  } catch {
    // Storage unavailable: the choice just lasts for this visit.
  }
}

export function readVoiceNoteDismissed(): boolean {
  return read(NOTE_KEY) === "1";
}

export function writeVoiceNoteDismissed() {
  write(NOTE_KEY, "1");
}
