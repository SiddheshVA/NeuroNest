/**
 * The conversation last open in Neuro, remembered for this browser session only
 * (sessionStorage), so coming back to the AI Companion reopens it. Tagged with
 * its owner so a guest's chat is never restored for a signed-in user, or the
 * reverse.
 */

const LAST_CONVERSATION_KEY = "nuronest:last-conversation";

interface StoredConversation {
  id: string;
  owner: string | null;
}

export function readLastConversation(owner: string | null): string | null {
  try {
    const raw = window.sessionStorage.getItem(LAST_CONVERSATION_KEY);
    if (!raw) return null;
    const stored = JSON.parse(raw) as Partial<StoredConversation>;
    return typeof stored.id === "string" && (stored.owner ?? null) === owner
      ? stored.id
      : null;
  } catch {
    return null;
  }
}

export function writeLastConversation(id: string | null, owner: string | null) {
  try {
    if (id) {
      const stored: StoredConversation = { id, owner };
      window.sessionStorage.setItem(LAST_CONVERSATION_KEY, JSON.stringify(stored));
    } else {
      window.sessionStorage.removeItem(LAST_CONVERSATION_KEY);
    }
  } catch {
    // Storage unavailable (e.g. private mode): the chat just won't be restored.
  }
}
