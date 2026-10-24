import { apiFetch } from "./api";

/**
 * Client for the signed-in user's reviews of their own sessions (GET /api/bookings/reviews and
 * POST/PUT/DELETE /api/bookings/{bookingId}/review). Every rule is checked by the server; its messages are
 * written for people and shown as they come.
 */

export const MAX_COMMENT_LENGTH = 500;

export interface MyReview {
  rating: number;
  comment: string | null;
  /** HIDDEN: hidden by NeuroNest, and then it can no longer be edited or deleted. */
  status: "VISIBLE" | "HIDDEN";
  createdAt: string;
  updatedAt: string;
}

/** One of the user's confirmed appointments whose session has ended. */
export interface ReviewableBooking {
  bookingId: string;
  professional: { id: string; fullName: string; credentialType: string; city: string | null };
  /** ISO date-time with the +05:30 offset. */
  scheduledTime: string;
  canReview: boolean;
  review: MyReview | null;
}

export class ReviewApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T | null> {
  let response: Response;
  try {
    response = await apiFetch(path, init);
  } catch {
    throw new ReviewApiError(0, "We could not reach NeuroNest. Please check your connection and try again.");
  }
  if (!response.ok) {
    let message = "Something went wrong. Please try again.";
    try {
      const body = await response.json();
      if (body && typeof body.error === "string") message = body.error;
    } catch {
      // Not JSON; keep the generic message.
    }
    throw new ReviewApiError(response.status, message);
  }
  return response.status === 204 ? null : ((await response.json()) as T);
}

export async function getReviewableBookings(): Promise<ReviewableBooking[]> {
  const items = await request<ReviewableBooking[]>("/bookings/reviews");
  return Array.isArray(items) ? items : [];
}

function send(bookingId: string, method: "POST" | "PUT", rating: number, comment: string) {
  return request<MyReview>(`/bookings/${encodeURIComponent(bookingId)}/review`, {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rating, comment: comment.trim() || null }),
  }) as Promise<MyReview>;
}

export function createReview(bookingId: string, rating: number, comment: string) {
  return send(bookingId, "POST", rating, comment);
}

export function updateReview(bookingId: string, rating: number, comment: string) {
  return send(bookingId, "PUT", rating, comment);
}

export async function deleteReview(bookingId: string): Promise<void> {
  await request(`/bookings/${encodeURIComponent(bookingId)}/review`, { method: "DELETE" });
}
