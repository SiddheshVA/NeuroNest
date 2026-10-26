/**
 * Pure helpers for reading Neuro's replies aloud: cleaning text for speech,
 * splitting it into short utterances, choosing a voice and wording the crisis
 * helpline line.
 */

import type { CrisisContact } from "@/lib/nuro-client";
import type { SpeechLanguageCode } from "@/lib/voice/voice-client";

/** Chrome stops long utterances after ~15 seconds, so text is spoken in short pieces. */
const MAX_CHUNK_LENGTH = 180;

const DEVANAGARI = /[ऀ-ॿ]/;
// Emoji plus the joiners, skin tones and flag letters that build up composite emoji.
const EMOJI = /\p{Extended_Pictographic}|\p{Regional_Indicator}|\p{Emoji_Modifier}|️|‍|⃣/gu;

export function hasDevanagari(text: string) {
  return DEVANAGARI.test(text);
}

/** Removes markdown syntax and emoji so they aren't read out. */
export function cleanForSpeech(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/^\s{0,3}(#{1,6}|>+|[-*+•]|\d+[.)])\s+/gm, "")
    .replace(/(\*\*|__|~~|\*)/g, "")
    .replace(EMOJI, "")
    .split("\n")
    .map((line) => line.replace(/\s+/g, " ").trim())
    .filter(Boolean)
    .join("\n");
}

function splitLongSentence(sentence: string): string[] {
  const pieces: string[] = [];
  let current = "";
  for (const word of sentence.split(/\s+/)) {
    const next = current ? `${current} ${word}` : word;
    if (next.length > MAX_CHUNK_LENGTH && current) {
      pieces.push(current);
      current = word;
    } else {
      current = next;
    }
  }
  if (current) pieces.push(current);
  return pieces;
}

/** Splits text into sentence-based pieces short enough to speak reliably. */
export function splitIntoChunks(text: string): string[] {
  const sentences = text
    .split("\n")
    .flatMap((line) => line.match(/[^.!?।]+[.!?।]+["'”’)\]]*|[^.!?।]+$/g) ?? [])
    .map((sentence) => sentence.trim())
    .filter(Boolean)
    .flatMap((sentence) =>
      sentence.length > MAX_CHUNK_LENGTH ? splitLongSentence(sentence) : [sentence],
    );

  // Short neighbouring sentences are joined so the voice doesn't pause after each one.
  const chunks: string[] = [];
  for (const sentence of sentences) {
    const last = chunks[chunks.length - 1];
    if (last && last.length + sentence.length + 1 <= MAX_CHUNK_LENGTH) {
      chunks[chunks.length - 1] = `${last} ${sentence}`;
    } else {
      chunks.push(sentence);
    }
  }
  return chunks;
}

// Known female Indian voices: Microsoft Heera/Neerja (en-IN), Kalpana/Swara (hi-IN),
// Aarohi (mr-IN), Apple Veena (en-IN) and Lekha (hi-IN), Google's Indian voices.
// Only voices already filtered to an Indian language are scored, so "google" means Google's Indian voice.
const FEMALE_VOICE = /heera|neerja|swara|kalpana|veena|lekha|aditi|raveena|kajal|ananya|aarohi|female|google/i;
// Neural voices sound far more natural than the older offline ones.
const NATURAL_VOICE = /natural|online/i;
const MALE_VOICE = /\b(male|prabhat|rishi|hemant|madhur|manohar|ravi|aarav|kunal)\b/i;

function normaliseLang(lang: string) {
  return lang.replace("_", "-").toLowerCase();
}

function bestVoiceFor(voices: SpeechSynthesisVoice[], lang: string) {
  const candidates = voices.filter((voice) => normaliseLang(voice.lang) === lang);
  const score = (voice: SpeechSynthesisVoice) =>
    (FEMALE_VOICE.test(voice.name) ? 2 : 0) -
    (MALE_VOICE.test(voice.name) ? 3 : 0) +
    (NATURAL_VOICE.test(voice.name) ? 1 : 0) +
    // Other online voices also tend to sound better than local ones.
    (voice.localService ? 0 : 0.5);
  return candidates.sort((a, b) => score(b) - score(a))[0] ?? null;
}

/**
 * The browser voice for `text` (used when Neuro's own voice is unavailable):
 * the given voice if this device has it, otherwise a female en-IN voice, then
 * the browser default (null).
 *
 * Hindi and Marathi share Devanagari, so for Devanagari text the language
 * of the question (when it was spoken) decides: with mr-IN, a mr-IN voice,
 * then hi-IN; otherwise hi-IN. A given voice already in that language is kept.
 */
export function chooseVoice(
  voices: SpeechSynthesisVoice[],
  text: string,
  chosenName: string | null,
  language: SpeechLanguageCode = "en-IN",
) {
  const chosen = chosenName ? voices.find((voice) => voice.name === chosenName) : undefined;
  const chosenLang = chosen ? normaliseLang(chosen.lang) : null;

  if (hasDevanagari(text)) {
    const scriptLangs = language === "mr-IN" ? ["mr-in", "hi-in"] : ["hi-in"];
    for (const lang of scriptLangs) {
      if (chosen && chosenLang === lang) return chosen;
      const native = bestVoiceFor(voices, lang);
      if (native) return native;
    }
  }

  return (
    chosen ?? bestVoiceFor(voices, "en-in") ?? voices.find((voice) => voice.default) ?? null
  );
}

/** "KIRAN Mental Health Helpline" -> "KIRAN". */
function spokenName(name: string) {
  return name.replace(/\s+(mental\s+health\s+)?helpline$/i, "").trim();
}

/** "1800-599-0019" -> "1 8 0 0, 5 9 9, 0 0 1 9" (first number only). */
function spokenNumber(phone: string) {
  const first = phone.split(/\s+or\s+/i)[0];
  return first
    .split(/\D+/)
    .filter(Boolean)
    .map((group) => group.split("").join(" "))
    .join(", ");
}

/** The line spoken after a crisis reply, built from the backend's helplines. */
export function crisisSpeechLine(contacts: CrisisContact[]): string {
  const parts = contacts
    .map((contact) => ({ name: spokenName(contact.name), number: spokenNumber(contact.phone) }))
    .filter((part) => part.name && part.number)
    .map((part) => `${part.name} on ${part.number}`);

  const intro = "I've shared helpline numbers on your screen.";
  if (parts.length === 0) return intro;
  const options =
    parts.length === 1
      ? parts[0]
      : `${parts.slice(0, -1).join(", ")}, or ${parts[parts.length - 1]}`;
  return `${intro} You can call ${options}, any time.`;
}
