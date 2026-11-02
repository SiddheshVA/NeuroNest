// Errors from /api/wellness and how the screens should treat them. Pure (no React, no fetch).

import { ERRORS } from "./copy.ts";
import type { FieldError } from "./types.ts";

/** status 0 = the request never got an answer (offline, server down, CORS). */
export class WellnessApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly fields: FieldError[];

  constructor(status: number, message: string, code: string | null = null, fields: FieldError[] = []) {
    super(message);
    this.name = "WellnessApiError";
    this.status = status;
    this.code = code;
    this.fields = fields;
  }
}

export type ErrorKind = "unavailable" | "invalid" | "unauthorized" | "network" | "notFound" | "generic";

export function errorKind(error: unknown): ErrorKind {
  if (!(error instanceof WellnessApiError)) return "generic";
  if (error.status === 0) return "network";
  if (error.status === 503 || error.code === "WELLNESS_UNAVAILABLE") return "unavailable";
  if (error.status === 400) return "invalid";
  if (error.status === 401) return "unauthorized";
  if (error.status === 404) return "notFound";
  return "generic";
}

/** The friendly banner text for an error (400 field messages are shown inline instead). */
export function friendlyMessage(error: unknown): string {
  switch (errorKind(error)) {
    case "unavailable":
      return ERRORS.unavailable;
    case "network":
      return ERRORS.network;
    case "invalid":
      return ERRORS.invalid;
    default:
      return ERRORS.generic;
  }
}

/** Reads the backend's {"error", "code", "fields"} body defensively. */
export function parseErrorBody(status: number, body: unknown): WellnessApiError {
  const b = (body && typeof body === "object" ? body : {}) as { error?: unknown; code?: unknown; fields?: unknown };
  const message = typeof b.error === "string" ? b.error : ERRORS.generic;
  const code = typeof b.code === "string" ? b.code : null;
  const fields: FieldError[] = Array.isArray(b.fields)
    ? b.fields
        .filter((f): f is { field?: unknown; message?: unknown } => !!f && typeof f === "object")
        .map((f) => ({
          field: typeof f.field === "string" ? f.field : null,
          message: typeof f.message === "string" ? f.message : ERRORS.invalid,
        }))
    : [];
  return new WellnessApiError(status, message, code, fields);
}
