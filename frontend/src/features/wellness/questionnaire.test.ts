/// <reference types="node" />
// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { OPTIONAL, Q, bySegment } from "./copy.ts";
import {
  EMPTY_ANSWERS,
  MOVEMENT_OPTIONS,
  SLEEP_OPTIONS,
  errorsByAnswer,
  isComplete,
  missingInStep,
  stepForFieldErrors,
  toRequest,
  withWhatIf,
  type Answers,
} from "./questionnaire.ts";

const REQUIRED_KEYS = [
  "segment",
  "age",
  "sleepDuration",
  "qualityOfSleep",
  "stressLevel",
  "physicalActivityMinutes",
  "pressure",
  "satisfaction",
  "financialStress",
];

function complete(overrides: Partial<Answers> = {}): Answers {
  return {
    ...EMPTY_ANSWERS,
    segment: "working",
    age: 34,
    movementMinutes: 45,
    sleepHours: 6.5,
    rested: 6,
    pressure: 4,
    satisfaction: 2,
    financialStress: 3,
    stress: 7,
    ...overrides,
  };
}

describe("chip values", () => {
  test("sleep chips map to the representative hours", () => {
    assert.deepEqual(
      SLEEP_OPTIONS.map((o) => o.value),
      [4.5, 5.5, 6.5, 7.5, 8.5],
    );
  });

  test("movement chips map to minutes per day within the API range", () => {
    for (const o of MOVEMENT_OPTIONS) {
      assert.ok(Number.isInteger(o.value) && o.value >= 0 && o.value <= 600, o.id);
    }
    assert.deepEqual(
      MOVEMENT_OPTIONS.map((o) => o.value),
      [10, 25, 45, 75],
    );
  });
});

describe("toRequest", () => {
  test("required answers become the request with numbers, not strings", () => {
    const req = toRequest(complete());
    assert.deepEqual(Object.keys(req).sort(), [...REQUIRED_KEYS].sort());
    assert.equal(req.sleepDuration, 6.5);
    assert.equal(req.physicalActivityMinutes, 45);
    assert.equal(req.qualityOfSleep, 6);
    assert.equal(req.stressLevel, 7);
    for (const [key, value] of Object.entries(req)) {
      if (key !== "segment") assert.equal(typeof value, "number", key);
    }
  });

  test("skipped optionals are omitted entirely, never sent as null", () => {
    const json = JSON.stringify(toRequest(complete()));
    assert.ok(!json.includes("null"));
    for (const key of ["workStudyHours", "dietaryHabits", "familyHistory", "bmiCategory", "heartRate", "dailySteps"]) {
      assert.ok(!json.includes(key), key);
    }
  });

  test("answered optionals are included; prefer-not-to-say family history is omitted", () => {
    const req = toRequest(
      complete({ workStudyHours: 9, dietaryHabits: "moderate", familyHistory: "yes", heartRate: 65, dailySteps: 7500 }),
    );
    assert.equal(req.workStudyHours, 9);
    assert.equal(req.dietaryHabits, "moderate");
    assert.equal(req.familyHistory, true);
    assert.equal(req.heartRate, 65);
    assert.equal(req.dailySteps, 7500);
    assert.ok(!("bmiCategory" in req));
    assert.equal(toRequest(complete({ familyHistory: "no" })).familyHistory, false);
    assert.ok(!("familyHistory" in toRequest(complete({ familyHistory: "prefer_not" }))));
  });

  test("only fields the backend DTO accepts are ever sent", () => {
    const allowed = new Set([
      ...REQUIRED_KEYS,
      "dietaryHabits",
      "workStudyHours",
      "familyHistory",
      "bmiCategory",
      "heartRate",
      "dailySteps",
    ]);
    const req = toRequest(
      complete({
        workStudyHours: 3,
        dietaryHabits: "healthy",
        familyHistory: "no",
        bmiCategory: "normal",
        heartRate: 55,
        dailySteps: 2500,
      }),
    );
    for (const key of Object.keys(req)) assert.ok(allowed.has(key), key);
  });

  test("incomplete answers are refused", () => {
    assert.throws(() => toRequest({ ...complete(), stress: null }));
  });

  test("what-if only changes sleep, stress and movement", () => {
    const base = toRequest(complete({ workStudyHours: 9 }));
    const changed = withWhatIf(base, { sleepDuration: 8.5, stressLevel: 3, physicalActivityMinutes: 75 });
    assert.deepEqual({ ...changed, sleepDuration: 6.5, stressLevel: 7, physicalActivityMinutes: 45 }, base);
  });
});

describe("validation", () => {
  test("each step reports its own missing answers", () => {
    assert.deepEqual(missingInStep(EMPTY_ANSWERS, 0), ["segment", "age", "movementMinutes"]);
    assert.deepEqual(missingInStep(EMPTY_ANSWERS, 1), ["sleepHours", "rested"]);
    assert.deepEqual(missingInStep(EMPTY_ANSWERS, 2), ["pressure", "satisfaction", "financialStress"]);
    assert.deepEqual(missingInStep(EMPTY_ANSWERS, 3), ["stress"]);
    assert.ok(isComplete(complete()));
  });

  test("out-of-range and fractional values count as missing", () => {
    assert.deepEqual(missingInStep(complete({ age: 12 }), 0), ["age"]);
    assert.deepEqual(missingInStep(complete({ age: 81 }), 0), ["age"]);
    assert.deepEqual(missingInStep(complete({ pressure: 6 }), 2), ["pressure"]);
    assert.deepEqual(missingInStep(complete({ stress: 7.5 }), 3), ["stress"]);
  });

  test("400 field errors map to answers and to the first step to revisit", () => {
    const errors = [
      { field: "stressLevel", message: "Input should be less than or equal to 10" },
      { field: "physicalActivityMinutes", message: "Field required" },
      { field: "somethingNew", message: "ignored" },
    ];
    assert.equal(stepForFieldErrors(errors), 0);
    assert.deepEqual(errorsByAnswer(errors), {
      stress: "Input should be less than or equal to 10",
      movementMinutes: "Field required",
    });
    assert.equal(stepForFieldErrors([{ field: "dailySteps", message: "x" }]), 3);
    assert.equal(stepForFieldErrors([{ field: null, message: "x" }]), null);
  });
});

describe("segment-specific wording", () => {
  test("load, satisfaction and money questions adapt to study, work or both", () => {
    assert.match(Q.pressure.label("student"), /study/i);
    assert.match(Q.pressure.label("working"), /workload/i);
    assert.match(Q.pressure.label("both"), /study and work/i);
    assert.match(Q.satisfaction.label("student"), /studies/i);
    assert.match(Q.satisfaction.label("working"), /work/i);
    assert.match(Q.money.hint("student"), /fees/i);
    assert.match(Q.money.hint("working"), /EMIs/);
    assert.match(OPTIONAL.workStudyHours.label("working"), /work/i);
    assert.notEqual(Q.pressure.hint("student"), Q.pressure.hint("working"));
  });

  test("before a segment is chosen, the neutral wording is used", () => {
    assert.equal(bySegment(null, { student: "s", working: "w", both: "b" }), "b");
  });
});
