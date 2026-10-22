/**
 * Where to go after signing in, from the redirect_url query parameter. Only a relative path on this
 * site is accepted ("/professionals/123"); anything else (an external URL, "//evil.example",
 * "/\\evil.example", "javascript:...") gives null, and the caller falls back to its default.
 */
export function safeRedirectPath(value: string | null | undefined): string | null {
  if (!value) return null;
  const trimmed = value.trim();
  // Must be a single leading slash: "//host" and "/\host" are treated by browsers as another site.
  if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.includes("\\")) return null;
  // No control characters (a tab or newline inside "/\t/evil.example" is dropped by URL parsers).
  for (let i = 0; i < trimmed.length; i++) {
    const code = trimmed.charCodeAt(i);
    if (code < 0x20 || code === 0x7f) return null;
  }
  try {
    const url = new URL(trimmed, window.location.origin);
    if (url.origin !== window.location.origin) return null;
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return null;
  }
}

/** The sign-in page, returning to `path` afterwards. */
export function loginHref(path: string): string {
  return `/login?redirect_url=${encodeURIComponent(path)}`;
}
