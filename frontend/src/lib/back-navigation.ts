import { useCallback, type MouseEvent } from "react";
import { matchPath, useNavigate } from "react-router-dom";

/**
 * Where each inner page's "Back to ..." control goes. This list is the only place parents are defined;
 * the public app, the professional portal and the admin area all read it. The first matching pattern
 * wins. Pages that are not listed have no back control: Home, sign-in and sign-up, the account pages,
 * /pro/join, /pro/dashboard, /admin and /admin/login.
 */
export interface BackTarget {
  /** An in-app path, never a sign-in page or an outside address. May carry a query (a list's tab). */
  parentPath: string;
  /** Shown as "Back to <label>". */
  label: string;
}

const BACK_ROUTES: ({ pattern: string } & BackTarget)[] = [
  // ---- Public and user app ----
  { pattern: "/dashboard", parentPath: "/", label: "Home" },
  { pattern: "/appointments", parentPath: "/dashboard", label: "Dashboard" },
  { pattern: "/dashboard/check-ins/:id", parentPath: "/dashboard", label: "Dashboard" },
  { pattern: "/professionals", parentPath: "/", label: "Home" },
  { pattern: "/professionals/:id", parentPath: "/professionals", label: "Find Licensed Professional" },
  { pattern: "/assessment", parentPath: "/", label: "Home" },
  { pattern: "/assessment/result/:type", parentPath: "/assessment", label: "Assessment" },
  { pattern: "/assessment/:test", parentPath: "/assessment", label: "Assessment" },
  { pattern: "/ai-companion", parentPath: "/", label: "Home" },
  { pattern: "/ai-companion/:conversationId", parentPath: "/", label: "Home" },
  { pattern: "/settings/memory", parentPath: "/ai-companion", label: "Chat with Neuro" },
  { pattern: "/resources", parentPath: "/", label: "Home" },
  { pattern: "/resources/articles/:slug", parentPath: "/resources?tab=articles", label: "Resources" },
  { pattern: "/resources/videos/:slug", parentPath: "/resources?tab=videos", label: "Resources" },
  { pattern: "/resources/guides/:slug", parentPath: "/resources?tab=guides", label: "Resources" },
  { pattern: "/relaxation", parentPath: "/", label: "Home" },
  { pattern: "/relaxation/breathing/:exerciseId", parentPath: "/relaxation/breathing", label: "Breathing Exercises" },
  { pattern: "/relaxation/meditation/:slug", parentPath: "/relaxation/meditation", label: "Meditation" },
  { pattern: "/relaxation/mindful-reset/:activityId", parentPath: "/relaxation/mindful-reset", label: "Mindful Reset" },
  // Breathing, Meditation, Mindful Reset, Sleep Support and Favorites.
  { pattern: "/relaxation/:section", parentPath: "/relaxation", label: "Relaxation Hub" },

  // ---- Professional portal ----
  // The profile page shows this only once the person has a professional account.
  { pattern: "/pro/profile", parentPath: "/pro/dashboard", label: "Dashboard" },
  { pattern: "/pro/requests", parentPath: "/pro/dashboard", label: "Dashboard" },
  { pattern: "/pro/requests/:id", parentPath: "/pro/requests", label: "Requests" },
  { pattern: "/pro/appointments", parentPath: "/pro/dashboard", label: "Dashboard" },
  { pattern: "/pro/days-off", parentPath: "/pro/dashboard", label: "Dashboard" },

  // ---- Admin ----
  { pattern: "/admin/professionals", parentPath: "/admin", label: "Dashboard" },
  { pattern: "/admin/professionals/new", parentPath: "/admin/professionals", label: "Professionals" },
  { pattern: "/admin/professionals/:id/edit", parentPath: "/admin/professionals", label: "Professionals" },
  { pattern: "/admin/review", parentPath: "/admin", label: "Dashboard" },
  { pattern: "/admin/review/:id", parentPath: "/admin/review", label: "Review queue" },
  { pattern: "/admin/bookings", parentPath: "/admin", label: "Dashboard" },
  { pattern: "/admin/responsiveness", parentPath: "/admin", label: "Dashboard" },
];

/** The back target for a path, or null when the page has no back control. */
export function backTargetFor(pathname: string): BackTarget | null {
  const rule = BACK_ROUTES.find((r) => matchPath({ path: r.pattern, end: true }, pathname));
  return rule ? { parentPath: rule.parentPath, label: rule.label } : null;
}

// ---- The previous in-app location (kept by NavigationTracker) ----

let currentKey: string | null = null;
let currentPathname: string | null = null;
let previousPathname: string | null = null;

/**
 * Called on every location change. Only a normal forward navigation (PUSH) makes the page we came from
 * known; a redirect (REPLACE) keeps it, and after the browser's back or forward button (POP), or on the
 * first page load, it is unknown.
 */
export function recordNavigation(key: string, pathname: string, type: "PUSH" | "REPLACE" | "POP") {
  if (key === currentKey) return;
  if (type === "PUSH") previousPathname = currentPathname;
  else if (type === "POP") previousPathname = null;
  currentKey = key;
  currentPathname = pathname;
}

/**
 * The click handler of a back control. When the page we came from is exactly the parent, it goes back
 * through history, so the parent's filters, page number and scroll position are kept; otherwise it opens
 * the parent. Either way it never leaves the app or lands on a sign-in page or a just-finished form.
 * Clicks with a modifier key (open in a new tab) are left to the browser.
 */
export function useBackNavigation(target: BackTarget | null) {
  const navigate = useNavigate();
  return useCallback(
    (event: MouseEvent<HTMLElement>) => {
      if (!target || event.defaultPrevented || event.button !== 0) return;
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      const parentPathname = target.parentPath.split(/[?#]/)[0];
      if (previousPathname !== null && previousPathname === parentPathname) {
        navigate(-1);
      } else {
        navigate(target.parentPath);
      }
    },
    [navigate, target],
  );
}
