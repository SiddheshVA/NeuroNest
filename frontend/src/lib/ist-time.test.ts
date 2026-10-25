/// <reference types="node" />
// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { formatMeetingLength, formatMeetingTime } from "./ist-time.ts";

describe("formatMeetingTime", () => {
  test("a 2-session booking shows the whole meeting and never the break after it", () => {
    // Blocked until 10:15 pm, but the meeting ends at 9:45 pm.
    const text = formatMeetingTime("2026-10-29T19:45:00+05:30", "2026-10-29T21:45:00+05:30", 2);
    assert.match(text, /^Thu, 29 Oct, 7:45 pm to 9:45 pm IST \(2 sessions, 2 hours\)$/i);
    assert.doesNotMatch(text, /10:15/);
  });

  test("one session, and an older booking without an end", () => {
    assert.match(formatMeetingTime("2026-10-29T19:45:00+05:30", "2026-10-29T20:45:00+05:30", 1), /\(1 session, 1 hour\)$/);
    assert.match(formatMeetingTime("2026-10-29T19:45:00+05:30", null, 1), /^Thu, 29 Oct, 7:45 pm IST$/i);
  });

  test("lengths", () => {
    assert.equal(formatMeetingLength(30), "30 minutes");
    assert.equal(formatMeetingLength(90), "1 hour 30 minutes");
    assert.equal(formatMeetingLength(240), "4 hours");
  });
});
