
import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { AlertCircle, Brain } from "lucide-react";
import { useAuth } from "@clerk/nextjs";
import HistoryRounded from "@mui/icons-material/HistoryRounded";
import SpaOutlined from "@mui/icons-material/SpaOutlined";
import StopCircleRounded from "@mui/icons-material/StopCircleRounded";
import VolumeOffRounded from "@mui/icons-material/VolumeOffRounded";
import VolumeUpRounded from "@mui/icons-material/VolumeUpRounded";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { NuroComposer } from "@/components/nuro/nuro-composer";
import { NuroMemoryNotice } from "@/components/nuro/nuro-memory-notice";
import { NuroPanelToggle } from "@/components/nuro/nuro-panel-controls";
import { NuroVoicePicker } from "@/components/nuro/nuro-voice-picker";
import {
  NuroCrisisBanner,
  NuroMessageBubble,
  NuroTypingIndicator,
} from "@/components/nuro/nuro-message";
import type { CrisisContact, NuroMessage } from "@/lib/nuro-client";

const SUGGESTED_PROMPTS = [
  "I'm feeling anxious",
  "I can't sleep",
  "I'm feeling low today",
  "Just want to talk",
];

const WELCOME_MESSAGE = [
  "Hi there! I'm Neuro. 👋",
  "This is a safe and supportive space where you can share whatever is on your mind.",
  "How are you feeling today?",
].join("\n\n");

export function NuroConversation({
  messages,
  isLoadingMessages,
  isSending,
  sendError,
  draft,
  onDraftChange,
  onSend,
  onListeningChange,
  readAloud,
  onSuggestedPrompt,
  historyOpen,
  onOpenHistory,
  suggestionsOpen,
  onOpenSuggestions,
  bannerContacts,
}: {
  messages: NuroMessage[];
  isLoadingMessages: boolean;
  isSending: boolean;
  sendError: string | null;
  draft: string;
  onDraftChange: (value: string) => void;
  /** `viaVoice`: the message was dictated with the mic. */
  onSend: (viaVoice: boolean) => void;
  onListeningChange: (listening: boolean) => void;
  /** "Read replies aloud" state; omitted when the browser can't speak. */
  readAloud?: {
    enabled: boolean;
    speaking: boolean;
    onToggle: () => void;
    onStopSpeaking: () => void;
    voices: SpeechSynthesisVoice[];
    /** The chosen voice's name, or null for the automatic choice. */
    voiceName: string | null;
    autoVoice: SpeechSynthesisVoice | null;
    onVoiceChange: (name: string | null) => void;
    onPreviewVoice: (name: string | null) => void;
  };
  onSuggestedPrompt: (text: string) => void;
  /** Whether the chat history panel is showing; its reopen button shows only when it isn't. */
  historyOpen: boolean;
  onOpenHistory: () => void;
  /** Whether the suggestions panel is showing; its reopen button shows only when it isn't. */
  suggestionsOpen: boolean;
  onOpenSuggestions: () => void;
  /** Helplines for the calm banner of this conversation (empty when none is needed). */
  bannerContacts: CrisisContact[];
}) {
  const scrollAnchorRef = useRef<HTMLDivElement>(null);
  // The listening animation is drawn over the messages (not over the input box).
  const [overlayHost, setOverlayHost] = useState<HTMLDivElement | null>(null);
  const { isLoaded: authLoaded, isSignedIn, userId } = useAuth();
  const memoryUserId = authLoaded && isSignedIn && userId ? userId : null;

  // Follows streamed text too: the last message grows while the reply arrives.
  const lastMessageLength = messages[messages.length - 1]?.content.length ?? 0;
  useEffect(() => {
    scrollAnchorRef.current?.scrollIntoView({ block: "end" });
  }, [messages.length, lastMessageLength, isSending, isLoadingMessages]);

  const showWelcome = !isLoadingMessages && messages.length === 0;
  // Before the safety check returns there is no reply bubble yet; after that the
  // bubble shows its own typing dots until the first token.
  const showTypingIndicator = isSending && !messages.some((message) => message.isStreaming);

  // After a crisis turn, a calm banner stays for the rest of this conversation. It is
  // hidden while the latest reply is itself the crisis turn (its full alert is showing).
  const assistantMessages = messages.filter((message) => message.role === "ASSISTANT");
  const hadCrisisTurn = assistantMessages.some((message) => message.riskFlag);
  const latestReplyIsCrisis = assistantMessages[assistantMessages.length - 1]?.riskFlag === true;
  const showCrisisBanner = bannerContacts.length > 0 && hadCrisisTurn && !latestReplyIsCrisis;

  return (
    <main className="relative flex min-w-0 flex-1 flex-col bg-background">
      <Image
        src="/images/cetre-area.png"
        alt=""
        aria-hidden="true"
        fill
        sizes="100vw"
        priority
        className="object-cover"
      />
      <div className="relative z-10 flex items-center justify-between gap-3 border-b border-border bg-background px-4 py-2.5 sm:px-8 sm:py-3">
        <div className="flex items-center gap-2">
          {historyOpen ? null : (
            <NuroPanelToggle label="Open chat history" onClick={onOpenHistory}>
              <HistoryRounded fontSize="small" />
            </NuroPanelToggle>
          )}
          <div>
            <h1 className="font-heading text-lg font-semibold text-foreground sm:text-xl">
              Chat with Neuro
            </h1>
            <p className="text-xs text-muted-foreground">
              Talk, reflect, and feel a little lighter.
            </p>
            {authLoaded && !isSignedIn ? (
              <p className="text-xs text-muted-foreground">
                <Link href="/login?redirect_url=%2Fai-companion" className="font-medium text-primary hover:underline">
                  Sign in
                </Link>{" "}
                to let Neuro remember you.
              </p>
            ) : null}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {memoryUserId ? (
            <Button
              variant="ghost"
              size="sm"
              nativeButton={false}
              render={<Link href="/settings/memory" />}
              className="text-muted-foreground hover:text-foreground"
            >
              <Brain aria-hidden="true" />
              <span className="sr-only sm:not-sr-only">Memory</span>
            </Button>
          ) : null}
          <Badge
            variant="secondary"
            className="h-6 shrink-0 gap-1.5 rounded-full px-2.5"
          >
            <span
              className="size-1.5 rounded-full bg-primary"
              aria-hidden="true"
            />
            Online
          </Badge>
          {readAloud?.speaking ? (
            <NuroPanelToggle label="Stop speaking" onClick={readAloud.onStopSpeaking}>
              <StopCircleRounded fontSize="small" />
            </NuroPanelToggle>
          ) : null}
          {readAloud ? (
            <NuroVoicePicker
              voices={readAloud.voices}
              selectedName={readAloud.voiceName}
              autoVoice={readAloud.autoVoice}
              onSelect={readAloud.onVoiceChange}
              onPreview={readAloud.onPreviewVoice}
            />
          ) : null}
          {readAloud ? (
            <NuroPanelToggle
              label="Read replies aloud"
              tooltip={readAloud.enabled ? "Read replies aloud: on" : "Read replies aloud: off"}
              pressed={readAloud.enabled}
              onClick={readAloud.onToggle}
            >
              {readAloud.enabled ? (
                <VolumeUpRounded fontSize="small" />
              ) : (
                <VolumeOffRounded fontSize="small" />
              )}
            </NuroPanelToggle>
          ) : null}
          {suggestionsOpen ? null : (
            <NuroPanelToggle label="Open suggestions" onClick={onOpenSuggestions}>
              <SpaOutlined fontSize="small" />
            </NuroPanelToggle>
          )}
        </div>
      </div>

      <div className="relative z-10 flex min-h-0 flex-1 flex-col">
        <div className="flex-1 overflow-y-auto px-4 py-5 sm:px-8">
          <div className="mx-auto flex max-w-3xl flex-col gap-5">
            {showCrisisBanner ? <NuroCrisisBanner contacts={bannerContacts} /> : null}

            {memoryUserId ? <NuroMemoryNotice key={memoryUserId} userId={memoryUserId} /> : null}

            {isLoadingMessages ? (
              <div className="flex flex-col gap-4">
                <div className="h-20 w-3/4 animate-pulse rounded-2xl bg-muted" />
                <div className="ml-auto h-14 w-2/3 animate-pulse rounded-2xl bg-muted" />
              </div>
            ) : showWelcome ? (
              <>
                <NuroMessageBubble role="ASSISTANT" content={WELCOME_MESSAGE} />
                <div className="flex flex-wrap gap-2 pl-10">
                  {SUGGESTED_PROMPTS.map((prompt) => (
                    <button
                      key={prompt}
                      type="button"
                      onClick={() => onSuggestedPrompt(prompt)}
                      className="rounded-full border border-border bg-background px-3.5 py-1.5 text-sm text-foreground transition-colors hover:border-primary/40 hover:bg-primary/5"
                    >
                      {prompt}
                    </button>
                  ))}
                </div>
              </>
            ) : (
              messages.map((message) => (
                <NuroMessageBubble
                  key={message.id}
                  role={message.role}
                  content={message.content}
                  createdAt={message.createdAt}
                  recommendations={message.recommendations}
                  crisisContacts={message.crisisContacts}
                  crisisTone={message.crisisTone}
                  isStreaming={message.isStreaming}
                />
              ))
            )}

            {showTypingIndicator ? <NuroTypingIndicator /> : null}

            {sendError ? (
              <div className="flex items-center gap-2 rounded-xl border border-destructive/20 bg-destructive/5 px-3.5 py-2.5 text-sm text-destructive">
                <AlertCircle className="size-4 shrink-0" aria-hidden="true" />
                {sendError}
              </div>
            ) : null}

            <div ref={scrollAnchorRef} />
          </div>
        </div>
        <div ref={setOverlayHost} className="pointer-events-none absolute inset-0" />
      </div>

      <div className="relative z-10 border-t border-border bg-background px-4 py-4 sm:px-8">
        <div className="mx-auto max-w-3xl">
          <NuroComposer
            value={draft}
            onChange={onDraftChange}
            onSend={onSend}
            onListeningChange={onListeningChange}
            overlayHost={overlayHost}
            disabled={isSending}
          />
        </div>
      </div>
    </main>
  );
}
