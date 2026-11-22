"""Glue between the HTTP layer and :mod:`app.wellness.predictor`.

No model logic lives here: it splits one check-in into the mood and sleep
inputs, calls the predictor and attaches model versions.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.wellness.display_config import DISCLAIMER
from app.wellness.predictor import load_models, predict_mood, predict_sleep
from app.wellness.schema import MOOD_FEATURES, SLEEP_EXTENDED_FEATURES, feature_names, sleep_band_from_hours

_MOOD_NAMES = feature_names(MOOD_FEATURES)
_SLEEP_NAMES = feature_names(SLEEP_EXTENDED_FEATURES)

# Fixed, in-range synthetic answers used only for the startup warm-up.
_WARMUP_ANSWERS: dict[str, Any] = {
    "age": 35, "sleep_duration": 7.0, "quality_of_sleep": 7, "stress_level": 5, "physical_activity_minutes": 45,
    "pressure": 3, "satisfaction": 3, "financial_stress": 3,
}


@dataclass(frozen=True)
class ModelRegistry:
    """Models loaded at startup plus their version info."""

    model_dir: Path
    versions: dict[str, dict[str, str]]

    @classmethod
    def load(cls, model_dir: Path) -> "ModelRegistry":
        """Load every model into the predictor cache and read its version.

        Called once from the app lifespan; later requests hit the cache only.
        """
        models = load_models(model_dir)
        versions: dict[str, dict[str, str]] = {}
        for name in models:
            card = json.loads((model_dir / f"{name}.model_card.json").read_text(encoding="utf-8"))
            sha = hashlib.sha256((model_dir / f"{name}.joblib").read_bytes()).hexdigest()
            stamp = datetime.fromisoformat(card["trained_at_utc"]).astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            versions[name] = {
                "version": f"{stamp}-{sha[:12]}",
                "trained_at_utc": card["trained_at_utc"],
                "algorithm": card["algorithm"],
                "sha256": sha,
            }
        registry = cls(Path(model_dir), versions)
        registry.warm_up()
        return registry

    def warm_up(self) -> None:
        """Exercise every model once so first-call setup (SHAP, numba, sklearn
        input checks) happens at startup, not on a user's first request.
        Without this the first check-in took ~1.3 s instead of ~15 ms."""
        for segment in ("student", "working"):
            run_checkin(self, segment, _WARMUP_ANSWERS)  # mood models + sleep core
        run_checkin(self, "student", {**_WARMUP_ANSWERS, "heart_rate": 70})  # sleep extended


def run_checkin(registry: ModelRegistry, segment: str, answers: dict[str, Any]) -> dict[str, Any]:
    """Score one check-in. ``answers`` is the validated request minus ``segment``.

    ``answers["sleep_duration"]`` is hours; the mood model gets the matching band.
    """
    given = {k: v for k, v in answers.items() if v is not None}
    mood_inputs = {k: given[k] for k in _MOOD_NAMES if k in given and k != "sleep_duration"}
    mood_inputs["sleep_duration"] = sleep_band_from_hours(given["sleep_duration"])
    sleep_inputs = {k: given[k] for k in _SLEEP_NAMES if k in given}

    mood = predict_mood(segment, mood_inputs, registry.model_dir)
    sleep = predict_sleep(sleep_inputs, registry.model_dir)
    mood.pop("disclaimer", None)
    sleep.pop("disclaimer", None)
    return {"mood": mood, "sleep": sleep, "model_versions": registry.versions, "disclaimer": DISCLAIMER}
