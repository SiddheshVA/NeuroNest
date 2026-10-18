import { API_BASE } from "@/lib/api";
import type {
  AdminDocument,
  LogEntry,
  PageResponse,
  ProfessionalDetail,
  ProfessionalListFilters,
  ProfessionalRequest,
  ProfessionalSummary,
  ProfessionalUpdateResult,
  ReviewQueueItem,
  VerifyRequest,
} from "./types";

// Admin API client. Completely separate from the public apiFetch: it never sends the Clerk token or
// the user cookie, only the admin token kept in sessionStorage (cleared when the tab closes).

const TOKEN_KEY = "neuronest.admin.session";

export const SESSION_EXPIRED_MESSAGE = "Your session has expired. Please log in again.";
export const TOO_MANY_ATTEMPTS_MESSAGE = "Too many attempts. Please wait 15 minutes and try again.";
const NETWORK_ERROR_MESSAGE =
  "The server could not be reached. Please check that the backend is running and try again.";

interface StoredSession {
  token: string;
  expiresAt: string;
}

export class AdminApiError extends Error {
  readonly status: number;
  /** The "missing" list some 400 responses include, such as unconfirmed checklist items. */
  readonly missing: string[];

  constructor(status: number, message: string, missing: string[] = []) {
    super(message);
    this.status = status;
    this.missing = missing;
  }
}

function readSession(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(TOKEN_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredSession;
    return typeof parsed?.token === "string" && typeof parsed?.expiresAt === "string" ? parsed : null;
  } catch {
    return null;
  }
}

export function saveAdminSession(token: string, expiresAt: string) {
  try {
    sessionStorage.setItem(TOKEN_KEY, JSON.stringify({ token, expiresAt }));
  } catch {
    // Storage unavailable (for example, a locked-down private window): the login simply won't persist.
  }
}

export function clearAdminSession() {
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // Nothing to clear.
  }
}

// Milliseconds until the stored token expires, or null when there is no session at all.
export function adminSessionRemainingMs(): number | null {
  const session = readSession();
  if (!session) return null;
  const expiresAt = Date.parse(session.expiresAt);
  return Number.isNaN(expiresAt) ? 0 : expiresAt - Date.now();
}

export function getValidAdminToken(): string | null {
  const session = readSession();
  const remaining = adminSessionRemainingMs();
  return session && remaining !== null && remaining > 0 ? session.token : null;
}

// Called on any 401 from a protected admin endpoint. AdminLayout registers a handler that sends the
// admin to the login page; the fallback is a full page load.
let unauthorizedHandler: (() => void) | null = null;

export function setAdminUnauthorizedHandler(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

export const EXPIRED_LOGIN_PATH = "/admin/login?expired=1";

function handleUnauthorized(): never {
  clearAdminSession();
  if (unauthorizedHandler) {
    unauthorizedHandler();
  } else {
    window.location.assign(EXPIRED_LOGIN_PATH);
  }
  throw new AdminApiError(401, SESSION_EXPIRED_MESSAGE);
}

async function errorMessage(response: Response): Promise<string> {
  return (await errorDetails(response)).message;
}

async function errorDetails(response: Response): Promise<{ message: string; missing: string[] }> {
  try {
    const body = await response.json();
    if (body && typeof body.error === "string") {
      const missing: string[] = Array.isArray(body.missing)
        ? body.missing.filter((item: unknown): item is string => typeof item === "string")
        : [];
      return { message: body.error, missing };
    }
  } catch {
    // Not JSON; fall through to a generic message.
  }
  return { message: `The request failed with status ${response.status}. Please try again.`, missing: [] };
}

async function send(path: string, init: RequestInit, token: string | null): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body !== undefined) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  try {
    return await fetch(`${API_BASE}${path}`, { ...init, headers, credentials: "omit" });
  } catch {
    throw new AdminApiError(0, NETWORK_ERROR_MESSAGE);
  }
}

// The one helper every protected admin call goes through.
async function adminRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getValidAdminToken();
  if (!token) handleUnauthorized();
  const response = await send(path, init, token);
  if (response.status === 401) handleUnauthorized();
  if (!response.ok) {
    const { message, missing } = await errorDetails(response);
    throw new AdminApiError(response.status, message, missing);
  }
  return (await response.json()) as T;
}

export async function adminLogin(username: string, password: string): Promise<void> {
  const response = await send(
    "/admin/login",
    { method: "POST", body: JSON.stringify({ username, password }) },
    null,
  );
  if (response.status === 429) throw new AdminApiError(429, TOO_MANY_ATTEMPTS_MESSAGE);
  if (!response.ok) throw new AdminApiError(response.status, await errorMessage(response));
  const body = (await response.json()) as { token: string; expiresAt: string };
  saveAdminSession(body.token, body.expiresAt);
}

export function listProfessionals(filters: ProfessionalListFilters) {
  const params = new URLSearchParams();
  if (filters.q?.trim()) params.set("q", filters.q.trim());
  if (filters.credentialType) params.set("credentialType", filters.credentialType);
  if (filters.verificationStatus) params.set("verificationStatus", filters.verificationStatus);
  if (filters.active) params.set("active", filters.active);
  params.set("page", String(filters.page));
  params.set("size", String(filters.size));
  return adminRequest<PageResponse<ProfessionalSummary>>(`/admin/professionals?${params}`);
}

export function getProfessional(id: string) {
  return adminRequest<ProfessionalDetail>(`/admin/professionals/${encodeURIComponent(id)}`);
}

export function createProfessional(request: ProfessionalRequest) {
  return adminRequest<ProfessionalDetail>("/admin/professionals", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function updateProfessional(id: string, request: ProfessionalRequest) {
  return adminRequest<ProfessionalUpdateResult>(`/admin/professionals/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

export function setProfessionalActive(id: string, active: boolean) {
  return adminRequest<ProfessionalDetail>(`/admin/professionals/${encodeURIComponent(id)}/active`, {
    method: "PATCH",
    body: JSON.stringify({ active }),
  });
}

// ---- Verification ----

const professionalPath = (id: string) => `/admin/professionals/${encodeURIComponent(id)}`;

export function getReviewQueue(page: number, size: number) {
  return adminRequest<PageResponse<ReviewQueueItem>>(`/admin/professionals/review-queue?page=${page}&size=${size}`);
}

export function getDocuments(id: string) {
  return adminRequest<AdminDocument[]>(`${professionalPath(id)}/documents`);
}

export function getVerificationLog(id: string) {
  return adminRequest<LogEntry[]>(`${professionalPath(id)}/log`);
}

// Fetched with the admin token in the Authorization header and returned as a Blob. The token never goes
// into a URL, and there is no direct link to the file.
export async function getDocumentFile(id: string, docId: string): Promise<Blob> {
  const token = getValidAdminToken();
  if (!token) handleUnauthorized();
  const response = await send(`${professionalPath(id)}/documents/${encodeURIComponent(docId)}/file`, {}, token);
  if (response.status === 401) handleUnauthorized();
  if (!response.ok) throw new AdminApiError(response.status, await errorMessage(response));
  return response.blob();
}

export function verifyProfessional(id: string, request: VerifyRequest) {
  return adminRequest<ProfessionalDetail>(`${professionalPath(id)}/verify`, {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function requestMoreInformation(id: string, note: string) {
  return adminRequest<ProfessionalDetail>(`${professionalPath(id)}/needs-info`, {
    method: "POST",
    body: JSON.stringify({ note }),
  });
}

export function rejectProfessional(id: string, reason: string) {
  return adminRequest<ProfessionalDetail>(`${professionalPath(id)}/reject`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}
