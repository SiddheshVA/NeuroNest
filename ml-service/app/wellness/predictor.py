"""Prediction API for the wellness check-in models (pure, in-memory).

``predict_mood(segment, inputs)`` and ``predict_sleep(inputs)`` validate the
inputs, impute missing optional fields, score the model, derive a confidence
level and return the top-3 drivers. Models are loaded from disk once (on
first use, or via :func:`load_models`) and cached; after that a call does no
disk or network I/O, so it is cheap enough for live "what-if" sliders.

Outputs are estimates for self-reflection, never a diagnosis. All
user-facing wording comes from :mod:`app.wellness.display_config`.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

import joblib
import numpy as np
import pandas as pd

from .config import MODEL_DIR, MOOD_SEGMENT_ROUTING
from .display_config import DISCLAIMER, SLEEP_LABELS, SLEEP_OUT_OF_RANGE_REASON
from .explain import Contributor, build_contributor, top_drivers
from .guidance import sleep_guidance
from .schema import (
    MOOD_FEATURES,
    MOOD_SEGMENTS,
    MOOD_WORKING_FEATURES,
    MOOD_WORKING_INPUTS,
    SLEEP_BOOSTER_FEATURES,
    SLEEP_CORE_FEATURES,
    SLEEP_EXTENDED_FEATURES,
    FeatureSpec,
    SchemaError,
    feature_names,
    validate,
)

CONFIDENCE_LEVELS: tuple[str, ...] = ("low", "medium", "high")
_SLEEP_CORE, _SLEEP_EXT = "sleep_pattern_core", "sleep_pattern_extended"
# Outside the training range of these, the sleep model returns no pattern.
SLEEP_GATED_FEATURES: tuple[str, ...] = ("age", "sleep_duration")
_MODEL_SPECS: dict[str, tuple[FeatureSpec, ...]] = {
    "mood_student": MOOD_FEATURES,
    "mood_working": MOOD_WORKING_FEATURES,
    _SLEEP_CORE: SLEEP_CORE_FEATURES,
    _SLEEP_EXT: SLEEP_EXTENDED_FEATURES,
}


@dataclass(frozen=True)
class LoadedModel:
    """A model bundle plus its (expensive-to-build) explainer, built once."""

    bundle: dict[str, Any]
    contributor: Contributor

    @property
    def features(self) -> list[str]:
        return self.bundle["features"]


_lock = threading.Lock()
_cache: dict[Path, dict[str, LoadedModel]] = {}


def load_models(model_dir: Path = MODEL_DIR) -> dict[str, LoadedModel]:
    """Load (once) and return every wellness model in ``model_dir``."""
    model_dir = Path(model_dir).resolve()
    with _lock:
        if model_dir not in _cache:
            loaded: dict[str, LoadedModel] = {}
            for name, specs in _MODEL_SPECS.items():
                path = model_dir / f"{name}.joblib"
                if not path.exists():
                    raise FileNotFoundError(f"{path} missing; run `python -m app.wellness.train`")
                bundle = joblib.load(path)
                if bundle["features"] != feature_names(specs):
                    raise RuntimeError(f"{name}: saved feature order does not match schema; retrain")
                loaded[name] = LoadedModel(bundle, build_contributor(bundle["base_model"]))
            _cache[model_dir] = loaded
        return _cache[model_dir]


def _confidence(
    ceiling: str,
    missing_penalty: int,
    out_of_range: list[str],
    missing: list[str],
    cap: str | None = None,
    cap_reason: str | None = None,
) -> tuple[str, list[str]]:
    """Start at the model's ceiling, drop one level per issue, then apply ``cap``."""
    level = CONFIDENCE_LEVELS.index(ceiling)
    reasons: list[str] = []
    if ceiling != "high":
        reasons.append(f"model quality limits confidence to '{ceiling}'")
    if missing_penalty:
        level -= missing_penalty
        reasons.append(f"optional input(s) not provided: {missing}")
    if out_of_range:
        level -= 1
        reasons.append(f"input(s) outside the range seen in training: {out_of_range}")
    if cap is not None:
        level = min(level, CONFIDENCE_LEVELS.index(cap))
        if cap_reason:
            reasons.append(cap_reason)
    return CONFIDENCE_LEVELS[max(level, 0)], reasons


def _prepare(
    model: LoadedModel, encoded: dict[str, float | None]
) -> tuple[pd.DataFrame, list[str], list[str]]:
    """Impute missing values; list imputed and out-of-training-range inputs."""
    b = model.bundle
    row: dict[str, float] = {}
    imputed: list[str] = []
    out_of_range: list[str] = []
    for name in model.features:
        v = encoded.get(name)
        if v is None:
            row[name] = b["impute_values"][name]
            imputed.append(name)
            continue
        lo, hi = b["train_ranges"][name]
        if not lo <= v <= hi:
            out_of_range.append(name)
        row[name] = v
    return pd.DataFrame([row], columns=model.features), imputed, out_of_range


def _score(model: LoadedModel, X: pd.DataFrame) -> np.ndarray:
    return np.asarray(model.bundle["model"].predict_proba(X))[0]


def _raw_inputs(inputs: Mapping[str, Any], specs: tuple[FeatureSpec, ...]) -> dict[str, Any]:
    return {s.name: inputs.get(s.name) for s in specs}


def predict_mood(segment: str, inputs: Mapping[str, Any], model_dir: Path = MODEL_DIR) -> dict[str, Any]:
    """Mood risk band for the check-in segment.

    Args:
        segment: ``"student"``, ``"working"`` or ``"both"`` (studying and
            working: uses the student model, confidence capped at medium).
        inputs: See :data:`app.wellness.schema.MOOD_FEATURES`. ``pressure`` and
            ``satisfaction`` are academic for students, work/job for workers.
            For ``"working"`` age is optional and ignored by the model.

    Returns:
        dict with ``risk_band`` (Low / Moderate / High), calibrated
        ``probability``, ``confidence`` (+ reasons), ``drivers`` (top 3),
        ``imputed``, ``ignored_inputs`` and ``out_of_range`` input names.
        Never a diagnosis label.

    Raises:
        SchemaError: invalid segment or inputs.
    """
    if segment not in MOOD_SEGMENTS:
        raise SchemaError(f"segment must be one of {list(MOOD_SEGMENTS)}, got {segment!r}")
    model_name, segment_cap = MOOD_SEGMENT_ROUTING[segment]
    input_specs = MOOD_WORKING_INPUTS if model_name == "mood_working" else MOOD_FEATURES
    encoded = validate(inputs, input_specs)
    model = load_models(model_dir)[model_name]
    b = model.bundle
    ignored = [n for n, v in encoded.items() if v is not None and n not in model.features]
    X, imputed, out_of_range = _prepare(model, encoded)
    p = float(_score(model, X)[1])
    bands = b["bands"]
    band = "Low" if p < bands["low_below"] else "High" if p >= bands["high_at_or_above"] else "Moderate"
    confidence, reasons = _confidence(
        b["confidence_ceiling"], min(len(imputed), 2), out_of_range, imputed,
        cap=segment_cap,
        cap_reason=None if segment_cap is None else
        f"segment '{segment}' uses the {model_name} model; confidence capped at '{segment_cap}'",
    )
    if ignored:
        reasons.append(f"input(s) not used by this model: {ignored}")
    contrib = model.contributor.contributions(X.to_numpy()[0], class_index=1)
    drivers = top_drivers(
        contrib, model.features, b["feature_labels"], _raw_inputs(inputs, input_specs), set(imputed),
        subject="estimated risk",
    )
    return {
        "model": b["name"],
        "segment": segment,
        "risk_band": band,
        "probability": round(p, 4),
        "confidence": confidence,
        "confidence_reasons": reasons,
        "drivers": drivers,
        "imputed": imputed,
        "ignored_inputs": ignored,
        "out_of_range": out_of_range,
        "disclaimer": DISCLAIMER,
    }


def predict_sleep(inputs: Mapping[str, Any], model_dir: Path = MODEL_DIR) -> dict[str, Any]:
    """Sleep pattern estimate, or guidance only when out of range.

    Core inputs are required. Booster inputs (``bmi_category``, ``heart_rate``,
    ``daily_steps``) are optional: if any is given the extended model is used
    (missing boosters imputed), otherwise the core model.

    If ``age`` or ``sleep_duration`` is outside the training range, no pattern
    is returned (``in_range=False``, ``pattern_code=None``) and only the
    rule-based ``guidance`` is meaningful.

    Returns:
        dict with ``in_range``, ``reason``, ``pattern_code``
        (none / insomnia_like / apnea_like), ``display_text``, ``see_doctor``,
        ``probability``, ``probabilities`` (by code), ``confidence``
        (+ reasons; never above medium), ``drivers``, ``imputed``,
        ``out_of_range``, ``guidance`` and the ``model`` variant used.
    """
    encoded = validate(inputs, SLEEP_EXTENDED_FEATURES)
    models = load_models(model_dir)
    guidance = sleep_guidance(inputs["sleep_duration"], inputs["quality_of_sleep"], inputs["physical_activity_minutes"])

    ranges = models[_SLEEP_CORE].bundle["train_ranges"]
    gated = [f for f in SLEEP_GATED_FEATURES if not ranges[f][0] <= encoded[f] <= ranges[f][1]]
    if gated:
        reason = SLEEP_OUT_OF_RANGE_REASON.format(
            age_lo=ranges["age"][0], age_hi=ranges["age"][1],
            sleep_lo=ranges["sleep_duration"][0], sleep_hi=ranges["sleep_duration"][1],
            fields=", ".join(gated),
        )
        return {
            "model": None,
            "in_range": False,
            "reason": reason,
            "pattern_code": None,
            "display_text": None,
            "see_doctor": None,
            "probability": None,
            "probabilities": None,
            "probabilities_calibrated": None,
            "confidence": None,
            "confidence_reasons": [reason],
            "drivers": [],
            "imputed": [],
            "out_of_range": gated,
            "guidance": guidance,
            "disclaimer": DISCLAIMER,
        }

    given = [n for n in feature_names(SLEEP_BOOSTER_FEATURES) if encoded[n] is not None]
    model = models[_SLEEP_EXT] if given else models[_SLEEP_CORE]
    b = model.bundle
    X, imputed, out_of_range = _prepare(model, encoded)
    proba = _score(model, X)
    k = int(np.argmax(proba))
    # Penalise only imputed values the model consumes. The core-only path uses
    # a model trained on exactly those inputs, so absent boosters cost nothing
    # (the extended model is only ~+0.01 macro-F1 better, within CV noise).
    confidence, reasons = _confidence(
        b["confidence_ceiling"], 1 if imputed else 0, out_of_range, imputed, cap="medium"
    )
    if not given:
        reasons.append("booster inputs (bmi_category, heart_rate, daily_steps) not provided; core model used")
    label = SLEEP_LABELS[b["classes"][k]]
    contrib = model.contributor.contributions(X.to_numpy()[0], class_index=k)
    drivers = top_drivers(
        contrib, model.features, b["feature_labels"], _raw_inputs(inputs, SLEEP_EXTENDED_FEATURES), set(imputed),
        subject=f"likelihood of {label.short_label}",
    )
    return {
        "model": b["name"],
        "in_range": True,
        "reason": None,
        "pattern_code": label.code,
        "display_text": label.display_text,
        "see_doctor": label.see_doctor,
        "probability": round(float(proba[k]), 4),
        "probabilities": {SLEEP_LABELS[c].code: round(float(v), 4) for c, v in zip(b["classes"], proba)},
        "probabilities_calibrated": bool(b["calibrated"]),
        "confidence": confidence,
        "confidence_reasons": reasons,
        "drivers": drivers,
        "imputed": imputed,
        "out_of_range": out_of_range,
        "guidance": guidance,
        "disclaimer": DISCLAIMER,
    }
