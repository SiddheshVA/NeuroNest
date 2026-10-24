/// <reference types="node" />
// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, test } from "node:test";

import {
  BAND_LEVELS,
  compareWithPrevious,
  isModerateOrAbove,
  levelFor,
  professionalReason,
  SUGGESTIONS,
  suggestionsFor,
} from "./insights.ts";
import { SCORE_RANGES } from "./ranges.ts";
import { ASSESSMENTS } from "./registry.ts";
import type { AssessmentType } from "./types";

const TYPES = Object.keys(ASSESSMENTS) as AssessmentType[];

describe("score direction", () => {
  test("matches the scoring code: WHO-5 and SCI higher is better, the rest lower", () => {
    const higher = TYPES.filter((type) => ASSESSMENTS[type].higherIsBetter).sort();
    assert.deepEqual(higher, ["sci", "who5"]);
  });
});

describe("compareWithPrevious", () => {
  test("higher-is-better (WHO-5): going up is the better direction", () => {
    assert.equal(
      compareWithPrevious("who5", 15, 12),
      "Your score is 3 points higher than last time (12 → 15). For WHO-5, that is the better direction.",
    );
  });

  test("higher-is-better (WHO-5): going down says higher scores are better", () => {
    assert.equal(
      compareWithPrevious("who5", 10, 12),
      "Your score is 2 points lower than last time (12 → 10). For WHO-5, higher scores are the better direction.",
    );
  });

  test("lower-is-better (GAD-7): going down is the better direction", () => {
    assert.equal(
      compareWithPrevious("gad7", 8, 12),
      "Your score is 4 points lower than last time (12 → 8). For GAD-7, that is the better direction.",
    );
  });

  test("lower-is-better (GAD-7): going up says lower scores are better", () => {
    assert.equal(
      compareWithPrevious("gad7", 15, 12),
      "Your score is 3 points higher than last time (12 → 15). For GAD-7, lower scores are the better direction.",
    );
  });

  test("every lower-is-better instrument treats a drop as better", () => {
    for (const type of ["phq9", "gad7", "pss10", "k10"] as const) {
      assert.match(compareWithPrevious(type, 10, 12), /that is the better direction\.$/, type);
      assert.match(compareWithPrevious(type, 12, 10), /lower scores are the better direction\.$/, type);
    }
  });

  test("first attempt", () => {
    assert.equal(
      compareWithPrevious("phq9", 7, null),
      "This is your first PHQ-9. Retake it in a couple of weeks to see how it changes.",
    );
  });

  test("same score", () => {
    assert.equal(compareWithPrevious("gad7", 7, 7), "Your score is the same as last time (7).");
  });

  test("one point is singular", () => {
    assert.match(compareWithPrevious("gad7", 8, 7), /^Your score is 1 point higher/);
  });

  test("numeric only (high-risk result) never says which direction is better", () => {
    const sentence = compareWithPrevious("gad7", 15, 12, true);
    assert.equal(sentence, "Your score is 3 points higher than last time (12 → 15).");
    assert.doesNotMatch(compareWithPrevious("who5", 15, 12, true), /better/);
  });
});

describe("band-to-level mapping", () => {
  test("agreed mapping", () => {
    assert.deepEqual(BAND_LEVELS, {
      who5: { high: "good", moderate: "mild", low: "moderate" },
      gad7: { minimal: "good", mild: "mild", moderate: "moderate", severe: "high" },
      phq9: { minimal: "good", mild: "mild", moderate: "moderate", "moderately-severe": "high", severe: "high" },
      pss10: { low: "good", moderate: "mild", high: "high" },
      k10: { "likely-well": "good", mild: "mild", moderate: "moderate", severe: "high" },
      sci: { steady: "good", difficulty: "moderate" },
    });
  });

  test("every band the scoring can produce has a level, and nothing else does", () => {
    for (const type of TYPES) {
      const bands = SCORE_RANGES[type].map((range) => range.levelKey).sort();
      assert.deepEqual(Object.keys(BAND_LEVELS[type]).sort(), bands, type);
      for (const band of bands) assert.notEqual(levelFor(type, band), null, `${type} ${band}`);
    }
  });

  test("unknown bands map to no level", () => {
    assert.equal(levelFor("gad7", "good"), null);
    assert.equal(isModerateOrAbove(null), false);
  });

  test("moderate or above", () => {
    assert.deepEqual(
      (["good", "mild", "moderate", "high"] as const).map(isModerateOrAbove),
      [false, false, true, true],
    );
  });
});

describe("professionalReason", () => {
  test("names the instrument and band", () => {
    assert.equal(
      professionalReason("k10", "moderate", "Moderate distress", null),
      "Your K10 result is in the moderate distress range, so talking it through with a professional could help.",
    );
  });

  test("mentions two moderate results in a row", () => {
    assert.equal(
      professionalReason("gad7", "moderate", "Moderate", "moderate"),
      "Your last two GAD-7 results were both in the moderate range, so talking it through with a professional could help.",
    );
    assert.match(professionalReason("gad7", "high", "Severe", "moderate"), /both in the moderate range or above,/);
  });

  test("a previous result below moderate is not mentioned", () => {
    assert.match(professionalReason("gad7", "moderate", "Moderate", "mild"), /^Your GAD-7 result is in/);
  });

  test("numeric only (high-risk result) drops the encouragement", () => {
    assert.equal(professionalReason("phq9", "high", "Severe", null, true), "Your PHQ-9 result is in the severe range.");
  });
});

describe("suggestions", () => {
  test("every level an instrument can reach has 2-3 suggestions", () => {
    for (const type of TYPES) {
      for (const level of new Set(Object.values(BAND_LEVELS[type]))) {
        const count = suggestionsFor(type, level).length;
        assert.ok(count >= 2 && count <= 3, `${type} ${level}: ${count}`);
      }
    }
  });

  test("every suggestion points at an item that exists in the app's data", () => {
    const read = (file: string) => readFileSync(new URL(`../../data/${file}`, import.meta.url), "utf8");
    const sources: Record<string, string> = {
      breathing: read("breathing-exercises.ts"),
      meditation: read("meditations.ts"),
      "mindful-reset": read("mindful-reset.ts"),
      sleep: read("sleep-support.ts"),
    };
    const articles = read("wellness-resources.ts");
    for (const levels of Object.values(SUGGESTIONS)) {
      for (const refs of Object.values(levels)) {
        for (const ref of refs ?? []) {
          if (ref.kind === "article") {
            assert.ok(articles.includes(`slug: "${ref.slug}"`), `article ${ref.slug}`);
          } else {
            const [kind, id] = ref.key.split(":");
            const field = kind === "meditation" ? "slug" : "id";
            assert.ok(sources[kind]?.includes(`${field}: "${id}"`), `relaxation ${ref.key}`);
          }
        }
      }
    }
  });
});
