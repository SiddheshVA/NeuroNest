/// <reference types="node" />
// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { CONFIDENCE_REASON_FALLBACK, HISTORY, INSIGHT, RESULT, TREND } from "./copy.ts";
import { WellnessApiError, errorKind, friendlyMessage, parseErrorBody } from "./errors.ts";
import {
  MOVEMENT_METER,
  SLEEP_METER,
  actionLinks,
  bandChange,
  bandCopy,
  bandIndex,
  daysAgo,
  driverValueText,
  driverViews,
  meterPercent,
  skippedOptional,
  weeklyMinutes,
  friendlyConfidenceReasons,
  saveState,
  showCrisisResources,
  sleepFlag,
  sleepView,
} from "./results.ts";
import type { CheckinResponse, SleepResult } from "./types.ts";

const GUIDANCE = {
  messages: [{ code: "sleep_short", text: "You're sleeping about 6.5 hours." }],
  meets_sleep_guideline: false,
  meets_activity_guideline: true,
  weekly_activity_minutes: 210,
  source: "General adult guidelines.",
};

function sleep(overrides: Partial<SleepResult> = {}): SleepResult {
  return {
    model: "sleep_pattern_core",
    in_range: true,
    reason: null,
    pattern_code: "insomnia_like",
    display_text: "Your answers resemble a difficulty-sleeping (insomnia-like) pattern.",
    see_doctor: true,
    probability: 0.57,
    probabilities: { none: 0.24, insomnia_like: 0.57, apnea_like: 0.19 },
    probabilities_calibrated: false,
    confidence: "medium",
    confidence_reasons: [],
    drivers: [],
    imputed: [],
    out_of_range: [],
    guidance: GUIDANCE,
    ...overrides,
  };
}

const OUT_OF_RANGE = sleep({
  model: null,
  in_range: false,
  reason: "The sleep-pattern model was built from adults aged 27-59 sleeping 5.8-8.5 hours.",
  pattern_code: null,
  display_text: null,
  see_doctor: null,
  probability: null,
  probabilities: null,
  confidence: null,
  out_of_range: ["age"],
});

describe("bands", () => {
  test("each band has a place on the scale and its own words", () => {
    assert.equal(bandIndex("Low"), 0);
    assert.equal(bandIndex("Moderate"), 1);
    assert.equal(bandIndex("High"), 2);
    for (const band of ["Low", "Moderate", "High"]) {
      const copy = bandCopy(band);
      assert.ok(copy && copy.label === band && copy.text.length > 20, band);
    }
  });

  test("an unknown band highlights nothing and has no copy", () => {
    assert.equal(bandIndex("Severe"), -1);
    assert.equal(bandCopy("Severe"), null);
  });

  test("crisis resources appear only with a High band", () => {
    assert.equal(showCrisisResources("High"), true);
    assert.equal(showCrisisResources("Moderate"), false);
    assert.equal(showCrisisResources("Low"), false);
  });
});

describe("confidence reasons", () => {
  test("technical reasons become plain language, deduplicated", () => {
    const out = friendlyConfidenceReasons([
      "model quality limits confidence to 'medium'",
      "optional input(s) not provided: ['family_history']",
      "optional input(s) not provided: ['dietary_habits']",
      "segment 'both' uses the mood_student model; confidence capped at 'medium'",
    ]);
    assert.equal(out.length, 3);
    assert.ok(out.every((t) => !t.includes("[") && !t.includes("_")));
  });

  test("unknown reasons are not shown raw; no reasons means complete answers", () => {
    assert.deepEqual(friendlyConfidenceReasons(["weird_internal_flag: x"]), [CONFIDENCE_REASON_FALLBACK]);
    assert.deepEqual(friendlyConfidenceReasons([]), [RESULT.confidence.fullAnswers]);
  });
});

describe("drivers", () => {
  test("top three with friendly labels and arrows; unknown names fall back gracefully", () => {
    const views = driverViews(
      [
        { feature: "financial_stress", label: "Financial stress", value: 4, direction: "increases", effect: "", impact: 0.8 },
        { feature: "sleep_duration", label: "Sleep duration", value: "7-8", direction: "decreases", effect: "", impact: -0.4 },
        { feature: "brand_new_feature", label: "Brand new", value: 1, direction: "increases", effect: "", impact: 0.2 },
        { feature: "age", label: "Age", value: 30, direction: "increases", effect: "", impact: 0.1 },
      ],
      "mood",
    );
    assert.equal(views.length, 3);
    assert.deepEqual(
      views.map((v) => [v.label, v.direction]),
      [
        ["Money worries", "up"],
        ["Sleep length", "down"],
        ["Brand new", "up"],
      ],
    );
    assert.equal(views[0].phrase, RESULT.direction.mood.increases);
    assert.equal(driverViews([{ feature: "no_label_x", label: "", value: null, direction: "decreases", effect: "", impact: 0 }], "sleep")[0].label, "no label x");
  });
});

describe("sleep card", () => {
  test("in range with a class: the display text, and the doctor line only when the action says so", () => {
    const withDoctor = sleepView(sleep(), ["KEEP_TRACKING", "RELAXATION_SLEEP", "SEE_DOCTOR"]);
    assert.equal(withDoctor.mode, "pattern");
    assert.equal(withDoctor.showSeeDoctor, true);
    const withoutDoctor = sleepView(sleep({ pattern_code: "none", see_doctor: false }), ["KEEP_TRACKING"]);
    assert.equal(withoutDoctor.showSeeDoctor, false);
  });

  test("out of range: a guidance-only result (a normal outcome) with the reason and guidance", () => {
    const view = sleepView(OUT_OF_RANGE, ["ASSESSMENT_PHQ9", "FIND_PROFESSIONAL"]);
    assert.equal(view.mode, "guidance");
    assert.equal(view.showSeeDoctor, false);
    assert.ok(view.mode === "guidance" && view.reason?.includes("27-59"));
    assert.equal(view.guidance.messages[0].code, "sleep_short");
  });

  test("history sleep flag", () => {
    assert.equal(sleepFlag(OUT_OF_RANGE, []), "guidance");
    assert.equal(sleepFlag(sleep(), ["SEE_DOCTOR"]), "concern");
    assert.equal(sleepFlag(sleep({ pattern_code: "none", see_doctor: false }), []), "ok");
  });
});

describe("suggested actions", () => {
  test("known codes link to existing routes in the backend's order; unknown codes are ignored", () => {
    const links = actionLinks(["ASSESSMENT_PHQ9", "TOTALLY_NEW_CODE", "FIND_PROFESSIONAL", "RELAXATION_SLEEP", "SEE_DOCTOR", "ASSESSMENT_PHQ9"]);
    assert.deepEqual(
      links.map((l) => [l.code, l.href]),
      [
        ["ASSESSMENT_PHQ9", "/assessment/phq9"],
        ["FIND_PROFESSIONAL", "/professionals"],
        ["RELAXATION_SLEEP", "/relaxation/sleep"],
        ["SEE_DOCTOR", null],
      ],
    );
    assert.deepEqual(actionLinks(undefined), []);
  });

  test("every backend code is mapped", () => {
    const codes = ["ASSESSMENT_PHQ9", "ASSESSMENT_PSS10", "FIND_PROFESSIONAL", "RELAXATION", "RELAXATION_SLEEP", "SEE_DOCTOR", "KEEP_TRACKING"];
    assert.equal(actionLinks(codes).length, codes.length);
  });
});

describe("save state (call to action)", () => {
  const base: Pick<CheckinResponse, "saved" | "id"> = { saved: false, id: null };
  test("guests get the sign-in call to action; stored check-ins show Saved", () => {
    assert.equal(saveState(base), "anonymous");
    assert.equal(saveState({ saved: true, id: "5f0c2a8e-3b1d-4c7a-9e2f-8d6b1a4c3e70" }), "saved");
    assert.equal(saveState({ saved: true, id: null }), "anonymous");
  });
});

describe("errors", () => {
  test("statuses map to friendly kinds; 503 keeps a calm message", () => {
    assert.equal(errorKind(new WellnessApiError(503, "x", "WELLNESS_UNAVAILABLE")), "unavailable");
    assert.equal(errorKind(new WellnessApiError(0, "x")), "network");
    assert.equal(errorKind(new WellnessApiError(400, "x")), "invalid");
    assert.equal(errorKind(new WellnessApiError(401, "x")), "unauthorized");
    assert.equal(errorKind(new Error("x")), "generic");
    assert.match(friendlyMessage(new WellnessApiError(503, "Traceback")), /resting/);
  });

  test("error bodies are parsed defensively", () => {
    const e = parseErrorBody(400, {
      error: "Some check-in answers are invalid: pressure: ...",
      code: "WELLNESS_INVALID_INPUT",
      fields: [{ field: "pressure", message: "Input should be less than or equal to 5" }, "junk", { field: 3 }],
    });
    assert.equal(e.code, "WELLNESS_INVALID_INPUT");
    assert.equal(e.fields.length, 2);
    assert.deepEqual(e.fields[0], { field: "pressure", message: "Input should be less than or equal to 5" });
    assert.equal(e.fields[1].field, null);
    assert.equal(parseErrorBody(500, "not json").fields.length, 0);
  });
});

describe("design helpers", () => {
  test("driver values in words, from what the API returned", () => {
    assert.equal(driverValueText("pressure", 4), "You rated it 4 out of 5");
    assert.equal(driverValueText("stress_level", 7), "You rated it 7 out of 10");
    assert.equal(driverValueText("sleep_duration", "7-8"), "About 7-8 h a night");
    assert.equal(driverValueText("physical_activity_minutes", 45), "About 45 min a day");
    assert.equal(driverValueText("family_history", true), "You answered: Yes");
    assert.equal(driverValueText("dietary_habits", "Moderate"), "You answered: A mix");
    assert.equal(driverValueText("brand_new_feature", 3), null);
    assert.equal(driverValueText("pressure", null), null);
    const [view] = driverViews([{ feature: "pressure", label: "", value: 4, direction: "increases", effect: "", impact: 1 }], "mood");
    assert.equal(view.valueText, "You rated it 4 out of 5");
  });

  test("skipped optional answers are detected from imputed inputs or the confidence reason", () => {
    assert.equal(skippedOptional({ imputed: ["family_history"], confidence_reasons: [] }), true);
    assert.equal(skippedOptional({ imputed: [], confidence_reasons: ["optional input(s) not provided: ['x']"] }), true);
    assert.equal(skippedOptional({ imputed: [], confidence_reasons: ["model quality limits confidence to 'medium'"] }), false);
  });

  test("what-if band comparison", () => {
    assert.equal(bandChange("High", "Low"), "lower");
    assert.equal(bandChange("Low", "Moderate"), "higher");
    assert.equal(bandChange("Moderate", "Moderate"), "same");
    assert.equal(bandChange("Moderate", "Unknown"), null);
  });

  test("meters use the two documented guidelines and clamp values to the scale", () => {
    assert.deepEqual(SLEEP_METER, { min: 4, max: 10, zoneFrom: 7, zoneTo: 9 });
    assert.equal(MOVEMENT_METER.zoneFrom, 150);
    assert.equal(weeklyMinutes(45), 315);
    assert.equal(meterPercent(7, SLEEP_METER), 50);
    assert.equal(meterPercent(3, SLEEP_METER), 0);
    assert.equal(meterPercent(12, SLEEP_METER), 100);
    assert.equal(meterPercent(525, MOVEMENT_METER), 100);
  });

  test("relative dates count calendar days", () => {
    const now = new Date(2026, 10, 3, 9, 0);
    assert.equal(daysAgo(new Date(2026, 10, 3, 1, 0).toISOString(), now), 0);
    assert.equal(daysAgo(new Date(2026, 10, 2, 23, 0).toISOString(), now), 1);
    assert.equal(daysAgo(new Date(2026, 9, 20, 12, 0).toISOString(), now), 14);
    assert.equal(daysAgo("not a date", now), 0);
    assert.equal(HISTORY.relative(0), "Today");
    assert.equal(HISTORY.relative(1), "Yesterday");
    assert.equal(HISTORY.relative(5), "5 days ago");
    assert.equal(HISTORY.relative(14), "2 weeks ago");
  });

  test("live sleep feedback uses the agreed copy for each chip", () => {
    assert.match(INSIGHT.sleep(4.5), /^Well under the 7\+ hours/);
    assert.match(INSIGHT.sleep(5.5), /^Below the 7\+ hours/);
    assert.match(INSIGHT.sleep(6.5), /^Close, but a little under/);
    assert.match(INSIGHT.sleep(7.5), /^Right in the range/);
    assert.match(INSIGHT.sleep(8.5), /^Plenty of time asleep/);
    assert.equal(INSIGHT.sleepTitle("6–7 h", INSIGHT.restedWord(2)), "6–7 h, feeling exhausted");
    assert.equal(INSIGHT.sleepTitle("6–7 h", null), "6–7 h");
  });

  test("trend summary text", () => {
    assert.equal(TREND.summary(["Low"]), "Your latest snapshot: Low.");
    assert.equal(TREND.summary(["Low", "High"]), "Your last 2 snapshots, oldest to newest: Low, High.");
  });
});
