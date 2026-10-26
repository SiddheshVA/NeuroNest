import { getAuthToken } from "./clerkAuthBridge";

// The backend's API address, including /api (e.g. https://api.example.com/api). VITE_API_URL, or the
// older VITE_API_BASE_URL; the local backend when neither is set. Read at build time.
export const API_BASE: string =
  import.meta.env.VITE_API_URL || import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api";

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
