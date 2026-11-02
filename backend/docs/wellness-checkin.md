# Wellness check-in (backend)

Package `com.neuronest.backend.wellness`. The backend calls the internal ML service
(`ml-service/`, contract in `ml-service/docs/wellness_models.md`), returns its result to the app and, for
signed-in users, stores it. Results are estimates for self-reflection, **never a diagnosis**.

## Configuration

| Property | Env var | Default | Notes |
|---|---|---|---|
| `wellness.ml.base-url` | `WELLNESS_ML_BASE_URL` | `http://127.0.0.1:8001` | ML service address |
| `wellness.ml.internal-key` | `WELLNESS_INTERNAL_KEY` | empty | Shared secret sent as `X-Internal-Key`; must equal the ML service's value. Never commit it. |
| `wellness.ml.connect-timeout` | | `PT2S` | |
| `wellness.ml.read-timeout` | | `PT3S` | |

The app starts without the ML service and without the key. A check-in then answers 503
`WELLNESS_UNAVAILABLE` and nothing is saved.

## Endpoints

| Method | Path | Auth | Result |
|---|---|---|---|
| POST | `/api/wellness/checkin[?save=false]` | optional | 200. Guests: result only (`saved: false`, nothing stored). Signed in: stored, with `id`, unless `save=false`. |
| GET | `/api/wellness/checkins?page=0&size=10` | required | Own check-ins, newest first, `{items, page, size, totalElements, totalPages}`; size 1–50 |
| GET | `/api/wellness/checkins/latest` | required | 200 with the latest, or 204 if none |
| DELETE | `/api/wellness/checkins/{id}` | required | 204. Another user's id gets the same 404 as a missing one. |
| DELETE | `/api/wellness/checkins` | required | 204. Deletes all of the user's own check-ins. |

Ownership is enforced in `WellnessCheckinService`: every query is scoped to the signed-in user.

**`save` query parameter** (optional, default `true`). With `save=false` the answers are scored and the normal
result is returned, but nothing is written, even for a signed-in user: the response has `saved: false`,
`id: null` and `createdAt: null`, exactly like a guest's. The frontend's what-if previews use it so slider moves
never create history. Guests behave the same either way. Only `true` or `false` (any case) are accepted; any
other value (`1`, `yes`, empty, ...) is a 400 `WELLNESS_INVALID_INPUT` with `fields: [{"field": "save", ...}]`,
returned before the ML service is called.

**Request** (camelCase; any other field is rejected with 400, so for example `gender` or `city` is never sent on):

```json
{"segment": "working", "age": 34, "sleepDuration": 6.5, "qualityOfSleep": 6, "stressLevel": 7,
 "physicalActivityMinutes": 30, "pressure": 4, "satisfaction": 2, "financialStress": 3,
 "dietaryHabits": "moderate", "workStudyHours": 9}
```

Optional: `dietaryHabits`, `workStudyHours`, `familyHistory`, `bmiCategory`, `heartRate`, `dailySteps`.
`sleepDuration` is hours per night and `physicalActivityMinutes` is minutes per day. Ranges are validated by the
ML service; its 422 comes back as a 400 with field messages in these camelCase names.

**Full response** (signed-in user). The envelope is camelCase. `result` is the ML service's response
unchanged, so its keys are snake_case; it is also exactly what is stored.

```json
{
  "id": "5f0c2a8e-3b1d-4c7a-9e2f-8d6b1a4c3e70",
  "saved": true,
  "createdAt": "2026-11-02T11:40:12.345Z",
  "result": {
    "request_id": "9c1e4b7a2f3d4e5a8b6c7d8e9f0a1b2c",
    "mood": {
      "model": "mood_working", "segment": "working", "risk_band": "Moderate", "probability": 0.2041,
      "confidence": "low",
      "confidence_reasons": ["model quality limits confidence to 'medium'",
                             "optional input(s) not provided: ['family_history']",
                             "input(s) not used by this model: ['age']"],
      "drivers": [
        {"feature": "pressure", "label": "Academic / work pressure", "value": 4, "direction": "increases",
         "effect": "increases estimated risk", "impact": 0.5537},
        {"feature": "satisfaction", "label": "Study / job satisfaction", "value": 2, "direction": "increases",
         "effect": "increases estimated risk", "impact": 0.448},
        {"feature": "work_study_hours", "label": "Daily work/study hours", "value": 9.0, "direction": "increases",
         "effect": "increases estimated risk", "impact": 0.4445}
      ],
      "imputed": ["family_history"], "ignored_inputs": ["age"], "out_of_range": []
    },
    "sleep": {
      "model": "sleep_pattern_core", "in_range": true, "reason": null,
      "pattern_code": "insomnia_like",
      "display_text": "Your answers resemble a difficulty-sleeping (insomnia-like) pattern. If poor sleep persists for several weeks, consider talking to a doctor.",
      "see_doctor": true, "probability": 0.5717,
      "probabilities": {"none": 0.241, "insomnia_like": 0.5717, "apnea_like": 0.1873},
      "probabilities_calibrated": false, "confidence": "medium",
      "confidence_reasons": ["model quality limits confidence to 'medium'",
                             "booster inputs (bmi_category, heart_rate, daily_steps) not provided; core model used"],
      "drivers": [
        {"feature": "sleep_duration", "label": "Sleep duration (h)", "value": 6.5, "direction": "increases",
         "effect": "increases likelihood of insomnia-like pattern", "impact": 0.6758},
        {"feature": "age", "label": "Age", "value": 34, "direction": "decreases",
         "effect": "decreases likelihood of insomnia-like pattern", "impact": -0.4644},
        {"feature": "stress_level", "label": "Stress level", "value": 7, "direction": "increases",
         "effect": "increases likelihood of insomnia-like pattern", "impact": 0.3351}
      ],
      "imputed": [], "out_of_range": [],
      "guidance": {
        "messages": [
          {"code": "sleep_short", "text": "You're sleeping about 6.5 hours. Adults are generally advised to get at least 7 hours a night; a consistent bedtime and less screen time before bed can help."},
          {"code": "activity_ok", "text": "About 210 minutes of activity a week meets the 150-minute adult guideline."}
        ],
        "meets_sleep_guideline": false, "meets_activity_guideline": true, "weekly_activity_minutes": 210.0,
        "source": "General adult guidelines: 7+ hours of sleep per night; 150 minutes of moderate activity per week."
      }
    },
    "model_versions": {
      "mood_student": {"version": "20261102T110227Z-aaa8cd8ec846", "trained_at_utc": "2026-11-02T11:02:27+00:00", "algorithm": "logreg", "sha256": "aaa8cd8ec8463efee080bd05bca70e6011f41d552c4e5bd0dec8e404e80f41ac"},
      "mood_working": {"version": "20261102T110241Z-752258127ccf", "trained_at_utc": "2026-11-02T11:02:41+00:00", "algorithm": "logreg", "sha256": "752258127ccf01940e4cca368934bc526f339ba1d7ede72e0a326ae29a1c327a"},
      "sleep_pattern_core": {"version": "20261102T110314Z-755cb248a8d9", "trained_at_utc": "2026-11-02T11:03:14+00:00", "algorithm": "hist_gb", "sha256": "755cb248a8d92aab8200e6431e1fc8b4d23a1d614c2505c2c00bc45653623d09"},
      "sleep_pattern_extended": {"version": "20261102T110349Z-c66940acddd9", "trained_at_utc": "2026-11-02T11:03:49+00:00", "algorithm": "hist_gb", "sha256": "c66940acddd954fca9b75dd06654011cd40d96969a97b56eb66613d0101f49f0"}
    },
    "disclaimer": "This is an estimate based on a few self-reported answers, not a diagnosis. If you are struggling, please talk to a professional."
  },
  "suggestedActions": ["RELAXATION", "ASSESSMENT_PSS10", "RELAXATION_SLEEP", "SEE_DOCTOR"]
}
```

A guest gets the same body with `"id": null, "saved": false, "createdAt": null`. History items are
`{id, createdAt, segment, result, suggestedActions}`.

**Errors** (the app's `{"error": ...}` shape, plus `code` and `fields` where the frontend needs them):

| Status | `code` | When |
|---|---|---|
| 400 | `WELLNESS_INVALID_INPUT` | Unknown field, wrong type, fraction in a whole-number field, or the ML service rejected a value. Includes `fields: [{field, message}]`. |
| 401 | | History endpoints without a signed-in user |
| 404 | | Check-in not found (or someone else's) |
| 503 | `WELLNESS_UNAVAILABLE` | ML service unreachable, timed out, 5xx, refused the key, or answered something unusable. Nothing is saved. |

## Suggested actions

These are stable codes from `WellnessSuggestedActions`. The frontend maps them to text and links. They are
suggestions, never diagnoses, and are recomputed from the stored result on every read.

| Source | Codes |
|---|---|
| mood `High` | `ASSESSMENT_PHQ9`, `FIND_PROFESSIONAL` |
| mood `Moderate` | `RELAXATION`, `ASSESSMENT_PSS10` |
| mood `Low` | `KEEP_TRACKING` |
| sleep `insomnia_like` | `RELAXATION_SLEEP` |
| sleep `see_doctor: true` (in-range sleep only) | `SEE_DOCTOR`. Taken from the ML service's flag (single source of truth), not hard-coded per class; today it is true for `insomnia_like` and `apnea_like`. |

## Data, privacy and retention

- Table `wellness_checkin` (migration **V21**) holds the answers sent to the ML service, its full result and
  model versions, plus band, confidence and sleep columns for listing.
- **Retention:** kept until the user deletes them (one, or all). They are removed automatically with the
  user's account (`ON DELETE CASCADE`). There is no auto-delete job.
- **Visibility:** owner only. No professional, admin screen or assessment-sharing feature reads this table.
  Other modules may use only `WellnessContextProvider.latestSummary(userId)`: band, date and a sleep flag for
  that user's own experience.
- **Logs:** never answers or results. The ML client logs only a request id, the status and the latency.

## Developing against a Neon branch (not the shared database)

Starting the app runs Flyway, so running this branch against the shared database would apply V21 there.
Use a Neon branch instead: a copy of the shared database, so the hand-made baseline tables and V2–V20 are
already in place.

1. Neon Console → the NeuroNest project → **Branches** → **New branch**.
   - Parent: the branch whose compute host is the shared one (currently `ep-sparkling-salad-…`).
   - Data: up to **now (head)**.
   - Name: `wellness-checkin-dev`.
   - Create, with a compute.
2. On the new branch, click **Connect**. Use the same database and role, and copy the **host**, which is a new
   `ep-…` endpoint.
3. In the PowerShell session that runs the backend, override the database. Environment variables win over
   `backend/.env.properties`, so leave that file pointing at the shared database and do not edit it.
   ```powershell
   $env:DB_URL      = "jdbc:postgresql://<branch-host>/<database>?sslmode=require"
   $env:DB_USER     = "<role>"
   $env:DB_PASSWORD = "<password shown for the branch>"
   echo $env:DB_URL   # must NOT contain ep-sparkling-salad; that is the shared database
   cd backend; .\mvnw spring-boot:run
   ```
   Flyway applies V21 to the branch only ("Successfully applied 1 migration … v21").
4. Every new terminal needs step 3 again. A terminal without the overrides talks to the shared database.

Command-line alternative, after `npx neonctl auth`:
`npx neonctl branches create --name wellness-checkin-dev --parent <parent-branch>`, then
`npx neonctl connection-string wellness-checkin-dev`.

Tests never need a branch: they use an embedded PostgreSQL and cannot reach the real database
(`TestDatabaseIsolation`).

## Applying V21 to the shared database at merge time

Status checked read-only on 2026-11-02: the shared database is at V20 (all 20 successful) and has no
`wellness_checkin` table. No branch or remote has any other V21.

1. **Tell the team first:** "V21 (`wellness_checkin`) is taken. Merge main before adding a migration; the next
   free number is V22."
2. **Check that nobody has claimed V21 meanwhile.** Run a read-only check on the shared database:
   `SELECT max(version::int) FROM flyway_schema_history WHERE success;` must be 20, and
   `SELECT to_regclass('public.wellness_checkin');` must be null. If a V21 already exists, renumber this
   migration before merging and never apply two different V21s.
3. **Merge the PR into main.** One person starts the backend from main against the shared database, once.
   Flyway applies V21; check the log for `v21` and that the app starts (it validates the schema).
4. **Verify:** `flyway_schema_history` has version 21 with `success = true`.
5. **Teammates' branches:**
   - A branch without V21 (latest V20) keeps working: Flyway ignores "future" migrations by default.
   - A branch that adds its own V21, or adds V22 without V21, fails Flyway validation at startup
     ("applied migration not resolved locally" or a checksum/description mismatch). The fix is to merge main
     and renumber their migration to V22 or later.
   - Never edit V21 once applied, never set `ignore-migration-patterns` to hide this, and never run
     `flyway repair` or `clean` on the shared database.
6. **Configure:** set `WELLNESS_INTERNAL_KEY` (and `WELLNESS_ML_BASE_URL` if the service is not local) for the
   backend, to the same key as the ML service's.
7. **Delete the Neon branch** `wellness-checkin-dev`.
