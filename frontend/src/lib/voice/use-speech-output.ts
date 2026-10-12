import { useCallback, useEffect, useRef, useState } from "react";

import type { CrisisContact } from "@/lib/nuro-client";
import {
  chooseVoice,
  cleanForSpeech,
  crisisSpeechLine,
  pickVoice,
  splitIntoChunks,
} from "@/lib/voice/speech-text";
import {
  readReadAloud,
  readRecognitionLang,
  readVoiceName,
  writeReadAloud,
  writeVoiceName,
} from "@/lib/voice/voice-prefs";

const RATE = 0.95;
const PITCH = 1;
const PREVIEW_LINE = "Hi, I'm Neuro. I'm here to listen.";

function synthesisSupported() {
  return (
    typeof window !== "undefined" &&
    "speechSynthesis" in window &&
    typeof window.SpeechSynthesisUtterance !== "undefined"
  );
}

/**
 * Reads Neuro's replies aloud with the browser's speech synthesis (nothing is
 * sent anywhere by NeuroNest). Speech stops when the page unmounts.
 */
export function useSpeechOutput() {
  const [supported] = useState(synthesisSupported);
  const [enabled, setEnabledState] = useState(readReadAloud);
  // Read when a reply arrives, so a toggle made in the same click counts.
  const enabledRef = useRef(enabled);
  const [speaking, setSpeaking] = useState(false);
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>(() =>
    synthesisSupported() ? window.speechSynthesis.getVoices() : [],
  );
  const voicesRef = useRef(voices);
  // The chosen voice's name; null means the automatic choice.
  const [voiceName, setVoiceNameState] = useState(readVoiceName);
  const voiceNameRef = useRef(voiceName);
  // Bumped on every stop, so callbacks from cancelled utterances are ignored.
  const generationRef = useRef(0);
  // Chrome can drop an utterance's events if it is garbage-collected mid-speech.
  const queueRef = useRef<SpeechSynthesisUtterance[]>([]);

  useEffect(() => {
    if (!supported) return;
    const synth = window.speechSynthesis;
    const loadVoices = () => {
      const list = synth.getVoices();
      voicesRef.current = list;
      setVoices(list);
    };
    synth.addEventListener("voiceschanged", loadVoices);
    // In case the list arrived before this listener was attached.
    const recheck = setTimeout(loadVoices, 0);
    return () => {
      clearTimeout(recheck);
      synth.removeEventListener("voiceschanged", loadVoices);
      generationRef.current += 1;
      queueRef.current = [];
      synth.cancel();
    };
  }, [supported]);

  const stop = useCallback(() => {
    generationRef.current += 1;
    queueRef.current = [];
    if (supported) window.speechSynthesis.cancel();
    setSpeaking(false);
  }, [supported]);

  const setEnabled = useCallback(
    (on: boolean) => {
      enabledRef.current = on;
      setEnabledState(on);
      writeReadAloud(on);
      if (!on) stop();
    },
    [stop],
  );

  const setVoiceName = useCallback((name: string | null) => {
    voiceNameRef.current = name;
    setVoiceNameState(name);
    writeVoiceName(name);
  }, []);

  /**
   * Speaks a new reply (never history), then the helpline line for a crisis
   * reply. Does nothing while "Read replies aloud" is off.
   */
  const speakReply = useCallback(
    (reply: string, crisisContacts?: CrisisContact[]) => {
      if (!supported || !enabledRef.current) return;
      const synth = window.speechSynthesis;
      generationRef.current += 1;
      synth.cancel();
      const generation = generationRef.current;

      const voices = voicesRef.current.length > 0 ? voicesRef.current : synth.getVoices();
      const replyText = cleanForSpeech(reply);
      // A Devanagari reply is read in the language picked for voice input (Hindi or Marathi).
      const replyVoice = chooseVoice(
        voices,
        replyText,
        voiceNameRef.current,
        readRecognitionLang(),
      );
      const pieces = splitIntoChunks(replyText).map((text) => ({ text, voice: replyVoice }));
      if (crisisContacts && crisisContacts.length > 0) {
        const englishVoice = chooseVoice(voices, "", voiceNameRef.current);
        pieces.push(
          ...splitIntoChunks(crisisSpeechLine(crisisContacts)).map((text) => ({
            text,
            voice: englishVoice,
          })),
        );
      }
      if (pieces.length === 0) return;

      const finished = () => {
        if (generation === generationRef.current) {
          queueRef.current = [];
          setSpeaking(false);
        }
      };
      queueRef.current = pieces.map(({ text, voice }, index) => {
        const utterance = new SpeechSynthesisUtterance(text);
        if (voice) utterance.voice = voice;
        utterance.lang = voice?.lang ?? "en-IN";
        utterance.rate = RATE;
        utterance.pitch = PITCH;
        if (index === pieces.length - 1) {
          utterance.onend = finished;
          utterance.onerror = finished;
        }
        return utterance;
      });
      setSpeaking(true);
      queueRef.current.forEach((utterance) => synth.speak(utterance));
    },
    [supported],
  );

  /** Speaks a short sample in a voice (null: the automatic choice), even with read-aloud off. */
  const preview = useCallback(
    (name: string | null) => {
      if (!supported) return;
      const synth = window.speechSynthesis;
      generationRef.current += 1;
      synth.cancel();
      const generation = generationRef.current;
      const list = voicesRef.current.length > 0 ? voicesRef.current : synth.getVoices();
      const voice = chooseVoice(list, PREVIEW_LINE, name);
      const utterance = new SpeechSynthesisUtterance(PREVIEW_LINE);
      if (voice) utterance.voice = voice;
      utterance.lang = voice?.lang ?? "en-IN";
      utterance.rate = RATE;
      utterance.pitch = PITCH;
      utterance.onend = utterance.onerror = () => {
        if (generation === generationRef.current) {
          queueRef.current = [];
          setSpeaking(false);
        }
      };
      queueRef.current = [utterance];
      setSpeaking(true);
      synth.speak(utterance);
    },
    [supported],
  );

  return {
    supported,
    enabled,
    setEnabled,
    speaking,
    speakReply,
    stop,
    voices,
    voiceName,
    setVoiceName,
    /** The voice the automatic choice uses for English replies right now. */
    autoVoice: pickVoice(voices, ""),
    preview,
  };
}
