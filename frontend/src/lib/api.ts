import { getAuthToken } from "./clerkAuthBridge";

export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api";

export async function apiFetch(path: string, options: RequestInit = {}) {
  const token = await getAuthToken();
  const headers = new Headers(options.headers || {});
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  return fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
    credentials: "include",
  });
}
