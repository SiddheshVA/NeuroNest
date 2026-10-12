import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ArrowUp } from "lucide-react";
import Button from "@mui/material/Button";
import IconButton from "@mui/material/IconButton";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import Tooltip from "@mui/material/Tooltip";
import CloseRounded from "@mui/icons-material/CloseRounded";
import ExpandMoreRounded from "@mui/icons-material/ExpandMoreRounded";
import MicRounded from "@mui/icons-material/MicRounded";
import StopRounded from "@mui/icons-material/StopRounded";

import { cn } from "@/lib/utils";
import { MAX_MESSAGE_LENGTH } from "@/lib/ai/provider";
import { toggleSx } from "@/components/nuro/nuro-panel-controls";
import { NuroListeningOverlay } from "@/components/nuro/nuro-listening-overlay";
import { useSpeechInput, type SpeechInputNotice } from "@/lib/voice/use-speech-input";
import {
  RECOGNITION_LANGS,
  readRecognitionLang,
  readVoiceNoteDismissed,
  writeRecognitionLang,
  writeVoiceNoteDismissed,
  type RecognitionLang,
} from "@/lib/voice/voice-prefs";

const MAX_TEXTAREA_HEIGHT = 160;

const VOICE_NOTICES: Record<SpeechInputNotice, string> = {
  "no-speech": "I couldn't catch that — no rush. You can try again or type instead.",
  denied:
    "Neuro can't hear you because microphone access is blocked for this site. To allow it, select the lock or site-settings icon beside the address bar, turn on Microphone, then tap the mic again. Typing works just as well.",
  unavailable:
    "Voice input isn't available right now (no microphone found, or the browser's speech service couldn't be reached). You can try again later, or type instead.",
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
  overlayHost,
  disabled,
  placeholder = "Share what's on your mind…",
}: {
  value: string;
  onChange: (value: string) => void;
  /** `viaVoice`: the message being sent was (at least partly) dictated. */
  onSend: (viaVoice: boolean) => void;
  /** Recording started or stopped (used to keep spoken replies out of the mic). */
  onListeningChange?: (listening: boolean) => void;
  /** Where the listening animation is shown (the message area), if anywhere. */
  overlayHost?: HTMLElement | null;
  disabled: boolean;
  placeholder?: string;
}) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const sendButtonRef = useRef<HTMLButtonElement>(null);

  // ---- Voice input ----
  const [lang, setLang] = useState<RecognitionLang>(readRecognitionLang);
  const [langMenuAnchor, setLangMenuAnchor] = useState<HTMLElement | null>(null);
  const [showVoiceNote, setShowVoiceNote] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  // Words finalised during the current recording, shown in the listening overlay.
  const [recordingText, setRecordingText] = useState("");
  // The box's text plus everything finalised so far in this recording.
  const dictatedRef = useRef("");
  const draftFromVoiceRef = useRef(false);
  const focusAfterStopRef = useRef(false);
  const autoSendRef = useRef(false);

  const handleFinalText = useCallback(
    (text: string) => {
      dictatedRef.current = appendText(dictatedRef.current, text);
      draftFromVoiceRef.current = true;
      setRecordingText((previous) => appendText(previous, text));
      onChange(dictatedRef.current);
    },
    [onChange],
  );

  const handleStopped = useCallback(
    ({ autoSend }: { autoSend: boolean }) => {
      setAnnouncement("Stopped");
      focusAfterStopRef.current = true;
      autoSendRef.current = autoSend;
      onListeningChange?.(false);
    },
    [onListeningChange],
  );

  const speech = useSpeechInput({
    lang,
    onFinalText: handleFinalText,
    onStopped: handleStopped,
  });

  function startListening() {
    dictatedRef.current = value;
    setRecordingText("");
    if (!readVoiceNoteDismissed()) setShowVoiceNote(true);
    setAnnouncement("Listening");
    onListeningChange?.(true);
    speech.start();
  }

  function chooseLang(next: RecognitionLang) {
    setLang(next);
    writeRecognitionLang(next);
    setLangMenuAnchor(null);
  }

  function dismissVoiceNote() {
    writeVoiceNoteDismissed();
    setShowVoiceNote(false);
  }

  // After a pause in speech the message is sent exactly as if Send were pressed.
  // After a manual stop nothing is sent: the text stays to read or edit, and
  // focus moves to Send (or back to the box if there's nothing to send). This
  // runs after the render that has the final words in the box.
  useEffect(() => {
    if (speech.listening || !focusAfterStopRef.current) return;
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
  }, [speech.listening]);

  useEffect(() => {
    if (!value) draftFromVoiceRef.current = false;
  }, [value]);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, MAX_TEXTAREA_HEIGHT)}px`;
  }, [value, speech.listening]);

  const trimmed = value.trim();
  const overLimit = value.length > MAX_MESSAGE_LENGTH;
  const canSend = trimmed.length > 0 && !overLimit && !disabled && !speech.listening;
  const currentLang = RECOGNITION_LANGS.find((option) => option.value === lang) ?? RECOGNITION_LANGS[0];

  function handleSend() {
    if (!canSend) return;
    onSend(draftFromVoiceRef.current);
  }

  return (
    <div className="flex flex-col gap-1.5">
      <span className="sr-only" aria-live="polite">
        {announcement}
      </span>

      {showVoiceNote ? (
        <div className="flex items-start gap-2 rounded-xl border border-border bg-muted/60 py-1.5 pr-1 pl-3 text-xs text-muted-foreground">
          <span className="flex-1 py-1">
            Your browser turns your voice into text. Some browsers, like Chrome, use
            their own online speech service to do this. NeuroNest never receives or
            stores recordings, only the text you choose to send.
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
        {speech.listening ? (
          // While dictating the box is read-only: final words in normal colour,
          // words still being recognised in a lighter one.
          <div
            role="textbox"
            aria-readonly="true"
            aria-multiline="true"
            aria-label="Message Neuro (dictating)"
            className="max-h-40 min-h-9 flex-1 overflow-y-auto px-1.5 py-1.5 text-sm whitespace-pre-wrap text-foreground"
          >
            {value}
            {speech.interim ? (
              <span className="text-muted-foreground">
                {value && !/\s$/.test(value) ? " " : ""}
                {speech.interim}
              </span>
            ) : null}
            {!value && !speech.interim ? (
              <span className="text-muted-foreground">Listening…</span>
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
            <Tooltip title={`Voice language: ${currentLang.label}`}>
              <span>
                <Button
                  size="small"
                  aria-label={`Voice input language: ${currentLang.label}`}
                  aria-haspopup="menu"
                  aria-expanded={langMenuAnchor ? "true" : undefined}
                  onClick={(event) => setLangMenuAnchor(event.currentTarget)}
                  disabled={speech.listening}
                  endIcon={<ExpandMoreRounded sx={{ fontSize: "16px !important" }} />}
                  sx={{
                    ...toggleSx,
                    minWidth: 0,
                    height: 36,
                    px: 1,
                    borderRadius: 999,
                    textTransform: "none",
                    fontSize: 12,
                    fontWeight: 600,
                    "& .MuiButton-endIcon": { ml: 0.25 },
                  }}
                >
                  {currentLang.short}
                </Button>
              </span>
            </Tooltip>
            <Menu
              anchorEl={langMenuAnchor}
              open={Boolean(langMenuAnchor)}
              onClose={() => setLangMenuAnchor(null)}
            >
              {RECOGNITION_LANGS.map((option) => (
                <MenuItem
                  key={option.value}
                  selected={option.value === lang}
                  onClick={() => chooseLang(option.value)}
                  sx={{ fontSize: 14 }}
                >
                  {option.label}
                </MenuItem>
              ))}
            </Menu>

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
                    disabled={disabled}
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

      {speech.listening && overlayHost
        ? createPortal(
            <NuroListeningOverlay
              transcript={recordingText}
              interim={speech.interim}
              countdown={speech.countdown}
              onStop={speech.stop}
              onKeepTalking={speech.keepTalking}
            />,
            overlayHost,
          )
        : null}

      {speech.listening ? (
        <p className="flex items-center gap-2 px-2 text-xs font-medium text-primary">
          <span
            aria-hidden="true"
            className="size-2 animate-pulse rounded-full bg-primary motion-reduce:animate-none"
          />
          Listening… take your time
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
