import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ArrowUp } from "lucide-react";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import CloseRounded from "@mui/icons-material/CloseRounded";
import MicRounded from "@mui/icons-material/MicRounded";
import StopRounded from "@mui/icons-material/StopRounded";

import { cn } from "@/lib/utils";
import { MAX_MESSAGE_LENGTH } from "@/lib/ai/provider";
import { toggleSx } from "@/components/nuro/nuro-panel-controls";
import { NuroListeningOverlay } from "@/components/nuro/nuro-listening-overlay";
import { useSpeechInput, type SpeechInputNotice } from "@/lib/voice/use-speech-input";
import {
  speechLanguageFor,
  type SpeechLanguageCode,
  type Transcript,
} from "@/lib/voice/voice-client";
import { readVoiceNoteDismissed, writeVoiceNoteDismissed } from "@/lib/voice/voice-prefs";

const MAX_TEXTAREA_HEIGHT = 160;

const VOICE_NOTICES: Record<SpeechInputNotice, string> = {
  "no-speech": "I couldn't catch that — no rush. You can try again or type instead.",
  denied:
    "Neuro can't hear you because microphone access is blocked for this site. To allow it, select the lock or site-settings icon beside the address bar, turn on Microphone, then tap the mic again. Typing works just as well.",
  unavailable:
    "Voice input isn't available right now (no microphone found, or this browser can't record audio). You can try again later, or type instead.",
};

const micSx = { ...toggleSx, width: 36, height: 36 } as const;
const listeningMicSx = {
  ...micSx,
  color: "var(--primary)",
  backgroundColor: "color-mix(in oklch, var(--primary) 12%, transparent)",
  "&:hover": { backgroundColor: "color-mix(in oklch, var(--primary) 18%, transparent)" },
} as const;

/** Adds dictated text after what's already in the box. */
function appendText(base: string, addition: string) {
  if (!base) return addition;
  return /\s$/.test(base) ? `${base}${addition}` : `${base} ${addition}`;
}

export function NuroComposer({
  value,
  onChange,
  onSend,
  onListeningChange,
  onBeforeListening,
  overlayHost,
  disabled,
  placeholder = "Share what's on your mind…",
}: {
  value: string;
  onChange: (value: string) => void;
  /**
   * `viaVoice`: the message being sent was (at least partly) dictated;
   * `spokenLanguage`: the language it was spoken in.
   */
  onSend: (viaVoice: boolean, spokenLanguage?: SpeechLanguageCode) => void;
  /** Recording started or stopped (used to keep spoken replies out of the mic). */
  onListeningChange?: (listening: boolean) => void;
  /** Runs before the mic starts (e.g. to ask for a voice first); calls `start` when ready. */
  onBeforeListening?: (start: () => void) => void;
  /** Where the listening animation is shown (the message area), if anywhere. */
  overlayHost?: HTMLElement | null;
  disabled: boolean;
  placeholder?: string;
}) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const sendButtonRef = useRef<HTMLButtonElement>(null);

  // ---- Voice input ----
  const [showVoiceNote, setShowVoiceNote] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  // The box's text when the recording started; the transcript is added after it.
  const dictatedRef = useRef("");
  const draftFromVoiceRef = useRef(false);
  // The language the dictated message was spoken in (detected by the backend).
  const spokenLanguageRef = useRef<SpeechLanguageCode | undefined>(undefined);
  const focusAfterStopRef = useRef(false);
  const autoSendRef = useRef(false);

  const handleTranscript = useCallback(
    (transcript: Transcript, autoSend: boolean) => {
      dictatedRef.current = appendText(dictatedRef.current, transcript.text);
      draftFromVoiceRef.current = true;
      spokenLanguageRef.current = speechLanguageFor(transcript.language);
      autoSendRef.current = autoSend;
      onChange(dictatedRef.current);
    },
    [onChange],
  );

  const speech = useSpeechInput({ onTranscript: handleTranscript });
  const voiceBusy = speech.phase !== "idle";

  // The mic is on only while listening; spoken replies may resume once it is off.
  const wasListeningRef = useRef(false);
  useEffect(() => {
    if (speech.listening) {
      wasListeningRef.current = true;
      return;
    }
    if (!wasListeningRef.current) return;
    wasListeningRef.current = false;
    setAnnouncement(speech.phase === "understanding" ? "Understanding" : "Stopped");
    onListeningChange?.(false);
  }, [onListeningChange, speech.listening, speech.phase]);

  function startListening() {
    if (onBeforeListening) onBeforeListening(beginListening);
    else beginListening();
  }

  function beginListening() {
    dictatedRef.current = value;
    autoSendRef.current = false;
    focusAfterStopRef.current = true;
    wasListeningRef.current = true;
    if (!readVoiceNoteDismissed()) setShowVoiceNote(true);
    setAnnouncement("Listening");
    onListeningChange?.(true);
    speech.start();
  }

  function dismissVoiceNote() {
    writeVoiceNoteDismissed();
    setShowVoiceNote(false);
  }

  // After a pause in speech the message is sent exactly as if Send were pressed.
  // After a manual stop nothing is sent: the text stays to read or edit, and
  // focus moves to Send (or back to the box if there's nothing to send). This
  // runs once the recording has been understood, after the render that has
  // the words in the box.
  useEffect(() => {
    if (voiceBusy || !focusAfterStopRef.current) return;
    focusAfterStopRef.current = false;
    const autoSend = autoSendRef.current;
    autoSendRef.current = false;
    const send = sendButtonRef.current;
    if (send && !send.disabled) {
      if (autoSend) send.click();
      else send.focus();
    } else {
      textareaRef.current?.focus();
    }
  }, [voiceBusy]);

  useEffect(() => {
    if (!value) {
      draftFromVoiceRef.current = false;
      spokenLanguageRef.current = undefined;
    }
  }, [value]);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, MAX_TEXTAREA_HEIGHT)}px`;
  }, [value, voiceBusy]);

  const trimmed = value.trim();
  const overLimit = value.length > MAX_MESSAGE_LENGTH;
  const canSend = trimmed.length > 0 && !overLimit && !disabled && !voiceBusy;

  function handleSend() {
    if (!canSend) return;
    const viaVoice = draftFromVoiceRef.current;
    onSend(viaVoice, viaVoice ? spokenLanguageRef.current : undefined);
  }

  return (
    <div className="flex flex-col gap-1.5">
      <span className="sr-only" aria-live="polite">
        {announcement}
      </span>

      {showVoiceNote ? (
        <div className="flex items-start gap-2 rounded-xl border border-border bg-muted/60 py-1.5 pr-1 pl-3 text-xs text-muted-foreground">
          <span className="flex-1 py-1">
            Your recording is sent securely to NeuroNest and turned into text by
            Google&apos;s Gemini. It isn&apos;t stored: only the text you choose to send
            is saved.
          </span>
          <IconButton
            aria-label="Dismiss voice privacy note"
            onClick={dismissVoiceNote}
            size="small"
            sx={toggleSx}
          >
            <CloseRounded sx={{ fontSize: 16 }} />
          </IconButton>
        </div>
      ) : null}

      <div className="flex items-end gap-1.5 rounded-3xl border border-border bg-background px-3 py-2 shadow-sm focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50">
        {voiceBusy ? (
          // While recording (and while it is understood) the box is read-only.
          <div
            role="textbox"
            aria-readonly="true"
            aria-multiline="true"
            aria-label="Message Neuro (dictating)"
            className="max-h-40 min-h-9 flex-1 overflow-y-auto px-1.5 py-1.5 text-sm whitespace-pre-wrap text-foreground"
          >
            {value}
            {!value ? (
              <span className="text-muted-foreground">
                {speech.listening ? "Listening…" : "Understanding…"}
              </span>
            ) : null}
          </div>
        ) : (
          <textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => {
              onChange(event.target.value);
              if (speech.notice) speech.clearNotice();
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                handleSend();
              }
            }}
            placeholder={placeholder}
            rows={1}
            aria-label="Message Neuro"
            disabled={disabled}
            className="max-h-40 min-h-9 flex-1 resize-none bg-transparent px-1.5 py-1.5 text-sm text-foreground placeholder:text-muted-foreground outline-none disabled:opacity-60"
          />
        )}

        {speech.supported ? (
          <>
            {speech.listening ? (
              <Tooltip title="Stop listening">
                <IconButton aria-label="Stop listening" onClick={speech.stop} sx={listeningMicSx}>
                  <span
                    aria-hidden="true"
                    className="absolute inset-0 animate-ping rounded-full bg-primary/20 motion-reduce:animate-none"
                  />
                  <StopRounded fontSize="small" />
                </IconButton>
              </Tooltip>
            ) : (
              <Tooltip
                title={disabled ? "You can speak again once Neuro has replied" : "Speak your message"}
              >
                {/* The span lets the tooltip show while the button is disabled. */}
                <span>
                  <IconButton
                    aria-label="Speak your message"
                    onClick={startListening}
                    disabled={disabled || voiceBusy}
                    sx={micSx}
                  >
                    <MicRounded fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
            )}
          </>
        ) : null}

        <button
          ref={sendButtonRef}
          type="button"
          onClick={handleSend}
          disabled={!canSend}
          aria-label="Send message"
          className={cn(
            "flex size-9 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground transition-colors",
            "disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground",
            canSend && "hover:bg-primary/85",
          )}
        >
          <ArrowUp className="size-4.5" aria-hidden="true" />
        </button>
      </div>

      {voiceBusy && overlayHost
        ? createPortal(
            <NuroListeningOverlay
              understanding={speech.phase === "understanding"}
              levelLive={speech.levelLive}
              levelRef={speech.setLevelTarget}
              pausing={speech.pausing}
              onStop={speech.stop}
              onKeepTalking={speech.keepTalking}
            />,
            overlayHost,
          )
        : null}

      {voiceBusy ? (
        <p className="flex items-center gap-2 px-2 text-xs font-medium text-primary">
          <span
            aria-hidden="true"
            className="size-2 animate-pulse rounded-full bg-primary motion-reduce:animate-none"
          />
          {speech.listening ? "Listening… take your time" : "Understanding…"}
        </p>
      ) : speech.notice ? (
        <p className="px-2 text-xs text-muted-foreground">{VOICE_NOTICES[speech.notice]}</p>
      ) : null}

      <div className="flex items-center justify-between px-2 text-xs text-muted-foreground">
        <span>
          Neuro is an AI, not a therapist. It offers supportive, non-clinical
          guidance and is not a replacement for professional help.
        </span>
        {value.length > MAX_MESSAGE_LENGTH * 0.8 ? (
          <span className={cn(overLimit && "font-medium text-destructive")}>
            {value.length}/{MAX_MESSAGE_LENGTH}
          </span>
        ) : null}
      </div>
    </div>
  );
}
