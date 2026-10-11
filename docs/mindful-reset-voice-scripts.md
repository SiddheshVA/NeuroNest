# Mindful Reset — voice scripts

Step text for the six **Mindful Reset** activities in the Relaxation Hub, extracted for
conversion to voice audio with a TTS tool.

## Source and how this was extracted

- **All step text and timing** comes from one file: `frontend/src/data/mindful-reset.ts`.
  Each activity is an entry in `mindfulResetActivities` (line 378) whose `steps` points to a
  step array in the same file. The file describes itself as *"Temporary, local frontend data"*
  that a future content API may replace.
- **What the app shows:** the player (`frontend/src/components/relaxation/mindful-reset/mindful-reset-activity-player.tsx`)
  shows one step at a time: the step's `instruction`, and below it the step's `detail`
  when there is one. Each step stays on screen for its `seconds`, and the steps run once,
  in array order (`use-mindful-reset-engine.ts`: "a linear (non-repeating) sequence of
  steps"). There are no repeat cycles.
- **Templates combined:** some steps are built in code, not written out:
  - `tensionCycleSteps(region, label, …)` (line 126) produces four steps per body part
    (tense, hold, release, relax), inserting the part's `label` into
    `` `Gently tense your ${label}` `` and `` `Let your ${label} relax completely` ``.
  - `bodyScanRegionSteps` and `mindfulBodyAwarenessRegionSteps` hold the instruction for each
    body part; the activity's step array adds the timing.

  Below, these are expanded into the exact text the app displays, in display order.
- **No other step text exists:** the animations in `components/relaxation/mindful-reset/animations/`
  are purely visual (no text), and the detail page only shows short region labels
  (e.g. "Hands → Arms").
- **Breathing counts:** none of the six activities has breath counts or a breath pace in the
  code. The only breathing step ("Follow your breath, in and out, at its own pace") is
  deliberately un-paced. No counts were added to the voice scripts.

### How the voice-script pauses were calculated

Each step's on-screen time comes from its `seconds` value in the code. In each voice script,
the pause after a step = step `seconds` − estimated speaking time, so the audio follows the
same timing as the app.

- Speaking time is estimated at **2.5 words per second** (about 150 words per minute, a
  typical TTS rate), rounded up to whole seconds.
- Minimum pause is 1s. Where speech fills the whole step (only the 6-second "tense" steps), the
  narration may run up to about a second over.
- The final pause in each script keeps the closing step's full length. Trim it if the audio
  should end on the last word.
- If your TTS voice is slower or faster, adjust the pauses proportionally.

**Changes made in the voice scripts** (and nothing else):
- Digits are spelled out for speech ("5" → "five").
- The dash in "Squeeze softly — there's no need to strain" becomes a comma.
- The only added joining word is "Now," at the start of each new body part in the two
  tense-and-release activities, so the listener hears the change of body part.

---

## 1. Progressive Muscle Relaxation

- **Source:** `frontend/src/data/mindful-reset.ts`
  - activity entry `id: "progressive-muscle-relaxation"` in `mindfulResetActivities` (line 381)
  - steps from `progressiveMuscleRelaxationSteps` (line 165), which calls
    `tensionCycleSteps(...)` (line 126) for six body parts with `{ relax: 28 }`
- **Listed duration on the card:** 5 min (`durationMinutes: 5`)

### Original steps

The detail text, where there is one, is the second line shown under the instruction.

1. Get comfortable and settle in
   *Detail:* Close your eyes if that feels right.
2. Gently tense your hands
   *Detail:* Squeeze softly — there's no need to strain.
3. Hold the tension
4. Release, all at once
5. Let your hands relax completely
   *Detail:* Notice the difference between tension and ease.
6. Gently tense your arms
   *Detail:* Squeeze softly — there's no need to strain.
7. Hold the tension
8. Release, all at once
9. Let your arms relax completely
   *Detail:* Notice the difference between tension and ease.
10. Gently tense your shoulders
    *Detail:* Squeeze softly — there's no need to strain.
11. Hold the tension
12. Release, all at once
13. Let your shoulders relax completely
    *Detail:* Notice the difference between tension and ease.
14. Gently tense your face
    *Detail:* Squeeze softly — there's no need to strain.
15. Hold the tension
16. Release, all at once
17. Let your face relax completely
    *Detail:* Notice the difference between tension and ease.
18. Gently tense your legs
    *Detail:* Squeeze softly — there's no need to strain.
19. Hold the tension
20. Release, all at once
21. Let your legs relax completely
    *Detail:* Notice the difference between tension and ease.
22. Gently tense your whole body
    *Detail:* Squeeze softly — there's no need to strain.
23. Hold the tension
24. Release, all at once
25. Let your whole body relax completely
    *Detail:* Notice the difference between tension and ease.
26. Rest in this feeling of ease

### Timing found in the code

- Seconds per step:
  - Step 1 (intro): 15s.
  - Each body part, in order hands, arms, shoulders, face, legs, whole body:
    tense 6s, hold 5s, release 5s, relax 28s (44s per body part).
  - Step 26 (closing): 20s.
- Total: 15 + 6 × 44 + 20 = **299s (4:59)**.
- Repeat cycles: none (one pass). Breath counts: none found.

### Voice script

Get comfortable and settle in. Close your eyes if that feels right. [pause 10s]

Gently tense your hands. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your hands relax completely. Notice the difference between tension and ease. [pause 23s]

Now, gently tense your arms. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your arms relax completely. Notice the difference between tension and ease. [pause 23s]

Now, gently tense your shoulders. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your shoulders relax completely. Notice the difference between tension and ease. [pause 23s]

Now, gently tense your face. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your face relax completely. Notice the difference between tension and ease. [pause 23s]

Now, gently tense your legs. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your legs relax completely. Notice the difference between tension and ease. [pause 23s]

Now, gently tense your whole body. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your whole body relax completely. Notice the difference between tension and ease. [pause 22s]

Rest in this feeling of ease. [pause 17s]

---

## 2. Body Scan

- **Source:** `frontend/src/data/mindful-reset.ts`
  - activity entry `id: "body-scan"` in `mindfulResetActivities` (line 393)
  - steps from `bodyScanSteps` (line 204): an intro, then one step per entry of
    `bodyScanRegionSteps` (line 189), then a closing step
- **Listed duration on the card:** 8 min (`durationMinutes: 8`)

### Original steps

1. Settle into a comfortable position
   *Detail:* Let your body soften with each breath.
2. Bring your attention to the top of your head
3. Notice your forehead, eyes, and jaw
4. Soften your neck
5. Let your shoulders drop away from your ears
6. Feel your chest rise and fall with each breath
7. Notice your arms, from shoulder to fingertips
8. Bring awareness to your belly, soft and easy
9. Notice your legs, resting and supported
10. Finish at your feet, grounded and still
11. Notice your whole body, resting and at ease

### Timing found in the code

- Seconds per step: step 1 (intro) 20s; steps 2–10 (body parts) 48s each; step 11 (closing) 25s.
- Total: 20 + 9 × 48 + 25 = **477s (7:57)**.
- Repeat cycles: none. Breath counts: none found. Steps 1 and 6 mention the breath but give
  no count or pace.

### Voice script

Settle into a comfortable position. Let your body soften with each breath. [pause 15s]

Bring your attention to the top of your head. [pause 44s]

Notice your forehead, eyes, and jaw. [pause 45s]

Soften your neck. [pause 46s]

Let your shoulders drop away from your ears. [pause 44s]

Feel your chest rise and fall with each breath. [pause 44s]

Notice your arms, from shoulder to fingertips. [pause 45s]

Bring awareness to your belly, soft and easy. [pause 44s]

Notice your legs, resting and supported. [pause 45s]

Finish at your feet, grounded and still. [pause 45s]

Notice your whole body, resting and at ease. [pause 21s]

---

## 3. Somatic Grounding

- **Source:** `frontend/src/data/mindful-reset.ts`
  - activity entry `id: "somatic-grounding"` in `mindfulResetActivities` (line 405)
  - steps from `somaticGroundingSteps` (line 231)
- **Listed duration on the card:** 5 min (`durationMinutes: 5`)

### Original steps

1. Sit or stand comfortably, feet resting on the ground
2. Notice your feet pressing into the ground
   *Detail:* Feel the support beneath you.
3. Notice your hands, resting or touching something nearby
4. Follow your breath, in and out, at its own pace
5. Notice the sounds and space around you
6. Carry this steadiness with you

### Timing found in the code

- Seconds per step: step 1 (intro) 15s; steps 2–5 65s each; step 6 (closing) 25s.
- Total: 15 + 4 × 65 + 25 = **300s (5:00)**.
- Repeat cycles: none. Breath counts: none found. Step 4 says the breath goes "at its own pace",
  so it is intentionally not counted.

### Voice script

Sit or stand comfortably, feet resting on the ground. [pause 11s]

Notice your feet pressing into the ground. Feel the support beneath you. [pause 60s]

Notice your hands, resting or touching something nearby. [pause 61s]

Follow your breath, in and out, at its own pace. [pause 61s]

Notice the sounds and space around you. [pause 62s]

Carry this steadiness with you. [pause 23s]

---

## 4. Tension & Release

- **Source:** `frontend/src/data/mindful-reset.ts`
  - activity entry `id: "tension-and-release"` in `mindfulResetActivities` (line 417)
  - steps from `tensionAndReleaseSteps` (line 271), which calls `tensionCycleSteps(...)`
    (line 126) for three body parts with `{ relax: 30 }`
- **Listed duration on the card:** 3 min (`durationMinutes: 3`)

### Original steps

1. Find a comfortable position
2. Gently tense your hands
   *Detail:* Squeeze softly — there's no need to strain.
3. Hold the tension
4. Release, all at once
5. Let your hands relax completely
   *Detail:* Notice the difference between tension and ease.
6. Gently tense your shoulders
   *Detail:* Squeeze softly — there's no need to strain.
7. Hold the tension
8. Release, all at once
9. Let your shoulders relax completely
   *Detail:* Notice the difference between tension and ease.
10. Gently tense your jaw
    *Detail:* Squeeze softly — there's no need to strain.
11. Hold the tension
12. Release, all at once
13. Let your jaw relax completely
    *Detail:* Notice the difference between tension and ease.
14. Notice the ease you've created

### Timing found in the code

- Seconds per step:
  - Step 1 (intro): 20s.
  - Each body part, in order hands, shoulders, jaw: tense 6s, hold 5s, release 5s,
    relax 30s (46s per body part).
  - Step 14 (closing): 22s.
- Total: 20 + 3 × 46 + 22 = **180s (3:00)**.
- Repeat cycles: none. Breath counts: none found.

### Voice script

Find a comfortable position. [pause 18s]

Gently tense your hands. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your hands relax completely. Notice the difference between tension and ease. [pause 25s]

Now, gently tense your shoulders. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your shoulders relax completely. Notice the difference between tension and ease. [pause 25s]

Now, gently tense your jaw. Squeeze softly, there's no need to strain. [pause 1s]
Hold the tension. [pause 3s]
Release, all at once. [pause 3s]
Let your jaw relax completely. Notice the difference between tension and ease. [pause 25s]

Notice the ease you've created. [pause 20s]

---

## 5. Mindful Body Awareness

- **Source:** `frontend/src/data/mindful-reset.ts`
  - activity entry `id: "mindful-body-awareness"` in `mindfulResetActivities` (line 429)
  - steps from `mindfulBodyAwarenessSteps` (line 304): an intro, then one step per entry of
    `mindfulBodyAwarenessRegionSteps` (line 291), then a closing step
- **Listed duration on the card:** 5 min (`durationMinutes: 5`)

### Original steps

1. Settle in and let your body be just as it is
2. Notice the sensations in your feet
3. Bring awareness to your legs
4. Notice your belly rising and falling
5. Feel your chest, open and easy
6. Notice your hands, resting still
7. Bring awareness to your shoulders
8. Notice your face, soft and unguarded
9. Notice your whole body, just as it is

### Timing found in the code

- Seconds per step: step 1 (intro) 20s; steps 2–8 (body parts) 36s each; step 9 (closing) 24s.
- Total: 20 + 7 × 36 + 24 = **296s (4:56)**.
- Repeat cycles: none. Breath counts: none found.

### Voice script

Settle in and let your body be just as it is. [pause 15s]

Notice the sensations in your feet. [pause 33s]

Bring awareness to your legs. [pause 34s]

Notice your belly rising and falling. [pause 33s]

Feel your chest, open and easy. [pause 33s]

Notice your hands, resting still. [pause 34s]

Bring awareness to your shoulders. [pause 34s]

Notice your face, soft and unguarded. [pause 33s]

Notice your whole body, just as it is. [pause 20s]

---

## 6. 5-4-3-2-1 Grounding

- **Source:** `frontend/src/data/mindful-reset.ts`
  - activity entry `id: "5-4-3-2-1-grounding"` in `mindfulResetActivities` (line 441)
  - steps from `fiveSensesSteps` (line 330)
- **Listed duration on the card:** 3 min (`durationMinutes: 3`)

### Original steps

1. Take a slow breath and look around you
2. Notice 5 things you can see
3. Notice 4 things you can feel
4. Notice 3 things you can hear
5. Notice 2 things you can smell
6. Notice 1 thing you can taste
7. Notice how present you feel right now

### Timing found in the code

- Seconds per step: step 1 (intro) 15s, see 40s, feel 35s, hear 30s, smell 25s, taste 20s,
  closing 15s.
- Total: **180s (3:00)**.
- Repeat cycles: none. Breath counts: none found. Step 1's "slow breath" has no count.
- The code also gives each sense step a `senseCount` (5, 4, 3, 2, 1). The animation uses it
  to light one dot per item; it is not extra text and not a per-item timer.

### Voice script

Take a slow breath and look around you. [pause 11s]

Notice five things you can see. [pause 37s]

Notice four things you can feel. [pause 32s]

Notice three things you can hear. [pause 27s]

Notice two things you can smell. [pause 22s]

Notice one thing you can taste. [pause 17s]

Notice how present you feel right now. [pause 12s]

---

## Content check

All six activities are complete in the code. None has placeholder, missing or "TODO" step text,
and every step has an instruction and a duration. Points worth knowing before recording:

- **No breath counts anywhere.** If the audio should count breaths ("Breathe in… two…
  three… four"), that content would have to be written new. It is not in the app.
- **Repeated text:** the tense-and-release activities (1 and 4) reuse the same detail lines for
  every body part, because they come from one template. This is how the app shows them, not a
  copy error.
- **Small differences from the card durations:** Progressive Muscle Relaxation runs 4:59 against
  "5 min", Body Scan 7:57 against "8 min", and Mindful Body Awareness 4:56 against "5 min".
  The other three match exactly.
- **Temporary data:** `mindful-reset.ts` is marked as temporary local data that a future
  content API may replace. Re-check this extract if that happens.

## Summary

| Activity | Number of steps | Estimated audio length |
| --- | --- | --- |
| Progressive Muscle Relaxation | 26 | ~5:00 (4:59) |
| Body Scan | 11 | ~8:00 (7:57) |
| Somatic Grounding | 6 | 5:00 |
| Tension & Release | 14 | 3:00 |
| Mindful Body Awareness | 9 | ~5:00 (4:56) |
| 5-4-3-2-1 Grounding | 7 | 3:00 |

These lengths match the app's own step timings, assuming the pauses above are kept. The total
for all six is about 29 minutes.
