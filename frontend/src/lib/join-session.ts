import { formatIstDateTime } from "./ist-time.ts";

/**
 * "Join session": GET /api/bookings/{id}/join (the person) and GET /api/pro/bookings/{id}/join (the
 * professional) give out the video link only from a few minutes before the session until it ends. Outside that
 * window they answer 409 {"code": "TOO_EARLY" | "ENDED", "opensAt", "endsAt"} without the link.
 */
export interface JoinLink {
  meetingUri: string;
}

interface JoinRefusal {
  code?: unknown;
  opensAt?: unknown;
  error?: unknown;
}

/** Shown while a confirmed video session's link is still being made; the page checks again every 20 seconds. */
export const LINK_SETTING_UP_MESSAGE =
  "We're setting up your video link. This usually takes a minute. You'll also get it by email.";
export const LINK_POLL_MS = 20_000;

/** A confirmed video session whose link is not there yet. */
export function awaitingVideoLink(b: {
  status: string;
  meetingMethod: string | null;
  preferredMode: string | null;
  preferredMethod: string | null;
  videoLinkReady: boolean;
}): boolean {
  return b.status === "CONFIRMED" && isVideoSession(b) && !b.videoLinkReady;
}

/** A video session: by video call, chosen when booking or set as the meeting method. */
export function isVideoSession(b: {
  meetingMethod: string | null;
  preferredMode: string | null;
  preferredMethod: string | null;
}): boolean {
  return b.meetingMethod === "VIDEO" || (b.meetingMethod === null && b.preferredMode === "ONLINE" && b.preferredMethod === "VIDEO");
}

/** What to tell the person when the link was not given: "Opens at …", "This session has ended", or the server's words. */
export function joinRefusalMessage(status: number, body: unknown, fallback: string): string {
  const refusal = (body ?? {}) as JoinRefusal;
  if (status === 409 && refusal.code === "TOO_EARLY" && typeof refusal.opensAt === "string") {
    return `Opens at ${formatIstDateTime(refusal.opensAt)}.`;
  }
  if (status === 409 && refusal.code === "ENDED") return "This session has ended.";
  return typeof refusal.error === "string" && refusal.error ? refusal.error : fallback;
}
