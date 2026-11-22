"""End-to-end tests for the internal wellness API (FastAPI TestClient)."""

from __future__ import annotations

import json
import logging
import statistics
import time

import pytest
from fastapi.testclient import TestClient

from app.api import routes
from app.api.settings import ConfigError, Settings
from app.main import create_app
from app.wellness.display_config import DISCLAIMER

from .conftest import AUTH, FULL, REQUIRED, TEST_KEY, URL

MODELS = {"mood_student", "mood_working", "sleep_pattern_core", "sleep_pattern_extended"}


def _assert_error(resp, status: int, code: str) -> dict:
    assert resp.status_code == status, resp.text
    body = resp.json()
    assert set(body) == {"error"}
    err = body["error"]
    assert err["code"] == code and err["message"]
    assert err["request_id"] == resp.headers["X-Request-ID"]
    assert "Traceback" not in resp.text
    return err


# --- health ------------------------------------------------------------------

def test_health_reports_loaded_models(client: TestClient) -> None:
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok" and body["loaded"] is True
    assert set(body["model_versions"]) == MODELS
    for v in body["model_versions"].values():
        assert v["version"].endswith(v["sha256"][:12]) and len(v["sha256"]) == 64


def test_health_before_startup_says_not_loaded() -> None:
    app = create_app(Settings(internal_key=TEST_KEY))
    body = TestClient(app).get("/health").json()  # no `with`: lifespan not run
    assert body == {"status": "starting", "loaded": False, "model_versions": {}}


# --- happy paths ---------------------------------------------------------------

@pytest.mark.parametrize(
    "segment, model, max_conf",
    [("student", "mood_student", "high"), ("working", "mood_working", "medium"), ("both", "mood_student", "medium")],
)
def test_checkin_each_segment(client: TestClient, segment: str, model: str, max_conf: str) -> None:
    r = client.post(URL, json={**FULL, "segment": segment}, headers=AUTH)
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == {"request_id", "mood", "sleep", "model_versions", "disclaimer"}
    assert body["request_id"] == r.headers["X-Request-ID"]
    assert body["disclaimer"] == DISCLAIMER and "not a diagnosis" in body["disclaimer"]
    assert set(body["model_versions"]) == MODELS

    mood = body["mood"]
    assert mood["model"] == model and mood["segment"] == segment
    assert mood["risk_band"] in {"Low", "Moderate", "High"}
    assert 0 <= mood["probability"] <= 1
    assert mood["confidence"] == max_conf
    assert 1 <= len(mood["drivers"]) <= 3
    assert mood["ignored_inputs"] == (["age"] if segment == "working" else [])

    sleep = body["sleep"]
    assert sleep["in_range"] is True and sleep["model"] == "sleep_pattern_extended"
    assert sleep["pattern_code"] in {"none", "insomnia_like", "apnea_like"}
    assert sleep["display_text"] and isinstance(sleep["see_doctor"], bool)
    assert sleep["confidence"] in {"low", "medium"}
    assert sleep["guidance"]["messages"]


def test_sleep_hours_feed_mood_band(client: TestClient) -> None:
    """One sleep answer: hours for the sleep model, the derived band for mood."""
    from app.wellness.predictor import predict_mood
    from app.wellness.schema import MOOD_FEATURES

    mood_keys = {s.name for s in MOOD_FEATURES} - {"sleep_duration"}
    probs = set()
    for hours, band in [(4.5, "<5"), (6.0, "5-6"), (7.5, "7-8"), (8.4, ">8")]:
        api = client.post(URL, json={**FULL, "sleep_duration": hours}, headers=AUTH).json()["mood"]
        direct = predict_mood("student", {**{k: FULL[k] for k in mood_keys}, "sleep_duration": band})
        assert api["probability"] == direct["probability"] and api["drivers"] == direct["drivers"], hours
        probs.add(api["probability"])
    assert len(probs) == 4  # every band changes the mood estimate


def test_missing_optional_inputs(client: TestClient) -> None:
    r = client.post(URL, json=REQUIRED, headers=AUTH)
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body["mood"]["imputed"]) == {"dietary_habits", "work_study_hours", "family_history"}
    assert body["mood"]["confidence"] == "low"
    assert body["sleep"]["model"] == "sleep_pattern_core" and body["sleep"]["imputed"] == []
    # Explicit nulls behave like omitted optional fields.
    nulls = client.post(URL, json={**REQUIRED, "heart_rate": None, "dietary_habits": None}, headers=AUTH)
    assert nulls.status_code == 200 and nulls.json()["mood"] == body["mood"]


def test_out_of_range_sleep_returns_guidance_only(client: TestClient) -> None:
    r = client.post(URL, json={**FULL, "age": 20, "sleep_duration": 4.5}, headers=AUTH)
    assert r.status_code == 200, r.text
    body = r.json()
    sleep = body["sleep"]
    assert sleep["in_range"] is False and sleep["reason"]
    assert sleep["pattern_code"] is None and sleep["probabilities"] is None and sleep["confidence"] is None
    assert sleep["drivers"] == [] and set(sleep["out_of_range"]) == {"age", "sleep_duration"}
    assert [m["code"] for m in sleep["guidance"]["messages"]][0] == "sleep_short"
    assert body["mood"]["risk_band"] in {"Low", "Moderate", "High"}  # mood still answered


def test_response_never_contains_raw_dataset_labels(client: TestClient) -> None:
    cases = [
        FULL,
        {**REQUIRED, "age": 45, "sleep_duration": 6.2, "quality_of_sleep": 6, "stress_level": 7,
         "physical_activity_minutes": 40},
        {**REQUIRED, "age": 35, "sleep_duration": 6.0, "quality_of_sleep": 5, "stress_level": 8,
         "physical_activity_minutes": 30, "bmi_category": "overweight", "heart_rate": 80, "daily_steps": 4000},
    ]
    seen = set()
    for case in cases:
        r = client.post(URL, json=case, headers=AUTH)
        assert r.status_code == 200
        assert "Insomnia" not in r.text and "Sleep Apnea" not in r.text
        seen.add(r.json()["sleep"]["pattern_code"])
    assert {"insomnia_like", "apnea_like"} <= seen  # the risky classes were actually exercised


# --- auth ------------------------------------------------------------------------

@pytest.mark.parametrize("headers", [{}, {"X-Internal-Key": "wrong"}, {"X-Internal-Key": TEST_KEY + "x"}])
def test_bad_or_missing_key_is_401(client: TestClient, headers: dict) -> None:
    _assert_error(client.post(URL, json=FULL, headers=headers), 401, "unauthorized")


def test_auth_runs_before_validation(client: TestClient) -> None:
    # An unauthenticated caller learns nothing about the schema.
    _assert_error(client.post(URL, json={"pressure": 99}, headers={}), 401, "unauthorized")


def test_startup_fails_without_key_outside_dev_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(ConfigError):
        with TestClient(create_app(Settings(internal_key=None))):
            pass
    with pytest.raises(ConfigError):
        with TestClient(create_app(Settings(internal_key="short"))):
            pass
    monkeypatch.delenv("WELLNESS_INTERNAL_KEY", raising=False)
    monkeypatch.setenv("WELLNESS_DEV_MODE", "0")
    with pytest.raises(ConfigError):
        Settings.from_env().check()
    monkeypatch.setenv("WELLNESS_INTERNAL_KEY", TEST_KEY)
    assert Settings.from_env().auth_enabled


def test_dev_mode_without_key_disables_auth_and_enables_docs() -> None:
    with TestClient(create_app(Settings(internal_key=None, dev_mode=True))) as c:
        assert c.post(URL, json=FULL).status_code == 200
        assert c.get("/docs").status_code == 200


def test_docs_are_off_outside_dev_mode(client: TestClient) -> None:
    _assert_error(client.get("/docs"), 404, "not_found")
    _assert_error(client.get("/openapi.json"), 404, "not_found")


def test_no_cors_headers(client: TestClient) -> None:
    r = client.options(URL, headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"})
    assert "access-control-allow-origin" not in {k.lower() for k in r.headers}


# --- validation ------------------------------------------------------------------

@pytest.mark.parametrize(
    "override, field",
    [
        ({"pressure": 6}, "pressure"),
        ({"financial_stress": 0}, "financial_stress"),
        ({"pressure": 4.5}, "pressure"),          # int field
        ({"pressure": "4"}, "pressure"),          # no string coercion
        ({"age": 12}, "age"),
        ({"age": 85}, "age"),                     # stricter of mood (<=80) and sleep (<=90)
        ({"sleep_duration": 20}, "sleep_duration"),
        ({"quality_of_sleep": 11}, "quality_of_sleep"),
        ({"bmi_category": "underweight"}, "bmi_category"),
        ({"dietary_habits": "keto"}, "dietary_habits"),
        ({"family_history": "yes"}, "family_history"),
        ({"daily_steps": -1}, "daily_steps"),
        ({"segment": "retired"}, "segment"),
    ],
)
def test_invalid_ranges_are_422_with_field_messages(client: TestClient, override: dict, field: str) -> None:
    err = _assert_error(client.post(URL, json={**FULL, **override}, headers=AUTH), 422, "validation_error")
    assert [f["field"] for f in err["fields"]] == [field]
    assert field in err["message"]


def test_missing_required_and_unknown_fields_are_422(client: TestClient) -> None:
    body = {k: v for k, v in FULL.items() if k != "stress_level"}
    err = _assert_error(client.post(URL, json=body, headers=AUTH), 422, "validation_error")
    assert err["fields"][0]["field"] == "stress_level" and err["fields"][0]["type"] == "missing"
    err = _assert_error(client.post(URL, json={**FULL, "mood_today": 3}, headers=AUTH), 422, "validation_error")
    assert err["fields"][0] == {"field": "mood_today", "message": "Extra inputs are not permitted",
                                "type": "extra_forbidden"}


def test_malformed_json_is_422(client: TestClient) -> None:
    r = client.post(URL, content=b"{not json", headers={**AUTH, "Content-Type": "application/json"})
    _assert_error(r, 422, "validation_error")


@pytest.mark.parametrize(
    "key",
    ["Have you ever had suicidal thoughts ?", "suicidal_thoughts", "SuicidalThoughts", "gender", "Gender", "city",
     "degree", "name", "id", "profession", "cgpa", "CGPA", "occupation", "person_id"],
)
def test_forbidden_fields_are_rejected_explicitly(client: TestClient, key: str) -> None:
    err = _assert_error(client.post(URL, json={**FULL, key: "x"}, headers=AUTH), 422, "forbidden_field")
    assert err["fields"][0]["field"] == key
    assert "not accepted" in err["message"]


# --- errors, request ids, logging, latency --------------------------------------

def test_unhandled_error_is_500_without_details(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(*_a, **_k):
        raise RuntimeError("secret detail age=47")

    monkeypatch.setattr(routes, "run_checkin", boom)
    err = _assert_error(client.post(URL, json=FULL, headers=AUTH), 500, "internal_error")
    assert err["message"] == "Internal server error."


def test_request_id_is_echoed_or_generated(client: TestClient) -> None:
    r = client.post(URL, json=FULL, headers={**AUTH, "X-Request-ID": "spring-abc-123"})
    assert r.headers["X-Request-ID"] == "spring-abc-123" and r.json()["request_id"] == "spring-abc-123"
    r = client.post(URL, json=FULL, headers={**AUTH, "X-Request-ID": "bad id\nwith newline"})
    assert r.headers["X-Request-ID"] != "bad id\nwith newline" and len(r.headers["X-Request-ID"]) == 32
    _assert_error(client.get("/nope"), 404, "not_found")


def test_request_bodies_and_predictions_are_never_logged(client: TestClient, caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.DEBUG)
    ok = client.post(URL, json={**FULL, "daily_steps": 12345}, headers={**AUTH, "X-Request-ID": "log-test-1"})
    bad = client.post(URL, json={**FULL, "gender": "female-xyz"}, headers={**AUTH, "X-Request-ID": "log-test-2"})
    invalid = client.post(URL, json={**FULL, "pressure": 987}, headers={**AUTH, "X-Request-ID": "log-test-3"})
    assert (ok.status_code, bad.status_code, invalid.status_code) == (200, 422, 422)

    logs = "\n".join(r.getMessage() for r in caplog.records)
    sensitive = ["12345", "female-xyz", "987", "risk_band", "probability", "insomnia", "apnea", "pressure",
                 "display_text", json.dumps(FULL)[:20], ok.json()["mood"]["risk_band"]]
    for s in sensitive:
        assert s not in logs, f"{s!r} leaked into logs"
    access = [r.getMessage() for r in caplog.records if "log-test-1" in r.getMessage()]
    assert access and "status=200" in access[0] and "latency_ms=" in access[0]


def test_first_request_after_startup_is_fast() -> None:
    """Startup warm-up means the first real check-in is not a cold call."""
    with TestClient(create_app(Settings(internal_key=TEST_KEY))) as c:
        body = {**FULL, "segment": "working"}
        start = time.perf_counter()
        assert c.post(URL, json=body, headers=AUTH).status_code == 200
        first_ms = (time.perf_counter() - start) * 1000
    assert first_ms < 250, f"first request took {first_ms:.0f} ms"


def test_checkin_latency_is_well_under_budget(client: TestClient) -> None:
    """Target is <50 ms in-process; assert a generous bound to avoid CI flakiness."""
    for _ in range(3):  # warm-up
        client.post(URL, json=FULL, headers=AUTH)
    times = []
    for i in range(30):
        body = {**FULL, "stress_level": 3 + i % 6, "heart_rate": 65 + i % 20}
        start = time.perf_counter()
        assert client.post(URL, json=body, headers=AUTH).status_code == 200
        times.append((time.perf_counter() - start) * 1000)
    median, worst = statistics.median(times), max(times)
    print(f"check-in latency: median {median:.1f} ms, max {worst:.1f} ms")
    assert median < 100, f"median {median:.1f} ms"
    assert worst < 500, f"max {worst:.1f} ms"
