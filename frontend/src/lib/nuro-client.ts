/**
 * Thin, typed fetch layer between the Neuro chat UI and the Spring Boot
 * backend. Client components never call the backend directly — everything
 * goes through the functions below.
 *
 * Backend model: one chat conversation per session. A "conversation id" in
 * this UI is therefore the backend session id:
 *   - new chat        -> POST /api/sessions, then POST /api/sessions/{id}/chat/stream
 *   - continue chat   -> POST /api/sessions/{id}/chat/stream (Server-Sent Events)
 *   - load messages   -> GET  /api/sessions/{id}/chat
 *   - list (signed in)-> GET  /api/me/conversations
 *   - delete          -> DELETE /api/me/conversations/{id}
 * Guests can chat too; their conversations are remembered for this browser
 * tab only (sessionStorage), since the backend lists history per account.
 */

import { apiFetch } from "@/lib/api";
import { getAuthToken } from "@/lib/clerkAuthBridge";

export interface ConversationSummary {
  id: string;
  title: string | null;
  createdAt: string;
  updatedAt: string;
}

export type MessageRole = "USER" | "ASSISTANT" | "SYSTEM";

export type RecommendationType =
  | "ASSESSMENT"
  | "RELAXATION"
  | "RESOURCES"
  | "PROFESSIONAL";

export interface NuroRecommendation {
  type: RecommendationType;
  title: string;
  description: string;
  route: string;
  priority: number;
}

/** A crisis helpline, exactly as sent by the backend (CrisisResources). */
export interface CrisisContact {
  name: string;
  phone: string;
  availability: string;
  /** tel: link for the first number. */
  href: string;
}

export interface NuroMessage {
  id: string;
  role: MessageRole;
  content: string;
  createdAt: string;
  recommendations?: NuroRecommendation[];
  /** Helplines shown above the reply (from the backend's CrisisResources). */
  crisisContacts?: CrisisContact[];
  /**
   * "alert": this message's own crisis signal (full red box). "calm": helplines offered
   * because Neuro couldn't reply, not because of a crisis. Defaults to "alert".
   */
  crisisTone?: "alert" | "calm";
  /** Assistant reply to a message the safety check flagged as a crisis. */
  riskFlag?: boolean;
  /** True while reply text is still arriving. */
  isStreaming?: boolean;
}

export type SafetyLevel = "NONE" | "HIGH";

/** The safety check result ("meta" event), mapped for the UI. */
export interface StreamMeta {
  /** HIGH only when THIS message carries a crisis signal. */
  safetyLevel: SafetyLevel;
  /** Full alert for this message (empty unless safetyLevel is HIGH). */
  crisisContacts: CrisisContact[];
  /** An earlier message in this conversation was a crisis: keep the calm banner. */
  earlierRisk: boolean;
  /** Helplines for the calm banner (empty unless there was a crisis in this conversation). */
  bannerContacts: CrisisContact[];
  /** Shown only once the reply is complete. */
  recommendations: NuroRecommendation[];
}

export interface StreamTurnError {
  code: string;
  message: string;
  crisisContacts: CrisisContact[];
}

export type StreamTurnResult =
  | { conversationId: string; outcome: "done" }
  | { conversationId: string; outcome: "error"; error: StreamTurnError };

export class NuroApiError extends Error {
  status: number;
  /**
   * Present when the backend session already exists before the failure —
   * lets a failed send still be "adopted" so a retry continues the same
   * conversation instead of creating a duplicate one.
   */
  conversationId?: string;

  constructor(message: string, status: number, conversationId?: string) {
    super(message);
    this.name = "NuroApiError";
    this.status = status;
    this.conversationId = conversationId;
  }
}

// ---- Backend response shapes (Spring Boot) ----

interface BackendConversationSummary {
  sessionId: string;
  startedAt: string;
  preview: string;
  messageCount: number;
}

interface BackendChatMessage {
  id: string;
  role: string;
  content: string;
  createdAt: string;
  riskFlag?: boolean;
}

interface BackendCrisisResource {
  name: string;
  phone: string;
  availability: string;
}

interface BackendSafetyMeta {
  riskFlag: boolean;
  riskReason: string | null;
  recommendationTier: number;
  recommendedAction: string;
  recommendedInstrument: string | null;
  crisisResources: BackendCrisisResource[] | null;
  earlierRiskInConversation?: boolean;
}

async function parseJsonOrThrow<T>(res: Response, conversationId?: string): Promise<T> {
  let data: unknown = null;
  try {
    data = await res.json();
  } catch {
    // No JSON body — fall through to the generic message below.
  }

  if (!res.ok) {
    const body = data as { error?: string; message?: string } | null;
    const message =
      body?.error ?? body?.message ?? "Something went wrong. Please try again.";
    throw new NuroApiError(message, res.status, conversationId);
  }

  return data as T;
}

async function request<T>(path: string, init?: RequestInit, conversationId?: string): Promise<T> {
  let res: Response;
  try {
    res = await apiFetch(path, init);
  } catch {
    throw new NuroApiError(
      "Can't reach the NeuroNest server. Please check that it's running and try again.",
      0,
      conversationId,
    );
  }
  return parseJsonOrThrow<T>(res, conversationId);
}

// ---- Guest conversation memory (this tab only) ----

const GUEST_KEY = "nuronest:guest-conversations";

function readGuestConversations(): ConversationSummary[] {
  try {
    const raw = window.sessionStorage.getItem(GUEST_KEY);
    return raw ? (JSON.parse(raw) as ConversationSummary[]) : [];
  } catch {
    return [];
  }
}

function writeGuestConversations(items: ConversationSummary[]) {
  try {
    window.sessionStorage.setItem(GUEST_KEY, JSON.stringify(items));
  } catch {
    // Storage unavailable (e.g. private mode) — guests just won't see history.
  }
}

async function isSignedIn() {
  return Boolean(await getAuthToken());
}

const MAX_TITLE_LENGTH = 60;

function deriveTitle(message: string): string {
  const collapsed = message.replace(/\s+/g, " ").trim();
  return collapsed.length <= MAX_TITLE_LENGTH
    ? collapsed
    : `${collapsed.slice(0, MAX_TITLE_LENGTH)}…`;
}

// ---- Mapping the safety check onto crisis contacts and recommendation cards ----

const INSTRUMENT_ROUTES: Record<string, { route: string; label: string }> = {
  PHQ9: { route: "/assessment/phq9", label: "PHQ-9 mood check-in" },
  GAD7: { route: "/assessment/gad7", label: "GAD-7 anxiety check-in" },
  PSS10: { route: "/assessment/pss10", label: "PSS-10 stress check-in" },
  // WHO-5 has no page of its own; this opens its check-in on the Assessment hub.
  WHO5: { route: "/assessment?start=who5", label: "WHO-5 wellbeing check-in" },
};

function telHref(phone: string) {
  // "14416 or 1800-891-4416" -> dial the first number.
  const first = phone.split(/\s+or\s+/i)[0];
  return `tel:${first.replace(/[^\d+]/g, "")}`;
}

function toCrisisContacts(resources: BackendCrisisResource[] | null | undefined): CrisisContact[] {
  return (resources ?? []).map((r) => ({ ...r, href: telHref(r.phone) }));
}

function assessmentCard(instrument: string | null, priority: number): NuroRecommendation {
  const target = INSTRUMENT_ROUTES[instrument ?? "WHO5"] ?? INSTRUMENT_ROUTES.WHO5;
  return {
    type: "ASSESSMENT",
    title: "Take a short check-in",
    description: `A ${target.label} — a few minutes, not a diagnosis.`,
    route: target.route,
    priority,
  };
}

function toRecommendations(res: BackendSafetyMeta): NuroRecommendation[] {
  // A crisis shows helplines instead (crisisContacts), never ordinary suggestions.
  if (res.riskFlag) return [];

  switch (res.recommendedAction) {
    case "assessment":
      return [assessmentCard(res.recommendedInstrument, 0)];
    case "find_professional":
      return [
        {
          type: "PROFESSIONAL",
          title: "Find a professional",
          description: "Counsellors, psychologists and psychiatrists who can support you.",
          route: "/find-professional",
          priority: 0,
        },
        ...(res.recommendedInstrument ? [assessmentCard(res.recommendedInstrument, 1)] : []),
      ];
    case "relaxation":
      return [
        {
          type: "RELAXATION",
          title: "Visit the Relaxation Hub",
          description: "Breathing, meditation and calming sounds to help you reset.",
          route: "/relaxation",
          priority: 0,
        },
      ];
    case "wellness_resources":
      return [
        {
          type: "RESOURCES",
          title: "Explore wellness resources",
          description: "Guides and coping strategies for everyday wellbeing.",
          route: "/resources",
          priority: 0,
        },
      ];
    default:
      return [];
  }
}

function toStreamMeta(res: BackendSafetyMeta): StreamMeta {
  const contacts = toCrisisContacts(res.crisisResources);
  return {
    safetyLevel: res.riskFlag ? "HIGH" : "NONE",
    crisisContacts: res.riskFlag ? contacts : [],
    earlierRisk: Boolean(res.earlierRiskInConversation),
    bannerContacts: res.riskFlag || res.earlierRiskInConversation ? contacts : [],
    recommendations: toRecommendations(res),
  };
}

/** The helplines, from the backend (never a local copy). */
export async function getCrisisContacts(): Promise<CrisisContact[]> {
  return toCrisisContacts(await request<BackendCrisisResource[]>("/safety/crisis-resources"));
}

// ---- Server-Sent Events over fetch (POST, so EventSource can't be used) ----

interface SseEvent {
  event: string;
  data: string;
}

function parseSseBlock(block: string): SseEvent | null {
  let event = "message";
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (!line || line.startsWith(":")) continue;
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") event = value;
    else if (field === "data") data.push(value);
  }
  return data.length > 0 ? { event, data: data.join("\n") } : null;
}

async function* readSseEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<SseEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, "\n");
      let separator = buffer.indexOf("\n\n");
      while (separator !== -1) {
        const parsed = parseSseBlock(buffer.slice(0, separator));
        buffer = buffer.slice(separator + 2);
        if (parsed) yield parsed;
        separator = buffer.indexOf("\n\n");
      }
    }
    const rest = parseSseBlock(buffer + decoder.decode());
    if (rest) yield rest;
  } finally {
    reader.releaseLock();
  }
}

const INTERRUPTED = {
  code: "STREAM_INTERRUPTED",
  message: "My reply got cut off. Please try sending your message again.",
};

// ---- Public API used by the Neuro components ----

export async function listConversations(): Promise<ConversationSummary[]> {
  if (!(await isSignedIn())) {
    return readGuestConversations();
  }

  const items = await request<BackendConversationSummary[]>("/me/conversations");
  return items.map((c) => ({
    id: c.sessionId,
    title: c.preview,
    createdAt: c.startedAt,
    updatedAt: c.startedAt,
  }));
}

export async function getConversation(
  id: string,
): Promise<{ conversation: ConversationSummary; messages: NuroMessage[] }> {
  const history = await request<BackendChatMessage[]>(
    `/sessions/${encodeURIComponent(id)}/chat`,
  );

  const messages: NuroMessage[] = history.map((m) => ({
    id: m.id,
    role: m.role === "assistant" ? "ASSISTANT" : "USER",
    content: m.content,
    createdAt: m.createdAt,
    // Only an assistant reply's flag marks a real crisis turn (user messages are also
    // flagged when the safety check couldn't run).
    riskFlag: m.role === "assistant" && m.riskFlag ? true : undefined,
  }));

  const first = messages.find((m) => m.role === "USER");
  const createdAt = messages[0]?.createdAt ?? new Date().toISOString();
  return {
    conversation: {
      id,
      title: first ? deriveTitle(first.content) : null,
      createdAt,
      updatedAt: messages[messages.length - 1]?.createdAt ?? createdAt,
    },
    messages,
  };
}

export async function deleteConversation(id: string): Promise<void> {
  if (!(await isSignedIn())) {
    writeGuestConversations(readGuestConversations().filter((c) => c.id !== id));
    return;
  }
  await request(`/me/conversations/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/**
 * Sends a message and streams the reply. Calls onMeta first (safety result,
 * including crisis contacts), then onToken for each chunk of reply text.
 * Throws NuroApiError only if the turn could not start (network, session or
 * validation errors); failures after that are returned as outcome "error".
 */
export async function streamChatMessage(
  params: { conversationId?: string; message: string },
  handlers: { onMeta: (meta: StreamMeta) => void; onToken: (text: string) => void },
): Promise<StreamTurnResult> {
  let conversationId = params.conversationId;

  if (!conversationId) {
    const session = await request<{ id: string }>("/sessions", { method: "POST" });
    conversationId = session.id;

    if (!(await isSignedIn())) {
      const now = new Date().toISOString();
      writeGuestConversations([
        { id: conversationId, title: deriveTitle(params.message), createdAt: now, updatedAt: now },
        ...readGuestConversations(),
      ]);
    }
  }

  let res: Response;
  try {
    res = await apiFetch(`/sessions/${encodeURIComponent(conversationId)}/chat/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        // JSON is accepted too, so errors raised before the stream starts stay readable.
        Accept: "text/event-stream, application/json",
      },
      body: JSON.stringify({ message: params.message }),
    });
  } catch {
    throw new NuroApiError(
      "Can't reach the NeuroNest server. Please check that it's running and try again.",
      0,
      conversationId,
    );
  }
  if (!res.ok || !res.body) {
    await parseJsonOrThrow(res, conversationId);
    throw new NuroApiError("Something went wrong. Please try again.", res.status, conversationId);
  }

  try {
    for await (const { event, data } of readSseEvents(res.body)) {
      const payload = JSON.parse(data);
      switch (event) {
        case "meta":
          handlers.onMeta(toStreamMeta(payload as BackendSafetyMeta));
          break;
        case "token":
          handlers.onToken((payload as { text: string }).text);
          break;
        case "done":
          return { conversationId, outcome: "done" };
        case "error": {
          const error = payload as {
            code: string;
            message: string;
            crisisResources: BackendCrisisResource[] | null;
          };
          return {
            conversationId,
            outcome: "error",
            error: {
              code: error.code,
              message: error.message,
              crisisContacts: toCrisisContacts(error.crisisResources),
            },
          };
        }
      }
    }
  } catch {
    // Connection dropped or an unreadable event: treated like an interrupted reply.
  }
  return { conversationId, outcome: "error", error: { ...INTERRUPTED, crisisContacts: [] } };
}
