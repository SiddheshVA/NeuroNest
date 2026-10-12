
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useNavigationType, useParams } from "react-router-dom";
import { useAuth } from "@clerk/nextjs";
import Drawer from "@mui/material/Drawer";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useTheme } from "@mui/material/styles";

import { NuroSidebar, NuroSidebarContent } from "@/components/nuro/nuro-sidebar";
import { NuroConversation } from "@/components/nuro/nuro-conversation";
import {
  NuroWellnessPanel,
  NuroWellnessPanelContent,
} from "@/components/nuro/nuro-wellness-panel";
import { usePersistentPanelOpen } from "@/components/nuro/nuro-panel-controls";
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
import { useSpeechOutput } from "@/lib/voice/use-speech-output";
import {
  readLastConversation,
  writeLastConversation,
} from "@/lib/nuro-last-conversation";

const BASE_PATH = "/ai-companion";
const CONVERSATION_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CHAT_NOT_FOUND_MESSAGE =
  "This chat couldn't be found, so here's a fresh one.";

/** The backend's answer for a chat that doesn't exist or isn't this user's. */
function isChatNotFound(error: unknown) {
  return (
    error instanceof NuroApiError &&
    (error.status === 400 || error.status === 403 || error.status === 404)
  );
}

const drawerPaperSx = {
  width: "85%",
  maxWidth: 320,
  backgroundColor: "var(--background)",
  color: "var(--foreground)",
} as const;

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

  // Signed-in users' chats live at /ai-companion/:conversationId. Guest chats
  // are readable by anyone holding their id, so a guest's id never goes in the
  // URL; their open chat is only remembered in sessionStorage.
  const { conversationId: urlConversationId } = useParams();
  const navigate = useNavigate();
  const navigationType = useNavigationType();
  const { isLoaded: authLoaded, isSignedIn, userId } = useAuth();
  const signedIn = authLoaded && Boolean(isSignedIn);
  const storageOwner = signedIn ? (userId ?? null) : null;

  const [activeConversationId, setActiveConversationId] = useState<
    string | null
  >(null);
  // The open chat, kept in sync with "last chat" for this browser session.
  const showConversation = useCallback(
    (id: string | null) => {
      setActiveConversationId(id);
      writeLastConversation(id, storageOwner);
    },
    [storageOwner],
  );
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
  const [mobileSuggestionsOpen, setMobileSuggestionsOpen] = useState(false);

  // Voice replies. Nothing is spoken while the mic is on, so Neuro's voice
  // never ends up in the user's transcript.
  const speechOutput = useSpeechOutput();
  const { speakReply, stop: stopSpeaking } = speechOutput;
  const listeningRef = useRef(false);
  const handleListeningChange = useCallback(
    (listening: boolean) => {
      listeningRef.current = listening;
      if (listening) stopSpeaking();
    },
    [stopSpeaking],
  );

  // From MUI `md` up both side panels sit inline and remember whether they're
  // open; below it they start closed and open as overlay drawers instead.
  const theme = useTheme();
  const isDesktop = useMediaQuery(theme.breakpoints.up("md"), { noSsr: true });
  const [historyPanelOpen, setHistoryPanelOpen] = usePersistentPanelOpen(
    "neuro.chat.historyPanel",
  );
  const [suggestionsPanelOpen, setSuggestionsPanelOpen] = usePersistentPanelOpen(
    "neuro.chat.suggestionsPanel",
  );
  const historyOpen = isDesktop ? historyPanelOpen : mobileSidebarOpen;
  const suggestionsOpen = isDesktop ? suggestionsPanelOpen : mobileSuggestionsOpen;
  const setHistoryOpen = isDesktop ? setHistoryPanelOpen : setMobileSidebarOpen;
  const setSuggestionsOpen = isDesktop
    ? setSuggestionsPanelOpen
    : setMobileSuggestionsOpen;

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

  const startNewChat = useCallback(() => {
    setMobileSidebarOpen(false);
    stopSpeaking();
    showConversation(null);
    setMessages([]);
    setBannerContacts([]);
    chatEpochRef.current += 1;
    setIsLoadingMessages(false);
    setSendError(null);
    setDraft("");
  }, [showConversation, stopSpeaking]);

  const selectConversation = useCallback(
    async (id: string) => {
      setMobileSidebarOpen(false);
      if (id === activeConversationId) return;

      stopSpeaking();
      showConversation(id);
      setSendError(null);
      setDraft("");
      setBannerContacts([]);
      chatEpochRef.current += 1;
      // A slower load for a chat the user already left must not overwrite the new one.
      const epoch = chatEpochRef.current;
      setIsLoadingMessages(true);
      try {
        if (!CONVERSATION_ID_PATTERN.test(id)) {
          throw new NuroApiError(CHAT_NOT_FOUND_MESSAGE, 404);
        }
        const { messages: loaded } = await getConversation(id);
        if (epoch !== chatEpochRef.current) return;
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
        if (epoch !== chatEpochRef.current) return;
        if (isChatNotFound(error)) {
          // Missing or someone else's chat: forget it and start fresh at the base URL.
          startNewChat();
          setSendError(CHAT_NOT_FOUND_MESSAGE);
          if (urlConversationId) navigate(BASE_PATH, { replace: true });
          return;
        }
        setMessages([]);
        setSendError(
          error instanceof NuroApiError
            ? error.message
            : "Couldn't load that conversation.",
        );
      } finally {
        if (epoch === chatEpochRef.current) setIsLoadingMessages(false);
      }
    },
    [
      activeConversationId,
      navigate,
      showConversation,
      startNewChat,
      stopSpeaking,
      urlConversationId,
    ],
  );

  // Follows the URL: direct links, refresh, back/forward and nav links. In-app
  // actions update the chat and the URL together, so this only acts when the
  // URL points somewhere other than the open chat.
  const handledRouteRef = useRef<string | null>(null);
  useEffect(() => {
    if (!authLoaded) return;
    const routeKey = `${storageOwner ?? "guest"}:${urlConversationId ?? ""}`;
    if (handledRouteRef.current === routeKey) return;
    const isFirstVisit = handledRouteRef.current === null;
    handledRouteRef.current = routeKey;

    if (urlConversationId) {
      if (!signedIn) {
        navigate(BASE_PATH, { replace: true });
      } else if (urlConversationId !== activeConversationId) {
        // Syncing with the router: runs once per navigation, not per render.
        // eslint-disable-next-line react-hooks/set-state-in-effect
        void selectConversation(urlConversationId);
      }
      return;
    }

    // Arriving at the base URL reopens this session's last chat, except via
    // back/forward within the page, which should land on the fresh chat it left.
    const remembered = readLastConversation(storageOwner);
    if (remembered && (isFirstVisit || navigationType !== "POP")) {
      if (signedIn) {
        navigate(`${BASE_PATH}/${remembered}`, { replace: true });
      } else if (remembered !== activeConversationId) {
        void selectConversation(remembered);
      }
    } else if (activeConversationId !== null) {
      startNewChat();
    }
  }, [
    activeConversationId,
    authLoaded,
    navigate,
    navigationType,
    selectConversation,
    signedIn,
    startNewChat,
    storageOwner,
    urlConversationId,
  ]);

  const openConversation = useCallback(
    (id: string) => {
      if (signedIn && id !== urlConversationId) {
        navigate(`${BASE_PATH}/${id}`);
      }
      void selectConversation(id);
    },
    [navigate, selectConversation, signedIn, urlConversationId],
  );

  const openNewChat = useCallback(() => {
    startNewChat();
    if (urlConversationId) navigate(BASE_PATH);
  }, [navigate, startNewChat, urlConversationId]);

  const handleDelete = useCallback(
    async (id: string) => {
      setDeletingId(id);
      try {
        await deleteConversation(id);
        setConversations((prev) => prev.filter((c) => c.id !== id));
        if (id === activeConversationId) {
          startNewChat();
          // Replace, so Back doesn't return to the deleted chat's URL.
          if (urlConversationId) navigate(BASE_PATH, { replace: true });
        }
      } catch (error) {
        console.error("[nuro] failed to delete conversation", {
          reason: error instanceof Error ? error.message : "unknown error",
        });
      } finally {
        setDeletingId(null);
      }
    },
    [activeConversationId, navigate, startNewChat, urlConversationId],
  );

  // A new chat gets its id from the backend on its first message. Replacing the
  // URL keeps the same mounted page, so typing and the reply aren't interrupted.
  const adoptNewConversation = useCallback(
    (id: string) => {
      showConversation(id);
      if (signedIn) navigate(`${BASE_PATH}/${id}`, { replace: true });
    },
    [navigate, showConversation, signedIn],
  );

  const saveNeuroSuggestion = useSaveNeuroSuggestion();

  const handleSend = useCallback(
    async (explicitText?: string) => {
      const text = (explicitText ?? draft).trim();
      if (!text || isSending) return;

      setSendError(null);
      setIsSending(true);
      setDraft("");
      stopSpeaking();

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
            onConversationCreated: (id) => {
              if (stillOnThisChat()) adoptNewConversation(id);
            },
          },
        );

        const finalMeta = meta as StreamMeta | null;
        // A crisis reply is followed by the helplines (from the backend's crisisResources).
        const spokenCrisisContacts =
          finalMeta?.safetyLevel === "HIGH" ? finalMeta.crisisContacts : undefined;
        const canSpeak = () => stillOnThisChat() && !listeningRef.current;
        if (result.outcome === "done") {
          // Speaking starts as soon as the whole reply is in; the typewriter keeps going.
          if (canSpeak()) speakReply(reveal.received(), spokenCrisisContacts);
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
            if (spokenCrisisContacts && canSpeak()) speakReply("", spokenCrisisContacts);
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
          // The new chat was already adopted in onConversationCreated.
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
          if (stillOnThisChat()) adoptNewConversation(error.conversationId);
          void loadConversations();
        }
      } finally {
        setIsSending(false);
      }
    },
    [
      activeConversationId,
      adoptNewConversation,
      draft,
      isSending,
      loadConversations,
      saveNeuroSuggestion,
      speakReply,
      stopSpeaking,
    ],
  );

  // Signed-in: the highlighted chat is the one in the URL.
  const highlightedConversationId = signedIn
    ? (urlConversationId ?? null)
    : activeConversationId;
  // Until auth is known (and while the URL's chat is being opened), show the
  // loading skeleton rather than flashing the welcome message.
  const awaitingRoute =
    !authLoaded ||
    (signedIn &&
      urlConversationId !== undefined &&
      urlConversationId !== activeConversationId);

  const sidebarProps = {
    conversations,
    activeConversationId: highlightedConversationId,
    isLoading: isLoadingConversations,
    error: conversationsError,
    onRetry: () => void loadConversations(),
    searchQuery,
    onSearchChange: setSearchQuery,
    onSelect: openConversation,
    onNewChat: openNewChat,
    onDelete: (id: string) => void handleDelete(id),
    deletingId,
    onClose: () => setHistoryOpen(false),
  };

  return (
    <div className="fixed inset-x-0 top-16 bottom-0 z-10 flex bg-background">
      {isDesktop ? (
        <NuroSidebar open={historyPanelOpen} {...sidebarProps} />
      ) : (
        <Drawer
          anchor="left"
          open={mobileSidebarOpen}
          onClose={() => setMobileSidebarOpen(false)}
          slotProps={{
            paper: { sx: drawerPaperSx, role: "dialog", "aria-label": "Chat history" },
          }}
        >
          <NuroSidebarContent {...sidebarProps} />
        </Drawer>
      )}

      <NuroConversation
        messages={messages}
        isLoadingMessages={isLoadingMessages || awaitingRoute}
        isSending={isSending}
        sendError={sendError}
        draft={draft}
        onDraftChange={setDraft}
        onSend={(viaVoice) => {
          // Speaking to Neuro switches spoken replies on; it then stays as the user leaves it.
          if (viaVoice) speechOutput.setEnabled(true);
          void handleSend();
        }}
        onListeningChange={handleListeningChange}
        readAloud={
          speechOutput.supported
            ? {
                enabled: speechOutput.enabled,
                speaking: speechOutput.speaking,
                onToggle: () => speechOutput.setEnabled(!speechOutput.enabled),
                onStopSpeaking: stopSpeaking,
                voices: speechOutput.voices,
                voiceName: speechOutput.voiceName,
                autoVoice: speechOutput.autoVoice,
                onVoiceChange: speechOutput.setVoiceName,
                // Never speak into an open mic.
                onPreviewVoice: (name) => {
                  if (!listeningRef.current) speechOutput.preview(name);
                },
              }
            : undefined
        }
        onSuggestedPrompt={(text) => void handleSend(text)}
        historyOpen={historyOpen}
        onOpenHistory={() => setHistoryOpen(true)}
        suggestionsOpen={suggestionsOpen}
        onOpenSuggestions={() => setSuggestionsOpen(true)}
        bannerContacts={bannerContacts}
      />

      {isDesktop ? (
        <NuroWellnessPanel
          open={suggestionsPanelOpen}
          onClose={() => setSuggestionsPanelOpen(false)}
        />
      ) : (
        <Drawer
          anchor="right"
          open={mobileSuggestionsOpen}
          onClose={() => setMobileSuggestionsOpen(false)}
          slotProps={{
            paper: { sx: drawerPaperSx, role: "dialog", "aria-label": "Suggestions" },
          }}
        >
          <NuroWellnessPanelContent onClose={() => setMobileSuggestionsOpen(false)} />
        </Drawer>
      )}
    </div>
  );
}
