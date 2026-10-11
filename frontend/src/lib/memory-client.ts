/**
 * Typed fetch layer for Neuro's long-term memory controls (signed-in users only):
 *   - GET    /api/me/memory-settings  -> { enabled }
 *   - PUT    /api/me/memory-settings  <- { enabled }
 *   - GET    /api/me/memories         -> [{ id, content, createdAt }]
 *   - DELETE /api/me/memories/{id}
 *   - DELETE /api/me/memories         (clear all)
 */

import { apiFetch } from "@/lib/api";

export interface SavedMemory {
  id: string;
  content: string;
  createdAt: string;
}

export class MemoryApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "MemoryApiError";
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await apiFetch(path, init);
  } catch {
    throw new MemoryApiError(
      "Can't reach the NeuroNest server. Please try again in a moment.",
      0,
    );
  }

  let data: unknown = null;
  try {
    data = await res.json();
  } catch {
    // No JSON body — fall through to the generic message below.
  }

  if (!res.ok) {
    const body = data as { error?: string; message?: string } | null;
    throw new MemoryApiError(
      body?.error ?? body?.message ?? "Something went wrong. Please try again.",
      res.status,
    );
  }

  return data as T;
}

export async function getMemorySettings(): Promise<{ enabled: boolean }> {
  return request<{ enabled: boolean }>("/me/memory-settings");
}

export async function updateMemorySettings(enabled: boolean): Promise<{ enabled: boolean }> {
  return request<{ enabled: boolean }>("/me/memory-settings", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
}

export async function listMemories(): Promise<SavedMemory[]> {
  const items = await request<
    { id: string; content: string; createdAt?: string; created_at?: string }[]
  >("/me/memories");
  return items.map((m) => ({
    id: m.id,
    content: m.content,
    createdAt: m.createdAt ?? m.created_at ?? "",
  }));
}

export async function deleteMemory(id: string): Promise<void> {
  await request(`/me/memories/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export async function clearMemories(): Promise<void> {
  await request("/me/memories", { method: "DELETE" });
}
