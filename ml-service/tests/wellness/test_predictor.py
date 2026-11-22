"""Behaviour of the saved models through the public predictor functions."""

from __future__ import annotations

import itertools

import numpy as np
import pytest

from app.wellness import predictor
from app.wellness.predictor import CONFIDENCE_LEVELS, predict_mood, predict_sleep
from app.wellness.display_config import SLEEP_LABELS
from app.wellness.schema import DIET_LEVELS, SLEEP_BANDS

from .conftest import SLEEP_CORE, SLEEP_FULL, STUDENT_FULL, WORKING_FULL

BAND_RANK = {"Low": 0, "Moderate": 1, "High": 2}
SEGMENTS = [("student", STUDENT_FULL), ("working", WORKING_FULL)]


def _rank(level: str) -> int:
    return CONFIDENCE_LEVELS.index(level)


@pytest.mark.parametrize("segment, full", SEGMENTS)
def test_mood_output_contract(segment: str, full: dict) -> None:
    out = predict_mood(segment, full)
    assert out["risk_band"] in BAND_RANK
    assert 0.0 <= out["probability"] <= 1.0
    assert out["confidence"] in CONFIDENCE_LEVELS
    assert 1 <= len(out["drivers"]) <= 3
    for d in out["drivers"]:
        assert d["direction"] in {"increases", "decreases"}
        assert d["effect"].endswith("estimated risk")
        assert (d["impact"] > 0) == (d["direction"] == "increases")
    # Bands only: no diagnosis-style label anywhere in the response.
    assert "depress" not in str(out).lower()


@pytest.mark.parametrize("segment, full", SEGMENTS)
def test_missing_optional_mood_inputs_work_and_lower_confidence(segment: str, full: dict) -> None:
    required_only = {k: full[k] for k in ("age", "pressure", "satisfaction", "sleep_duration", "financial_stress")}
    full_out = predict_mood(segment, full)
    one_missing = predict_mood(segment, {k: v for k, v in full.items() if k != "family_history"})
    req_out = predict_mood(segment, required_only)

    assert one_missing["imputed"] == ["family_history"]
    assert set(req_out["imputed"]) == {"dietary_habits", "work_study_hours", "family_history"}
    assert _rank(one_missing["confidence"]) < _rank(full_out["confidence"]) or full_out["confidence"] == "low"
    assert _rank(req_out["confidence"]) <= _rank(one_missing["confidence"])
    # Explicit None is treated like an absent optional field.
    assert predict_mood(segment, {**full, "dietary_habits": None})["imputed"] == ["dietary_habits"]
    # Imputed inputs are never offered as drivers.
    assert not {d["feature"] for d in req_out["drivers"]} & set(req_out["imputed"])


def test_out_of_training_range_lowers_confidence() -> None:
    typical = predict_mood("student", STUDENT_FULL)
    older = predict_mood("student", {**STUDENT_FULL, "age": 55})  # valid, but rare in student data
    assert "age" in older["out_of_range"]
    assert _rank(older["confidence"]) < _rank(typical["confidence"])
    # Non-gated sleep input outside training range (quality 1-3 unseen) lowers confidence.
    sleep_ok = predict_sleep(SLEEP_CORE)
    sleep_odd = predict_sleep({**SLEEP_CORE, "quality_of_sleep": 2})
    assert sleep_odd["in_range"] and "quality_of_sleep" in sleep_odd["out_of_range"]
    assert _rank(sleep_odd["confidence"]) < _rank(sleep_ok["confidence"])


def _random_profiles(n: int, seed: int) -> list[dict]:
    rng = np.random.default_rng(seed)
    return [
        {
            "age": int(rng.integers(18, 60)),
            "pressure": int(rng.integers(1, 6)),
            "satisfaction": int(rng.integers(1, 6)),
            "sleep_duration": str(rng.choice(SLEEP_BANDS)),
            "financial_stress": int(rng.integers(1, 6)),
            "dietary_habits": str(rng.choice(DIET_LEVELS)),
            "work_study_hours": int(rng.integers(0, 13)),
            "family_history": bool(rng.integers(0, 2)),
        }
        for _ in range(n)
    ]


@pytest.mark.parametrize("segment", ["student", "working"])
@pytest.mark.parametrize("field", ["pressure", "financial_stress"])
def test_more_pressure_or_financial_stress_never_lowers_risk(segment: str, field: str) -> None:
    profiles = _random_profiles(150, seed=7)
    mean_rank, mean_p = [], []
    for level in range(1, 6):
        outs = [predict_mood(segment, {**p, field: level}) for p in profiles]
        mean_rank.append(np.mean([BAND_RANK[o["risk_band"]] for o in outs]))
        mean_p.append(np.mean([o["probability"] for o in outs]))
    assert all(b >= a for a, b in itertools.pairwise(mean_rank)), mean_rank
    assert all(b >= a for a, b in itertools.pairwise(mean_p)), mean_p
    assert mean_p[-1] > mean_p[0]


def test_sleep_core_and_extended_paths() -> None:
    core = predict_sleep(SLEEP_CORE)
    full = predict_sleep(SLEEP_FULL)
    partial = predict_sleep({**SLEEP_CORE, "heart_rate": 75})

    assert core["model"] == "sleep_pattern_core" and core["imputed"] == []
    assert full["model"] == "sleep_pattern_extended" and full["imputed"] == []
    assert partial["model"] == "sleep_pattern_extended"
    assert set(partial["imputed"]) == {"bmi_category", "daily_steps"}
    assert _rank(partial["confidence"]) < _rank(full["confidence"])
    for out in (core, full, partial):
        codes = {lab.code for lab in SLEEP_LABELS.values()}
        assert out["in_range"] and out["pattern_code"] in codes
        assert set(out["probabilities"]) == codes
        assert abs(sum(out["probabilities"].values()) - 1) < 1e-3
        assert out["probability"] == max(out["probabilities"].values())
        assert 1 <= len(out["drivers"]) <= 3
        assert not {d["feature"] for d in out["drivers"]} & set(out["imputed"])


def test_predictions_are_deterministic() -> None:
    a = [predict_mood("student", STUDENT_FULL), predict_mood("working", WORKING_FULL), predict_sleep(SLEEP_FULL)]
    b = [predict_mood("student", STUDENT_FULL), predict_mood("working", WORKING_FULL), predict_sleep(SLEEP_FULL)]
    assert a == b
    predictor._cache.clear()  # force a fresh load from disk
    c = [predict_mood("student", STUDENT_FULL), predict_mood("working", WORKING_FULL), predict_sleep(SLEEP_FULL)]
    assert a == c


def test_inputs_are_not_mutated() -> None:
    inputs = dict(STUDENT_FULL)
    predict_mood("student", inputs)
    assert inputs == STUDENT_FULL
