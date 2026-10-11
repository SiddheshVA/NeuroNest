
import { useEffect, useRef } from "react";
import Image from "next/image";
import Link from "next/link";
import { AlertCircle, Brain, Menu } from "lucide-react";
import { useAuth } from "@clerk/nextjs";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { NuroComposer } from "@/components/nuro/nuro-composer";
import { NuroMemoryNotice } from "@/components/nuro/nuro-memory-notice";
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
  onSuggestedPrompt,
  onOpenSidebar,
  bannerContacts,
}: {
  messages: NuroMessage[];
  isLoadingMessages: boolean;
  isSending: boolean;
  sendError: string | null;
  draft: string;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onSuggestedPrompt: (text: string) => void;
  onOpenSidebar: () => void;
  /** Helplines for the calm banner of this conversation (empty when none is needed). */
  bannerContacts: CrisisContact[];
}) {
  const scrollAnchorRef = useRef<HTMLDivElement>(null);
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
          <Button
            variant="ghost"
            size="icon"
            aria-label="Open conversation history"
            onClick={onOpenSidebar}
            className="md:hidden"
          >
            <Menu aria-hidden="true" />
          </Button>
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
        </div>
      </div>

      <div className="relative z-10 flex-1 overflow-y-auto px-4 py-5 sm:px-8">
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

      <div className="relative z-10 border-t border-border bg-background px-4 py-4 sm:px-8">
        <div className="mx-auto max-w-3xl">
          <NuroComposer
            value={draft}
            onChange={onDraftChange}
            onSend={onSend}
            disabled={isSending}
          />
        </div>
      </div>
    </main>
  );
}
