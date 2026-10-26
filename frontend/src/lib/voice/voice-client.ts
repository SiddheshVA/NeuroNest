/**
 * Calls to the backend's voice endpoints. Audio and text are only sent for the
 * request at hand; the backend neither stores nor logs them.
 *   - speak:      POST /api/voice/speak      -> MP3 of a reply in Neuro's voice
 *   - transcribe: POST /api/voice/transcribe -> { text, language } for a recording
 */

import { apiFetch } from "@/lib/api";
import type { NeuroVoice } from "@/lib/voice/voice-prefs";

/** Languages a reply can be spoken in (Sarvam language codes). */
export type SpeechLanguageCode = "en-IN" | "hi-IN" | "mr-IN";

/** Thrown when the backend couldn't produce speech; the caller falls back to the browser voice. */
export class VoiceRequestError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "VoiceRequestError";
    this.status = status;
  }
}

/** The reply as MP3 audio in the chosen voice. Throws VoiceRequestError (or AbortError). */
export async function fetchSpeech(
  text: string,
  voice: NeuroVoice,
  languageCode: SpeechLanguageCode | undefined,
  signal: AbortSignal,
): Promise<Blob> {
  const res = await apiFetch("/voice/speak", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(languageCode ? { text, voice, languageCode } : { text, voice }),
    signal,
  });
  if (!res.ok) throw new VoiceRequestError("Speech unavailable", res.status);
  const audio = await res.blob();
  if (audio.size === 0) throw new VoiceRequestError("Speech unavailable", res.status);
  return audio;
}

/** What the backend heard; "unknown" when it couldn't tell (or heard nothing). */
export type TranscriptLanguage = "hi" | "mr" | "en" | "unknown";

export interface Transcript {
  text: string;
  language: TranscriptLanguage;
}

/** The language to read the reply in, for a question spoken in `language`. */
export function speechLanguageFor(language: TranscriptLanguage): SpeechLanguageCode | undefined {
  if (language === "hi") return "hi-IN";
  if (language === "mr") return "mr-IN";
  if (language === "en") return "en-IN";
  return undefined;
}

/** The recording as text. Empty text means nothing intelligible was heard. Throws on failure. */
export async function transcribeRecording(recording: Blob, signal: AbortSignal): Promise<Transcript> {
  const extension = recording.type.includes("mp4") ? "m4a" : recording.type.includes("ogg") ? "ogg" : "webm";
  const form = new FormData();
  form.append("audio", recording, `voice.${extension}`);
  const res = await apiFetch("/voice/transcribe", { method: "POST", body: form, signal });
  if (!res.ok) throw new VoiceRequestError("Transcription unavailable", res.status);
  const body = (await res.json()) as Partial<Transcript>;
  const language = body.language;
  return {
    text: typeof body.text === "string" ? body.text.trim() : "",
    language: language === "hi" || language === "mr" || language === "en" ? language : "unknown",
  };
}
