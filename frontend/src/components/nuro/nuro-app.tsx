
import { useCallback, useEffect, useRef, useState } from "react";

import {
  Sheet,
  SheetContent,
  SheetTitle,
} from "@/components/ui/sheet";
import { NuroSidebar, NuroSidebarContent } from "@/components/nuro/nuro-sidebar";
import { NuroConversation } from "@/components/nuro/nuro-conversation";
import { NuroWellnessPanel } from "@/components/nuro/nuro-wellness-panel";
import {
  NuroApiError,
  deleteConversation,
  getConversation,
  getCrisisContacts,
  listConversations,
  streamChatMessage,
  type ConversationSummary,
  type CrisisContact,
  type NuroMessage,
  type StreamMeta,
} from "@/lib/nuro-client";
import { useSaveNeuroSuggestion } from "@/lib/relaxation/use-relaxation-activity";
import { createStreamReveal } from "@/lib/stream-reveal";

function genLocalId(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function NuroApp() {
  // This page is a dedicated, full-height app shell (see the fixed
  // container below) rather than a normal scrolling marketing page, so the
  // rest of the site's page scroll is suspended while it's mounted.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [isLoadingConversations, setIsLoadingConversations] = useState(true);
  const [conversationsError, setConversationsError] = useState<string | null>(
    null,
  );

  const [activeConversationId, setActiveConversationId] = useState<
    string | null
  >(null);
  const [messages, setMessages] = useState<NuroMessage[]>([]);
  const [isLoadingMessages, setIsLoadingMessages] = useState(false);
  // Helplines for the calm banner of the open conversation; always reset with it.
  const [bannerContacts, setBannerContacts] = useState<CrisisContact[]>([]);
  // Bumped whenever the visible chat changes, so a reply still streaming for the
  // previous chat can't add its bubble or banner to the new one.
  const chatEpochRef = useRef(0);

  const [draft, setDraft] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  const [searchQuery, setSearchQuery] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);

  const loadConversations = useCallback(async () => {
    setIsLoadingConversations(true);
    setConversationsError(null);
    try {
      const items = await listConversations();
      setConversations(items);
    } catch (error) {
      setConversationsError(
        error instanceof NuroApiError
          ? error.message
          : "Couldn't load your conversations.",
      );
    } finally {
      setIsLoadingConversations(false);
    }
  }, []);

  // Mount-time fetch: deliberately calls the raw API function (not
  // loadConversations) so every setState happens inside a .then/.catch
  // callback rather than synchronously in the effect body.
  useEffect(() => {
    let cancelled = false;

    listConversations()
      .then((items) => {
        if (!cancelled) setConversations(items);
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setConversationsError(
            error instanceof NuroApiError
              ? error.message
              : "Couldn't load your conversations.",
          );
        }
      })
      .finally(() => {
        if (!cancelled) setIsLoadingConversations(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const selectConversation = useCallback(
    async (id: string) => {
      setMobileSidebarOpen(false);
      if (id === activeConversationId) return;

      setActiveConversationId(id);
      setSendError(null);
      setDraft("");
      setBannerContacts([]);
      chatEpochRef.current += 1;
      setIsLoadingMessages(true);
      try {
        const { messages: loaded } = await getConversation(id);
        setMessages(loaded);
        // A reopened conversation with an earlier crisis turn keeps its calm banner.
        if (loaded.some((message) => message.riskFlag)) {
          getCrisisContacts()
            .then(setBannerContacts)
            .catch(() => {
              // Banner is a reminder only; the full alert still appears on any new crisis.
            });
        }
      } catch (error) {
        setMessages([]);
        setSendError(
          error instanceof NuroApiError
            ? error.message
            : "Couldn't load that conversation.",
        );
      } finally {
        setIsLoadingMessages(false);
      }
    },
    [activeConversationId],
  );

  const startNewChat = useCallback(() => {
    setMobileSidebarOpen(false);
    setActiveConversationId(null);
    setMessages([]);
    setBannerContacts([]);
    chatEpochRef.current += 1;
    setSendError(null);
    setDraft("");
  }, []);

  const handleDelete = useCallback(
    async (id: string) => {
      setDeletingId(id);
      try {
        await deleteConversation(id);
        setConversations((prev) => prev.filter((c) => c.id !== id));
        if (id === activeConversationId) {
          startNewChat();
        }
      } catch (error) {
        console.error("[nuro] failed to delete conversation", {
          reason: error instanceof Error ? error.message : "unknown error",
        });
      } finally {
        setDeletingId(null);
      }
    },
    [activeConversationId, startNewChat],
  );

  const saveNeuroSuggestion = useSaveNeuroSuggestion();

  const handleSend = useCallback(
    async (explicitText?: string) => {
      const text = (explicitText ?? draft).trim();
      if (!text || isSending) return;

      setSendError(null);
      setIsSending(true);
      setDraft("");

      const optimisticUserMessage: NuroMessage = {
        id: genLocalId("local-user"),
        role: "USER",
        content: text,
        createdAt: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, optimisticUserMessage]);

      // The reply bubble is created when the safety result ("meta") arrives — with the
      // full crisis alert straight away if THIS message was flagged — then fills in as
      // text streams. Updates only touch this turn's bubble, so switching conversation
      // mid-stream can't leak it into another chat.
      const assistantId = genLocalId("local-assistant");
      const epoch = chatEpochRef.current;
      const stillOnThisChat = () => epoch === chatEpochRef.current;
      const addAssistant = (message: Partial<NuroMessage>) => {
        if (!stillOnThisChat()) return;
        setMessages((prev) => [
          ...prev,
          { id: assistantId, role: "ASSISTANT", content: "", createdAt: new Date().toISOString(), ...message },
        ]);
      };
      const updateAssistant = (patch: Partial<NuroMessage>) =>
        setMessages((prev) => prev.map((m) => (m.id === assistantId ? { ...m, ...patch } : m)));
      const reveal = createStreamReveal((content) => updateAssistant({ content }));
      let meta: StreamMeta | null = null;

      try {
        const result = await streamChatMessage(
          { conversationId: activeConversationId ?? undefined, message: text },
          {
            onMeta: (received) => {
              meta = received;
              if (received.bannerContacts.length > 0 && stillOnThisChat()) {
                setBannerContacts(received.bannerContacts);
              }
              const isCrisis = received.safetyLevel === "HIGH";
              addAssistant({
                isStreaming: true,
                riskFlag: isCrisis || undefined,
                crisisContacts: isCrisis ? received.crisisContacts : undefined,
              });
            },
            onToken: (token) => reveal.push(token),
          },
        );

        const finalMeta = meta as StreamMeta | null;
        if (result.outcome === "done") {
          await reveal.finished();
          // Recommendations only once the reply is fully shown, and only if one was given.
          updateAssistant({
            isStreaming: false,
            recommendations:
              finalMeta && finalMeta.recommendations.length > 0 ? finalMeta.recommendations : undefined,
          });

          // A relaxation recommendation becomes a "Neuro suggestion" on the
          // Relaxation Hub. Never for a risk-flagged reply (those carry crisis
          // support instead). The texts are only read to pick the item; only
          // its id, type and the time are saved.
          if (
            finalMeta?.safetyLevel === "NONE" &&
            finalMeta.recommendations.some((recommendation) => recommendation.type === "RELAXATION")
          ) {
            saveNeuroSuggestion([text, reveal.received()]);
          }
        } else {
          const { error } = result;
          if (reveal.received()) {
            // Keep the partial reply; explain below it.
            reveal.showAll();
            updateAssistant({ isStreaming: false });
            if (stillOnThisChat()) setSendError(error.message);
          } else if (finalMeta) {
            // The reply never started: the bubble (with any crisis alert) carries the message.
            updateAssistant({ isStreaming: false, content: error.message });
          } else {
            // The safety check couldn't run: helplines offered calmly, not as a crisis alert.
            addAssistant({
              content: error.message,
              crisisContacts: error.crisisContacts.length > 0 ? error.crisisContacts : undefined,
              crisisTone: "calm",
            });
          }
        }

        if (!activeConversationId) {
          if (stillOnThisChat()) setActiveConversationId(result.conversationId);
          void loadConversations();
        } else {
          setConversations((prev) =>
            prev
              .map((c) =>
                c.id === result.conversationId
                  ? { ...c, updatedAt: new Date().toISOString() }
                  : c,
              )
              .sort(
                (a, b) =>
                  new Date(b.updatedAt).getTime() -
                  new Date(a.updatedAt).getTime(),
              ),
          );
        }
      } catch (error) {
        const message =
          error instanceof NuroApiError
            ? error.message
            : "Something went wrong. Please try again.";
        setSendError(message);

        // The server may have already saved this message (and, for a brand
        // new conversation, created it) before failing — adopt its id so a
        // follow-up send continues the same conversation instead of
        // silently starting a second one.
        if (
          error instanceof NuroApiError &&
          error.conversationId &&
          !activeConversationId
        ) {
          setActiveConversationId(error.conversationId);
          void loadConversations();
        }
      } finally {
        setIsSending(false);
      }
    },
    [activeConversationId, draft, isSending, loadConversations, saveNeuroSuggestion],
  );

  const sidebarProps = {
    conversations,
    activeConversationId,
    isLoading: isLoadingConversations,
    error: conversationsError,
    onRetry: () => void loadConversations(),
    searchQuery,
    onSearchChange: setSearchQuery,
    onSelect: (id: string) => void selectConversation(id),
    onNewChat: startNewChat,
    onDelete: (id: string) => void handleDelete(id),
    deletingId,
  };

  return (
    <div className="fixed inset-x-0 top-16 bottom-0 z-10 flex bg-background">
      <NuroSidebar {...sidebarProps} />

      <Sheet open={mobileSidebarOpen} onOpenChange={setMobileSidebarOpen}>
        <SheetContent side="left" className="w-[85%] max-w-xs p-0 sm:max-w-sm">
          <SheetTitle className="sr-only">Conversation history</SheetTitle>
          <NuroSidebarContent {...sidebarProps} />
        </SheetContent>
      </Sheet>

      <NuroConversation
        messages={messages}
        isLoadingMessages={isLoadingMessages}
        isSending={isSending}
        sendError={sendError}
        draft={draft}
        onDraftChange={setDraft}
        onSend={() => void handleSend()}
        onSuggestedPrompt={(text) => void handleSend(text)}
        onOpenSidebar={() => setMobileSidebarOpen(true)}
        bannerContacts={bannerContacts}
      />

      <NuroWellnessPanel />
    </div>
  );
}
