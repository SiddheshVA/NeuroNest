import { useCallback, useEffect, useRef, useState } from "react";

import type { CrisisContact } from "@/lib/nuro-client";
import {
  chooseVoice,
  cleanForSpeech,
  crisisSpeechLine,
  splitIntoChunks,
} from "@/lib/voice/speech-text";
import { fetchSpeech, type SpeechLanguageCode } from "@/lib/voice/voice-client";
import {
  DEFAULT_NEURO_VOICE,
  readNeuroVoice,
  readReadAloud,
  writeNeuroVoice,
  writeReadAloud,
  type NeuroVoice,
} from "@/lib/voice/voice-prefs";

const RATE = 0.95;
const PITCH = 1;
const PREVIEW_LINE = "Hi, I'm Neuro. I'm here to listen.";

/** One stretch of speech: a reply, or the helpline line after a crisis reply. */
interface SpeechPiece {
  text: string;
  /** Only for a reply to a spoken question: the language it was asked in. */
  language?: SpeechLanguageCode;
  /**
   * The user tapped for it (a voice preview, or "Read aloud" on a message): plays
   * even with "Read replies aloud" off.
   */
  requested?: boolean;
}

/**
 * What is playing: a new reply read because the toggle is on (stops when it is
 * turned off), or speech the user tapped for (a preview or one message).
 */
type PlaybackReason = "reply" | "requested";

type PlayResult = "ended" | "failed" | "stopped";

// Preview clips per voice, kept for this visit so a preview is fetched only once.
const previewCache = new Map<NeuroVoice, Blob>();

function synthesisSupported() {
  return (
    typeof window !== "undefined" &&
    "speechSynthesis" in window &&
    typeof window.SpeechSynthesisUtterance !== "undefined"
  );
}

function audioSupported() {
  return typeof window !== "undefined" && typeof window.Audio !== "undefined";
}

/**
 * Reads Neuro's replies aloud in the chosen voice (Aarti or Anand), with audio
 * from the backend. If that fails, the browser's own speech synthesis reads
 * the reply instead, so a reply is never silent. Speech stops when the page
 * unmounts.
 */
export function useSpeechOutput() {
  const [synthesis] = useState(synthesisSupported);
  const [supported] = useState(() => audioSupported() || synthesisSupported());
  const [enabled, setEnabledState] = useState(readReadAloud);
  // Read when a reply arrives, so a toggle made in the same click counts.
  const enabledRef = useRef(enabled);
  const [speaking, setSpeaking] = useState(false);
  // The message being read through its own "Read aloud" button, if any.
  const [speakingMessageId, setSpeakingMessageId] = useState<string | null>(null);
  // null until the user has chosen a voice (the one-time "Choose Neuro's voice" dialog).
  const [voice, setVoiceState] = useState<NeuroVoice | null>(readNeuroVoice);
  const voiceRef = useRef(voice);
  // Bumped on every stop, so callbacks from cancelled speech are ignored.
  const generationRef = useRef(0);
  // Chrome can drop an utterance's events if it is garbage-collected mid-speech.
  const queueRef = useRef<SpeechSynthesisUtterance[]>([]);
  const voicesRef = useRef<SpeechSynthesisVoice[]>([]);
  const abortRef = useRef<AbortController | null>(null);
  // Ends the clip that is playing (its object URL is released there).
  const endClipRef = useRef<((result: PlayResult) => void) | null>(null);
  const playbackReasonRef = useRef<PlaybackReason | null>(null);

  const releasePlayback = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    endClipRef.current?.("stopped");
    queueRef.current = [];
    if (synthesis) window.speechSynthesis.cancel();
  }, [synthesis]);

  useEffect(() => {
    if (!synthesis) return;
    const synth = window.speechSynthesis;
    const loadVoices = () => {
      voicesRef.current = synth.getVoices();
    };
    loadVoices();
    synth.addEventListener("voiceschanged", loadVoices);
    return () => synth.removeEventListener("voiceschanged", loadVoices);
  }, [synthesis]);

  // Leaving the page stops speech and releases any audio.
  useEffect(
    () => () => {
      generationRef.current += 1;
      releasePlayback();
    },
    [releasePlayback],
  );

  const stop = useCallback(() => {
    generationRef.current += 1;
    playbackReasonRef.current = null;
    releasePlayback();
    setSpeaking(false);
    setSpeakingMessageId(null);
  }, [releasePlayback]);

  const setEnabled = useCallback(
    (on: boolean) => {
      enabledRef.current = on;
      setEnabledState(on);
      writeReadAloud(on);
      // Turning it off silences a reply at once (speech the user tapped for plays on).
      // Turning it on never replays anything.
      if (!on && playbackReasonRef.current === "reply") stop();
    },
    [stop],
  );

  const setVoice = useCallback((next: NeuroVoice) => {
    voiceRef.current = next;
    setVoiceState(next);
    writeNeuroVoice(next);
  }, []);

  // A new reply (and its helpline line) is spoken only while "Read replies aloud" is on,
  // checked again right before anything plays, whichever voice plays it. Speech the
  // user tapped for (a preview, or "Read aloud" on a message) always plays.
  const mayPlay = useCallback((piece: SpeechPiece) => piece.requested === true || enabledRef.current, []);

  const finished = useCallback((generation: number) => {
    if (generation === generationRef.current) {
      queueRef.current = [];
      playbackReasonRef.current = null;
      setSpeaking(false);
      setSpeakingMessageId(null);
    }
  }, []);

  /** The fallback: the browser's own voice, matched to each piece's language. */
  const speakWithBrowser = useCallback(
    (pieces: SpeechPiece[], generation: number) => {
      if (!synthesis || generation !== generationRef.current || !pieces.every(mayPlay)) {
        finished(generation);
        return;
      }
      const synth = window.speechSynthesis;
      synth.cancel();
      const voices = voicesRef.current.length > 0 ? voicesRef.current : synth.getVoices();
      const chunks = pieces.flatMap((piece) => {
        const browserVoice = chooseVoice(voices, piece.text, null, piece.language);
        return splitIntoChunks(piece.text).map((text) => ({ text, voice: browserVoice }));
      });
      if (chunks.length === 0) {
        finished(generation);
        return;
      }
      queueRef.current = chunks.map(({ text, voice: browserVoice }, index) => {
        const utterance = new SpeechSynthesisUtterance(text);
        if (browserVoice) utterance.voice = browserVoice;
        utterance.lang = browserVoice?.lang ?? "en-IN";
        utterance.rate = RATE;
        utterance.pitch = PITCH;
        if (index === chunks.length - 1) {
          utterance.onend = () => finished(generation);
          utterance.onerror = () => finished(generation);
        }
        return utterance;
      });
      queueRef.current.forEach((utterance) => synth.speak(utterance));
    },
    [finished, mayPlay, synthesis],
  );

  /** Plays one clip to its end; "stopped" if speech was stopped meanwhile. */
  const playClip = useCallback((clip: Blob, generation: number) => {
    return new Promise<PlayResult>((resolve) => {
      if (generation !== generationRef.current) {
        resolve("stopped");
        return;
      }
      const url = URL.createObjectURL(clip);
      const audio = new Audio(url);
      const end = (result: PlayResult) => {
        if (endClipRef.current !== end) return;
        endClipRef.current = null;
        audio.onended = null;
        audio.onerror = null;
        audio.pause();
        audio.removeAttribute("src");
        URL.revokeObjectURL(url);
        resolve(generation === generationRef.current ? result : "stopped");
      };
      endClipRef.current = end;
      audio.onended = () => end("ended");
      audio.onerror = () => end("failed");
      // Rejected when the browser blocks playback: the browser voice takes over.
      audio.play().catch(() => end("failed"));
    });
  }, []);

  /**
   * Speaks the pieces in Neuro's voice, one after another; from the first piece
   * that can't be fetched or played, the browser voice reads the rest.
   */
  const speakPieces = useCallback(
    async (pieces: SpeechPiece[], generation: number, cached?: Blob) => {
      if (!audioSupported()) {
        speakWithBrowser(pieces, generation);
        return;
      }
      const controller = new AbortController();
      abortRef.current = controller;
      const chosen = voiceRef.current ?? DEFAULT_NEURO_VOICE;
      // Fetched together so the helpline line is ready as soon as the reply ends.
      const clips = pieces.map((piece, index) => {
        const clip =
          index === 0 && cached
            ? Promise.resolve(cached)
            : fetchSpeech(piece.text, chosen, piece.language, controller.signal);
        clip.catch(() => {}); // Handled below; not every clip may be awaited.
        return clip;
      });
      for (let index = 0; index < pieces.length; index += 1) {
        let clip: Blob;
        try {
          clip = await clips[index];
        } catch {
          if (generation === generationRef.current) speakWithBrowser(pieces.slice(index), generation);
          return;
        }
        if (!mayPlay(pieces[index])) {
          finished(generation);
          return;
        }
        const result = await playClip(clip, generation);
        if (result === "stopped") return;
        if (result === "failed") {
          speakWithBrowser(pieces.slice(index), generation);
          return;
        }
      }
      if (abortRef.current === controller) abortRef.current = null;
      finished(generation);
    },
    [finished, mayPlay, playClip, speakWithBrowser],
  );

  /**
   * Speaks a new reply (never history), then the helpline line for a crisis
   * reply. Does nothing while "Read replies aloud" is off, whether the message
   * was typed or spoken. `language` is passed only for a reply to a spoken
   * message: the language it was spoken in.
   */
  const speakReply = useCallback(
    (reply: string, crisisContacts?: CrisisContact[], language?: SpeechLanguageCode) => {
      if (!supported || !enabledRef.current) return;
      generationRef.current += 1;
      releasePlayback();
      const generation = generationRef.current;

      const pieces: SpeechPiece[] = [];
      const replyText = cleanForSpeech(reply);
      if (replyText) pieces.push({ text: replyText, language });
      if (crisisContacts && crisisContacts.length > 0) {
        pieces.push({ text: crisisSpeechLine(crisisContacts) });
      }
      if (pieces.length === 0) {
        playbackReasonRef.current = null;
        setSpeaking(false);
        return;
      }
      playbackReasonRef.current = "reply";
      setSpeakingMessageId(null);
      setSpeaking(true);
      void speakPieces(pieces, generation);
    },
    [releasePlayback, speakPieces, supported],
  );

  /** Speaks a short sample in a voice, even with read-aloud off. */
  const preview = useCallback(
    async (previewVoice: NeuroVoice) => {
      if (!supported) return;
      generationRef.current += 1;
      releasePlayback();
      const generation = generationRef.current;
      playbackReasonRef.current = "requested";
      setSpeakingMessageId(null);
      setSpeaking(true);

      let clip = previewCache.get(previewVoice);
      if (!clip && audioSupported()) {
        const controller = new AbortController();
        abortRef.current = controller;
        try {
          clip = await fetchSpeech(PREVIEW_LINE, previewVoice, undefined, controller.signal);
          previewCache.set(previewVoice, clip);
        } catch {
          // Falls back to the browser voice below.
        }
        if (generation !== generationRef.current) return;
      }
      if (clip) {
        void speakPieces([{ text: PREVIEW_LINE, requested: true }], generation, clip);
      } else {
        speakWithBrowser([{ text: PREVIEW_LINE, requested: true }], generation);
      }
    },
    [releasePlayback, speakPieces, speakWithBrowser, supported],
  );

  /**
   * "Read aloud" on one message: reads it (and its helplines, for a crisis reply)
   * whether or not "Read replies aloud" is on. Tapping the message that is being
   * read stops it.
   */
  const speakMessage = useCallback(
    (messageId: string, text: string, crisisContacts?: CrisisContact[]) => {
      if (!supported) return;
      if (speakingMessageId === messageId) {
        stop();
        return;
      }
      generationRef.current += 1;
      releasePlayback();
      const generation = generationRef.current;

      const pieces: SpeechPiece[] = [];
      const messageText = cleanForSpeech(text);
      if (messageText) pieces.push({ text: messageText, requested: true });
      if (crisisContacts && crisisContacts.length > 0) {
        pieces.push({ text: crisisSpeechLine(crisisContacts), requested: true });
      }
      if (pieces.length === 0) {
        stop();
        return;
      }
      playbackReasonRef.current = "requested";
      setSpeakingMessageId(messageId);
      setSpeaking(true);
      void speakPieces(pieces, generation);
    },
    [releasePlayback, speakPieces, speakingMessageId, stop, supported],
  );

  return {
    supported,
    enabled,
    setEnabled,
    speaking,
    speakReply,
    speakMessage,
    speakingMessageId,
    stop,
    /** The chosen voice, or null before the user has chosen one. */
    voice,
    setVoice,
    preview,
  };
}
