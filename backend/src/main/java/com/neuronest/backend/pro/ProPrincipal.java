package com.neuronest.backend.pro;

// The authenticated caller on /api/pro/**: only the Clerk user id from a verified token. The
// professional is always looked up from this, never taken from the request.
public record ProPrincipal(String clerkUserId) {
}
