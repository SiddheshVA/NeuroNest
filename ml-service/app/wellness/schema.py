"""Input schemas for the wellness check-in models.

Each model has an ordered list of :class:`FeatureSpec` entries. The order is
the column order the model is trained on. Hard ranges here are *validation*
limits for user input (what the app may send); the narrower *training* ranges
are stored in each model bundle and only lower the confidence.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Any, Literal, Mapping

Kind = Literal["int", "float", "ordinal", "binary"]


class SchemaError(ValueError):
    """Raised when prediction input does not satisfy the schema."""


@dataclass(frozen=True)
class FeatureSpec:
    """One model input.

    Attributes:
        name: Public input name (also the training column name).
        kind: ``int``/``float`` numeric, ``ordinal`` (string label mapped to
            0..n-1 in ``choices`` order) or ``binary`` (bool / 0 / 1).
        required: Whether the app must always send it.
        min_value / max_value: Inclusive hard limits for numeric kinds.
        choices: Ordered labels for ``ordinal`` kinds (low to high).
        label: Human-readable name used in driver explanations.
    """

    name: str
    kind: Kind
    required: bool
    label: str
    min_value: float | None = None
    max_value: float | None = None
    choices: tuple[str, ...] = field(default_factory=tuple)

    def encode(self, value: Any) -> float:
        """Validate one raw value and return its numeric encoding."""
        if self.kind == "ordinal":
            if not isinstance(value, str) or value.strip().lower() not in self.choices:
                raise SchemaError(f"{self.name}: expected one of {list(self.choices)}, got {value!r}")
            return float(self.choices.index(value.strip().lower()))
        if self.kind == "binary":
            if isinstance(value, bool):
                return float(value)
            if isinstance(value, (int, float)) and value in (0, 1):
                return float(value)
            raise SchemaError(f"{self.name}: expected true/false, got {value!r}")
        # numeric
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise SchemaError(f"{self.name}: expected a number, got {value!r}")
        if value != value:  # NaN
            raise SchemaError(f"{self.name}: NaN is not allowed")
        if self.kind == "int" and float(value) != int(value):
            raise SchemaError(f"{self.name}: expected a whole number, got {value!r}")
        assert self.min_value is not None and self.max_value is not None
        if not self.min_value <= value <= self.max_value:
            raise SchemaError(
                f"{self.name}: {value!r} outside allowed range [{self.min_value}, {self.max_value}]"
            )
        return float(value)

    def to_card(self) -> dict[str, Any]:
        """JSON-serialisable description for the model card."""
        out: dict[str, Any] = {"name": self.name, "kind": self.kind, "required": self.required, "label": self.label}
        if self.kind == "ordinal":
            out["choices_low_to_high"] = list(self.choices)
        elif self.kind == "binary":
            out["choices"] = [False, True]
        else:
            out["allowed_range"] = [self.min_value, self.max_value]
        return out


SLEEP_BANDS: tuple[str, ...] = ("<5", "5-6", "7-8", ">8")
DIET_LEVELS: tuple[str, ...] = ("unhealthy", "moderate", "healthy")
BMI_LEVELS: tuple[str, ...] = ("normal", "overweight", "obese")

# Shared by both mood segments. "pressure" / "satisfaction" mean academic for
# students and work/job for working professionals.
MOOD_FEATURES: tuple[FeatureSpec, ...] = (
    FeatureSpec("age", "int", True, "Age", 16, 80),
    FeatureSpec("pressure", "int", True, "Academic / work pressure", 1, 5),
    FeatureSpec("satisfaction", "int", True, "Study / job satisfaction", 1, 5),
    FeatureSpec("sleep_duration", "ordinal", True, "Sleep duration", choices=SLEEP_BANDS),
    FeatureSpec("financial_stress", "int", True, "Financial stress", 1, 5),
    FeatureSpec("dietary_habits", "ordinal", False, "Diet quality", choices=DIET_LEVELS),
    FeatureSpec("work_study_hours", "float", False, "Daily work/study hours", 0, 16),
    FeatureSpec("family_history", "binary", False, "Family history of mental illness"),
)

# mood_working is trained WITHOUT age: in dataset 2 age alone separates the
# labels (positive rate ~67% at 18-19, ~0% at 50+), a likely synthetic artefact.
MOOD_WORKING_FEATURES: tuple[FeatureSpec, ...] = tuple(s for s in MOOD_FEATURES if s.name != "age")

# What the app may send for the working segment: the same check-in payload,
# with age optional. If present it is validated, then ignored.
MOOD_WORKING_INPUTS: tuple[FeatureSpec, ...] = (
    replace(MOOD_FEATURES[0], required=False),
    *MOOD_WORKING_FEATURES,
)
assert MOOD_FEATURES[0].name == "age"

SLEEP_CORE_FEATURES: tuple[FeatureSpec, ...] = (
    FeatureSpec("age", "int", True, "Age", 16, 90),
    FeatureSpec("sleep_duration", "float", True, "Sleep duration (h)", 2, 14),
    FeatureSpec("quality_of_sleep", "int", True, "Sleep quality", 1, 10),
    FeatureSpec("stress_level", "int", True, "Stress level", 1, 10),
    FeatureSpec("physical_activity_minutes", "int", True, "Physical activity (min/day)", 0, 600),
)

SLEEP_BOOSTER_FEATURES: tuple[FeatureSpec, ...] = (
    FeatureSpec("bmi_category", "ordinal", False, "BMI category", choices=BMI_LEVELS),
    FeatureSpec("heart_rate", "int", False, "Resting heart rate", 30, 200),
    FeatureSpec("daily_steps", "int", False, "Daily steps", 0, 50000),
)

SLEEP_EXTENDED_FEATURES: tuple[FeatureSpec, ...] = SLEEP_CORE_FEATURES + SLEEP_BOOSTER_FEATURES

MOOD_SEGMENTS: tuple[str, ...] = ("student", "working", "both")
SLEEP_CLASSES: tuple[str, ...] = ("None", "Insomnia", "Sleep Apnea")


def sleep_band_from_hours(hours: float) -> str:
    """Map nightly sleep hours to the mood surveys' sleep-duration band.

    The survey options were "Less than 5", "5-6", "7-8" and "More than 8"
    hours, leaving 6-7 h unassigned; it goes to "5-6" because "7-8" implies
    at least 7 hours.
    """
    if hours < 5:
        return "<5"
    if hours < 7:
        return "5-6"
    if hours <= 8:
        return "7-8"
    return ">8"


def feature_names(specs: tuple[FeatureSpec, ...]) -> list[str]:
    """Column order for a feature set."""
    return [s.name for s in specs]


def validate(inputs: Mapping[str, Any], specs: tuple[FeatureSpec, ...]) -> dict[str, float | None]:
    """Validate raw inputs against ``specs``.

    Returns a dict of encoded values in spec order, with ``None`` for missing
    optional inputs. Raises :class:`SchemaError` on unknown fields, missing
    required fields, wrong types or out-of-range values.
    """
    if not isinstance(inputs, Mapping):
        raise SchemaError("inputs must be a mapping")
    known = {s.name for s in specs}
    unknown = sorted(set(inputs) - known)
    if unknown:
        raise SchemaError(f"unknown input(s): {unknown}")
    out: dict[str, float | None] = {}
    for spec in specs:
        raw = inputs.get(spec.name)
        if raw is None:
            if spec.required:
                raise SchemaError(f"{spec.name}: required")
            out[spec.name] = None
        else:
            out[spec.name] = spec.encode(raw)
    return out
