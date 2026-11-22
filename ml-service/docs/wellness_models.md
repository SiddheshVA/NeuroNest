# Wellness check-in models

Three models behind the NeuroNest "Wellness Check-in". They give estimates for
self-reflection, **never a diagnosis**.

| Model | Predicts | Algorithm | Training rows | Max confidence |
|---|---|---|---|---|
| `mood_student` | mood risk band (Low / Moderate / High) | logistic regression + isotonic calibration | 27,825 students | high |
| `mood_working` | mood risk band (Low / Moderate / High) | logistic regression + sigmoid calibration, **no age** | 2,054 working professionals | **medium** |
| `sleep_pattern_core` | none / insomnia_like / apnea_like | HistGradientBoosting (uncalibrated, see below) | 132 (deduplicated) | medium |
| `sleep_pattern_extended` | same, plus optional booster inputs | HistGradientBoosting (uncalibrated) | 132 (deduplicated) | medium |

## Layout

```
app/wellness/
  config.py          paths, seeds, forbidden columns, segment routing, confidence caps
  display_config.py  ALL user-facing text: sleep label codes / display text / see_doctor, guidance messages
  schema.py          input specs: allowed ranges, required vs optional
  data.py            find CSVs by their columns, clean them (every drop is logged), map features
  evaluation.py      model comparison, CV, calibration check, risk bands
  explain.py         top-3 drivers (standardized coefficients / SHAP)
  guidance.py        sleep_guidance(): rule-based adult-guideline messages (no ML)
  train.py           CLI: trains and saves everything
  predictor.py       predict_mood(segment, inputs), predict_sleep(inputs)
app/api/             FastAPI layer (settings, schemas, middleware, errors, routes, service): no model logic
app/main.py          create_app() + `app` for uvicorn
models/wellness/     <name>.joblib + <name>.model_card.json
tests/wellness/      model / predictor tests
tests/api/           API tests (FastAPI TestClient)
```

## Retrain

```bash
cd ml-service
py -3.13 -m venv .venv && .venv/Scripts/python -m pip install -r requirements.txt   # once
# put the three CSVs in data/raw/ (gitignored; any filenames, found by their columns)
.venv/Scripts/python -m app.wellness.train -v     # about 2 minutes, writes models/wellness/
.venv/Scripts/python -m pytest              # model + API tests
```

Training is deterministic (seed 42). Retraining on the same files gives identical cards apart from `trained_at_utc`.

## Using the predictor

```python
from app.wellness.predictor import predict_mood, predict_sleep
from app.wellness.guidance import sleep_guidance

predict_mood("student", {"age": 21, "pressure": 4, "satisfaction": 2, "sleep_duration": "5-6",
                         "financial_stress": 3})                 # optional: dietary_habits, work_study_hours, family_history
predict_sleep({"age": 40, "sleep_duration": 6.5, "quality_of_sleep": 6, "stress_level": 6,
               "physical_activity_minutes": 45})                 # optional boosters: bmi_category, heart_rate, daily_steps
sleep_guidance(sleep_hours=6.5, quality=6, activity_minutes=45)   # activity in minutes per day
```

* Invalid input raises `SchemaError` (unknown field, missing required field, wrong type, out of range).
* Models load once and stay cached. Each call runs in memory: about 2 ms for mood, about 7 ms for sleep.

### Mood segments

The check-in asks which segment the user is in:

| segment | model | confidence cap |
|---|---|---|
| `student` | mood_student | none (high possible) |
| `working` | mood_working | medium (stored in the model) |
| `both` | mood_student | medium (`MOOD_SEGMENT_ROUTING` in `config.py`) |

For `working`, `age` is optional. If sent, it is validated, then ignored, and listed in `ignored_inputs`.

### Sleep

* **Range gate.** If `age` or `sleep_duration` is outside the training range (27–59 years, 5.8–8.5 h), the predictor returns `in_range: false`, a short `reason`, and **no pattern** (`pattern_code`, `probabilities` and `confidence` are null). Show the `guidance` instead.
* **In range.** The response has `pattern_code` (`none` / `insomnia_like` / `apnea_like`), `display_text`, `see_doctor`, and probabilities keyed by code. Raw dataset class names never leave the service.
* `guidance` (from `sleep_guidance`) comes with every sleep response:
  * Sleep under 7 h: "short" message; 7–9 h: "OK" message; over 9 h: "long" message.
  * Quality at or below 4/10: an extra message.
  * Activity is converted to minutes per week and compared with 150.
* **Boosters.** With no boosters, the core model runs with no penalty. If any booster is given, the extended model runs and missing boosters are imputed (−1 confidence level).

### Confidence and drivers

* **Confidence** starts at the model's ceiling, which comes from CV quality and the caps above. It drops one level per imputed input the model uses (at most two levels) and one level if any input is outside the training range. A segment cap then applies on top. `confidence_reasons` explains each step.
* **Missing optional inputs** are filled with the training median and listed in `imputed`. They are never shown as drivers.
* **Drivers:** the three largest contributions, each marked as increasing or decreasing the estimate. For logistic regression this is standardized coefficient × standardized input; for trees it is SHAP.

## API (internal, called by Spring Boot only)

### Run locally (PowerShell, from `ml-service\`)

```powershell
.\.venv\Scripts\Activate.ps1
$env:WELLNESS_INTERNAL_KEY = python -c "import secrets; print(secrets.token_urlsafe(32))"
uvicorn app.main:app --host 127.0.0.1 --port 8001 --no-access-log
# local experiments only, auth off and docs at http://127.0.0.1:8001/docs:
#   $env:WELLNESS_DEV_MODE = "1"; Remove-Item Env:WELLNESS_INTERNAL_KEY
```

| Variable | Meaning |
|---|---|
| `WELLNESS_INTERNAL_KEY` | Shared secret sent in `X-Internal-Key` (at least 16 characters). **Startup fails if unset**, unless dev mode is on. |
| `WELLNESS_DEV_MODE` | `1` = local development: allows a missing key (auth off) and serves `/docs`. Default `0`. |
| `WELLNESS_MODEL_DIR` | Optional; defaults to `models/wellness`. |

See `.env.example`. The service does not read `.env` files itself, so set the variables in the shell or service manager. There is no CORS. Models load and warm up once at startup; after that a check-in takes about 15–20 ms. The `--no-access-log` flag avoids duplicate logs: the service writes its own access log, containing only request id, method, path, status and latency. Request bodies and predictions are never logged.

### Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | none | `status`, `loaded`, model versions (no user data) |
| POST | `/v1/wellness/checkin` | `X-Internal-Key` | mood + sleep result for one check-in |

Optional header `X-Request-ID` (letters, digits, `._-`, up to 64 characters) is echoed back. Otherwise the service generates one.

**Request fields.** The request schema is generated from `app/wellness/schema.py`. Unknown fields → 422 `validation_error`. Sensitive fields (suicidal thoughts, gender, city, degree, name, id, profession, CGPA, occupation) → 422 `forbidden_field`.

| Field | Type | Required |
|---|---|---|
| `segment` | `student` \| `working` \| `both` | yes |
| `age` | int 16–80 | yes (ignored by the working mood model) |
| `sleep_duration` | number, **hours** 2–14 | yes (the mood model gets the band: <5, 5–6.99 → "5-6", 7–8, >8) |
| `quality_of_sleep`, `stress_level` | int 1–10 | yes |
| `physical_activity_minutes` | int 0–600, per day | yes |
| `pressure`, `satisfaction`, `financial_stress` | int 1–5 | yes |
| `dietary_habits` | `unhealthy` \| `moderate` \| `healthy` | no |
| `work_study_hours` | number 0–16 | no |
| `family_history` | bool | no |
| `bmi_category` | `normal` \| `overweight` \| `obese` | no (sleep booster) |
| `heart_rate` | int 30–200 | no (sleep booster) |
| `daily_steps` | int 0–50000 | no (sleep booster) |

Numbers are strict: `"4"` and `4.5` are rejected for integer fields.

**Example**

```http
POST /v1/wellness/checkin
X-Internal-Key: <secret>
X-Request-ID: demo-001
Content-Type: application/json

{"segment": "working", "age": 34, "sleep_duration": 6.5, "quality_of_sleep": 6, "stress_level": 7,
 "physical_activity_minutes": 30, "pressure": 4, "satisfaction": 2, "financial_stress": 3,
 "dietary_habits": "moderate", "work_study_hours": 9}
```

```json
{
  "request_id": "demo-001",
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
    "mood_working": {"version": "20261102T110241Z-752258127ccf", "trained_at_utc": "2026-11-02T11:02:41+00:00",
                     "algorithm": "logreg", "sha256": "7522…"},
    "...": "one entry per model"
  },
  "disclaimer": "This is an estimate based on a few self-reported answers, not a diagnosis. If you are struggling, please talk to a professional."
}
```

When sleep inputs are out of range, `sleep` has `in_range: false`, a `reason`, null pattern, probabilities and confidence, empty `drivers`, and still includes `guidance`.

**Errors** always look like this:

```json
{"error": {"code": "validation_error", "message": "Invalid request: pressure: Input should be less than or equal to 5",
           "request_id": "…", "fields": [{"field": "pressure", "message": "Input should be less than or equal to 5",
                                          "type": "less_than_equal"}]}}
```

Codes: `unauthorized` (401), `validation_error` / `forbidden_field` (422), `not_found` (404), `method_not_allowed` (405), `internal_error` (500). Responses never contain stack traces.

## Metrics (5-fold stratified CV; working: 5×5 repeated; sleep: 5×5 grouped)

| Model | Primary metric | Baseline | Accuracy (baseline) | Brier raw → calibrated |
|---|---|---|---|---|
| mood_student | ROC-AUC 0.872 ± 0.005 | 0.500 | 0.799 (0.585) | 0.142 → 0.142 (ECE 0.006 → 0.003) |
| mood_working | ROC-AUC 0.817 ± 0.023 | 0.500 | 0.904 (0.901) | 0.076 → 0.076 (ECE 0.014 → 0.013) |
| sleep core | macro-F1 0.649 ± 0.070 | 0.237 | 0.709 (0.557) | 0.457 → 0.483, so calibration rejected |
| sleep extended | macro-F1 0.663 ± 0.068 | 0.237 | 0.722 (0.557) | 0.433 → 0.470, so calibration rejected |

Model choice: the simplest model within 0.01 of the best wins. Calibration is kept only if it does not worsen nested-CV Brier, so sleep probabilities are uncalibrated (`probabilities_calibrated: false`). With 90% negatives, mood_working's accuracy barely beats the baseline; AUC is the meaningful metric for it. Per-candidate metrics and reliability tables are in each model card.

**Why mood_working has no age.** In dataset 2, age alone gives AUC of about 0.87: the positive rate falls from about 67% at ages 18–19 to about 0% at 50+, which looks like a synthetic-data artefact. With age, AUC was 0.972, but nearly every older worker came out Low. The card's `excluded_features` keeps the with-age result for reference.

**Risk bands (mood).** Cut points sit at 3× above and below each segment's base odds, because the base rates differ (58% for students, 10% for working professionals).

| | Low if p < | High if p ≥ | Out-of-fold share Low / Mod / High | Observed positive rate per band |
|---|---|---|---|---|
| student | 0.320 | 0.809 | 29% / 33% / 38% | 16% / 59% / 91% |
| working | 0.035 | 0.248 | 35% / 55% / 10% | 1% / 10% / 39% |

## Data rules

* Never used as features: suicidal-thoughts question, Gender, City, Degree, Name, id, Profession, CGPA (also Occupation and Person ID). A test checks this by scrambling those columns and confirming the training data does not change.
* Depression files: placeholder values (`Others`, `?`, blank, NaN) and 0 on 1–5 scales are dropped. Each rule's count is in the card.
* The two depression datasets are never pooled.
* Sleep file: read with `keep_default_na=False` so the label "None" survives. Person ID is dropped and the 242 exact duplicates are removed before any split. CV groups rows with identical core features, so no copy of a row can be in both training and test.

## Known limitations

* **Not a diagnosis.** Labels are self-reported survey answers or synthetic, not clinician assessments.
* **mood_student:** survey of Indian students with 58% positive. Probabilities reflect that survey, not student populations in general. Ages over about 34 count as outside the training range. The `both` segment borrows this model, so it is capped at medium.
* **mood_working:** about 200 positives and likely partly synthetic. Confidence is capped at medium.
* **sleep_pattern:** synthetic data; only 132 unique rows. The label-conflict rate is 18.2%, so 81.8% accuracy is the ceiling. Most student users fall outside the age gate and get guidance only. Some learned effects are implausible, for example more physical activity raising the apnea-like estimate.
