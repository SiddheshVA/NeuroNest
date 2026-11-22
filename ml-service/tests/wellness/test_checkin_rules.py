"""Check-in product rules: age-free working model, sleep range gate and
guidance, label codes from one config, and the "both" segment."""

from __future__ import annotations

import json

import joblib
import pytest

from app.wellness import display_config
from app.wellness.config import MODEL_DIR
from app.wellness.display_config import SLEEP_LABELS
from app.wellness.guidance import sleep_guidance
from app.wellness.predictor import CONFIDENCE_LEVELS, predict_mood, predict_sleep
from app.wellness.schema import SLEEP_CLASSES, SchemaError

from .conftest import SLEEP_CORE, SLEEP_FULL, STUDENT_FULL, WORKING_FULL

HIGH = CONFIDENCE_LEVELS.index("high")


# --- 1. mood_working without age, capped at medium -------------------------

def test_working_model_has_no_age_feature_and_card_says_why() -> None:
    bundle = joblib.load(MODEL_DIR / "mood_working.joblib")
    card = json.loads((MODEL_DIR / "mood_working.model_card.json").read_text(encoding="utf-8"))
    assert "age" not in bundle["features"]
    assert "Age" not in card["dataset"]["source_columns_used"]["features"]
    assert "age" in card["excluded_features"]["features"]
    assert bundle["confidence_ceiling"] == card["confidence"]["ceiling"] == "medium"
    auc = card["cross_validation"]["candidates"][card["algorithm"]]["roc_auc"]["mean"]
    assert 0.75 < auc < 0.9  # honest ~0.82, not the age-driven ~0.97


def test_working_ignores_age_and_accepts_its_absence() -> None:
    with_age = predict_mood("working", WORKING_FULL)
    other_age = predict_mood("working", {**WORKING_FULL, "age": 58})
    no_age = predict_mood("working", {k: v for k, v in WORKING_FULL.items() if k != "age"})
    assert with_age["ignored_inputs"] == ["age"] and no_age["ignored_inputs"] == []
    for a, b in ((with_age, other_age), (with_age, no_age)):
        assert (a["probability"], a["risk_band"], a["drivers"]) == (b["probability"], b["risk_band"], b["drivers"])
    assert "age" not in {d["feature"] for d in with_age["drivers"]}
    with pytest.raises(SchemaError):  # still validated if sent
        predict_mood("working", {**WORKING_FULL, "age": 5})


def test_working_confidence_never_high() -> None:
    out = predict_mood("working", WORKING_FULL)
    assert out["confidence"] == "medium"
    assert CONFIDENCE_LEVELS.index(out["confidence"]) < HIGH


# --- 2. sleep in-range gate and rule-based guidance -------------------------

@pytest.mark.parametrize(
    "override, gated",
    [({"age": 19}, ["age"]), ({"sleep_duration": 4.5}, ["sleep_duration"]),
     ({"age": 70, "sleep_duration": 10.0}, ["age", "sleep_duration"])],
)
def test_sleep_out_of_range_returns_no_pattern(override: dict, gated: list[str]) -> None:
    for base in (SLEEP_CORE, SLEEP_FULL):
        out = predict_sleep({**base, **override})
        assert out["in_range"] is False
        assert out["out_of_range"] == gated
        assert out["pattern_code"] is None and out["probabilities"] is None and out["display_text"] is None
        assert out["drivers"] == []
        assert out["reason"] and all(f in out["reason"] for f in gated)
        assert out["guidance"]["messages"]


def test_sleep_in_range_has_pattern_and_confidence_at_most_medium() -> None:
    for inputs in (SLEEP_CORE, SLEEP_FULL, {**SLEEP_CORE, "daily_steps": 8000}):
        out = predict_sleep(inputs)
        assert out["in_range"] is True and out["reason"] is None
        assert out["pattern_code"] is not None
        assert CONFIDENCE_LEVELS.index(out["confidence"]) < HIGH


def test_sleep_guidance_messages() -> None:
    short = sleep_guidance(5.5, 3, 20)
    codes = [m["code"] for m in short["messages"]]
    assert codes == ["sleep_short", "quality_low", "activity_low"]
    assert short["meets_sleep_guideline"] is False and short["meets_activity_guideline"] is False
    assert short["weekly_activity_minutes"] == 140

    good = sleep_guidance(7.5, 8, 30)  # 210 min/week
    assert [m["code"] for m in good["messages"]] == ["sleep_ok", "activity_ok"]
    assert good["meets_sleep_guideline"] and good["meets_activity_guideline"]

    assert [m["code"] for m in sleep_guidance(7, 5, 0)["messages"]][0] == "sleep_ok"  # 7 h meets the guideline
    assert sleep_guidance(10, 7, 60)["messages"][0]["code"] == "sleep_long"
    assert all("{" not in m["text"] for m in short["messages"])  # every placeholder filled


@pytest.mark.parametrize("args", [(1, 5, 30), (8, 11, 30), (8, 5, -1), ("8", 5, 30), (8, 5.5, 30)])
def test_sleep_guidance_validates_input(args: tuple) -> None:
    with pytest.raises(SchemaError):
        sleep_guidance(*args)


# --- 3. label codes, display text and see_doctor from one config ------------

def test_every_sleep_class_has_a_display_label() -> None:
    assert set(SLEEP_LABELS) == set(SLEEP_CLASSES)
    assert {lab.code for lab in SLEEP_LABELS.values()} == {"none", "insomnia_like", "apnea_like"}
    for lab in SLEEP_LABELS.values():
        assert lab.display_text.strip() and lab.short_label.strip()
        assert isinstance(lab.see_doctor, bool)
    assert SLEEP_LABELS["None"].see_doctor is False
    assert SLEEP_LABELS["Insomnia"].see_doctor and SLEEP_LABELS["Sleep Apnea"].see_doctor


def test_sleep_output_uses_codes_and_config_text_only() -> None:
    for inputs in (SLEEP_CORE, SLEEP_FULL):
        out = predict_sleep(inputs)
        lab = next(v for v in SLEEP_LABELS.values() if v.code == out["pattern_code"])
        assert out["display_text"] == lab.display_text and out["see_doctor"] == lab.see_doctor
        text = json.dumps(out)
        assert "Sleep Apnea" not in text and '"Insomnia"' not in text  # raw dataset class names never leak


def test_display_text_is_defined_in_one_place(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.wellness import predictor

    custom = dict(SLEEP_LABELS)
    for cls, lab in SLEEP_LABELS.items():
        custom[cls] = display_config.SleepLabel(lab.code, lab.short_label, f"EDITED {lab.code}", lab.see_doctor)
    monkeypatch.setattr(predictor, "SLEEP_LABELS", custom)
    out = predict_sleep(SLEEP_CORE)
    assert out["display_text"] == f"EDITED {out['pattern_code']}"


# --- 4. "both" segment -------------------------------------------------------

def test_both_uses_student_model_with_medium_cap() -> None:
    student = predict_mood("student", STUDENT_FULL)
    both = predict_mood("both", STUDENT_FULL)
    assert both["model"] == "mood_student" and both["segment"] == "both"
    assert both["probability"] == student["probability"] and both["risk_band"] == student["risk_band"]
    assert student["confidence"] == "high" and both["confidence"] == "medium"
    assert any("both" in r for r in both["confidence_reasons"])
    # The cap is a ceiling, not a floor: missing inputs still lower it.
    sparse = predict_mood("both", {k: STUDENT_FULL[k] for k in
                                   ("age", "pressure", "satisfaction", "sleep_duration", "financial_stress")})
    assert sparse["confidence"] == "low"
