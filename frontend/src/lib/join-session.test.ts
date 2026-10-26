/// <reference types="node" />
// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { awaitingVideoLink, isVideoSession, joinRefusalMessage } from "./join-session.ts";

describe("join session", () => {
  test("too early shows when it opens, never a link", () => {
    const message = joinRefusalMessage(409, { code: "TOO_EARLY", opensAt: "2026-10-29T19:40:00+05:30", endsAt: "x" }, "fallback");
    assert.match(message, /^Opens at Thu, 29 Oct, 7:40 pm IST\.$/i);
  });

  test("ended, other refusals and failures", () => {
    assert.equal(joinRefusalMessage(409, { code: "ENDED" }, "fallback"), "This session has ended.");
    assert.equal(joinRefusalMessage(409, { code: "NOT_AVAILABLE", error: "Not ready yet." }, "fallback"), "Not ready yet.");
    assert.equal(joinRefusalMessage(0, null, "fallback"), "fallback");
  });

  test("which bookings are video sessions", () => {
    assert.equal(isVideoSession({ meetingMethod: "VIDEO", preferredMode: "ONLINE", preferredMethod: "VIDEO" }), true);
    assert.equal(isVideoSession({ meetingMethod: null, preferredMode: "ONLINE", preferredMethod: "VIDEO" }), true);
    assert.equal(isVideoSession({ meetingMethod: null, preferredMode: "ONLINE", preferredMethod: "PHONE" }), false);
    assert.equal(isVideoSession({ meetingMethod: "PHONE", preferredMode: "ONLINE", preferredMethod: "VIDEO" }), false);
    assert.equal(isVideoSession({ meetingMethod: null, preferredMode: "IN_PERSON", preferredMethod: null }), false);
  });

  test("a confirmed video session without its link is still being set up", () => {
    const video = { meetingMethod: null, preferredMode: "ONLINE", preferredMethod: "VIDEO" };
    assert.equal(awaitingVideoLink({ ...video, status: "CONFIRMED", videoLinkReady: false }), true);
    assert.equal(awaitingVideoLink({ ...video, status: "CONFIRMED", videoLinkReady: true }), false);
    assert.equal(awaitingVideoLink({ ...video, status: "AWAITING_PAYMENT", videoLinkReady: false }), false);
    assert.equal(awaitingVideoLink({ meetingMethod: "PHONE", preferredMode: "ONLINE", preferredMethod: "PHONE",
      status: "CONFIRMED", videoLinkReady: false }), false);
  });
});
