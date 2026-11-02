// /api/wellness calls through the app's apiFetch (Clerk token when signed in, cookies included). Nothing here
// logs, stores or caches answers or results.

import { apiFetch } from "@/lib/api";
import { WellnessApiError, parseErrorBody } from "./errors.ts";
import type { CheckinItem, CheckinPage, CheckinRequest, CheckinResponse } from "./types.ts";
import { ERRORS } from "./copy.ts";

async function call(path: string, init: RequestInit = {}): Promise<Response> {
  let response: Response;
  try {
    response = await apiFetch(path, init);
  } catch {
    throw new WellnessApiError(0, ERRORS.network);
  }
  if (!response.ok) {
    let body: unknown = null;
    try {
      body = await response.json();
    } catch {
      // Not JSON (for example a proxy error page); the status alone decides.
    }
    throw parseErrorBody(response.status, body);
  }
  return response;
}

/** Scores a check-in. Stored only when the user is signed in (the backend decides; see `saved`). */
export async function submitCheckin(request: CheckinRequest): Promise<CheckinResponse> {
  const response = await call("/wellness/checkin", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  return (await response.json()) as CheckinResponse;
}

/**
 * What-if preview: the same scoring with ?save=false, so nothing is ever stored, even for a signed-in user
 * (backend/docs/wellness-checkin.md). The only call the what-if panel may make.
 */
export async function previewCheckin(request: CheckinRequest): Promise<CheckinResponse> {
  const response = await call("/wellness/checkin?save=false", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  return (await response.json()) as CheckinResponse;
}

export async function listCheckins(page: number, size: number): Promise<CheckinPage> {
  const response = await call(`/wellness/checkins?page=${page}&size=${size}`);
  const p = (await response.json()) as Partial<CheckinPage> | null;
  return {
    items: Array.isArray(p?.items) ? p.items : [],
    page: p?.page ?? page,
    size: p?.size ?? size,
    totalElements: p?.totalElements ?? 0,
    totalPages: p?.totalPages ?? 0,
  };
}

/** The latest stored check-in, or null when there is none yet (204). */
export async function latestCheckin(): Promise<CheckinItem | null> {
  const response = await call("/wellness/checkins/latest");
  if (response.status === 204) return null;
  return (await response.json()) as CheckinItem;
}

export async function deleteCheckin(id: string): Promise<void> {
  await call(`/wellness/checkins/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export async function deleteAllCheckins(): Promise<void> {
  await call("/wellness/checkins", { method: "DELETE" });
}
