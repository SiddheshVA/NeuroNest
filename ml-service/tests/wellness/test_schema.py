"""Schema validation rejects bad input before any model is touched."""

from __future__ import annotations

import math

import pytest

from app.wellness.predictor import predict_mood, predict_sleep
from app.wellness.schema import MOOD_FEATURES, SLEEP_EXTENDED_FEATURES, SchemaError, validate

from .conftest import SLEEP_CORE, STUDENT_FULL


@pytest.mark.parametrize(
    "field, value",
    [
        ("pressure", 6),
        ("pressure", 0),
        ("satisfaction", -1),
        ("financial_stress", 2.5),  # int field
        ("age", 10),
        ("age", 120),
        ("work_study_hours", 30),
        ("sleep_duration", "9 hours"),
        ("dietary_habits", "keto"),
        ("family_history", "maybe"),
        ("pressure", True),  # bool is not a number here
        ("pressure", "4"),
        ("age", math.nan),
    ],
)
def test_mood_rejects_out_of_range_or_wrong_type(field: str, value: object) -> None:
    with pytest.raises(SchemaError):
        validate({**STUDENT_FULL, field: value}, MOOD_FEATURES)
    with pytest.raises(SchemaError):
        predict_mood("student", {**STUDENT_FULL, field: value})


@pytest.mark.parametrize(
    "field, value",
    [
        ("quality_of_sleep", 11),
        ("stress_level", 0),
        ("sleep_duration", 20),
        ("physical_activity_minutes", -5),
        ("heart_rate", 250),
        ("daily_steps", -1),
        ("bmi_category", "underweight"),
    ],
)
def test_sleep_rejects_out_of_range(field: str, value: object) -> None:
    with pytest.raises(SchemaError):
        predict_sleep({**SLEEP_CORE, field: value})


def test_missing_required_is_rejected() -> None:
    inputs = dict(STUDENT_FULL)
    del inputs["pressure"]
    with pytest.raises(SchemaError, match="pressure: required"):
        predict_mood("student", inputs)
    sleep = dict(SLEEP_CORE)
    del sleep["stress_level"]
    with pytest.raises(SchemaError, match="stress_level: required"):
        predict_sleep(sleep)


def test_unknown_fields_and_bad_segment_are_rejected() -> None:
    with pytest.raises(SchemaError, match="unknown"):
        predict_mood("student", {**STUDENT_FULL, "gender": "female"})
    with pytest.raises(SchemaError, match="unknown"):
        predict_sleep({**SLEEP_CORE, "suicidal_thoughts": True})
    with pytest.raises(SchemaError, match="segment"):
        predict_mood("retired", STUDENT_FULL)


def test_valid_input_encodes_in_schema_order() -> None:
    enc = validate({**SLEEP_CORE, "bmi_category": "Obese"}, SLEEP_EXTENDED_FEATURES)
    assert list(enc) == [s.name for s in SLEEP_EXTENDED_FEATURES]
    assert enc["bmi_category"] == 2.0
    assert enc["heart_rate"] is None


@pytest.mark.parametrize(
    "hours, band",
    [(2, "<5"), (4.9, "<5"), (5, "5-6"), (6.0, "5-6"), (6.9, "5-6"), (7, "7-8"), (8, "7-8"), (8.1, ">8"), (14, ">8")],
)
def test_sleep_band_from_hours(hours: float, band: str) -> None:
    from app.wellness.schema import SLEEP_BANDS, sleep_band_from_hours

    assert sleep_band_from_hours(hours) == band
    assert band in SLEEP_BANDS
