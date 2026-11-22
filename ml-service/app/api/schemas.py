"""Pydantic v2 request/response models.

The request model is *generated* from the wellness feature specs
(:mod:`app.wellness.schema`), so ranges and required/optional flags have one
source. The check-in sends each shared answer once:

* ``age`` appears in both the mood and sleep specs; the request uses the
  intersection of their ranges and is required (the sleep model needs it).
* ``sleep_duration`` is **hours per night** (sleep spec). The mood model's
  sleep band is derived from it by
  :func:`app.wellness.schema.sleep_band_from_hours`.
"""

from __future__ import annotations

import re
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, Strict, create_model, model_validator
from pydantic_core import PydanticCustomError

from app.wellness.config import FORBIDDEN_COLUMNS
from app.wellness.schema import MOOD_FEATURES, MOOD_SEGMENTS, SLEEP_EXTENDED_FEATURES, FeatureSpec

# ---------------------------------------------------------------- request --

# Mood inputs that the request does not carry directly (derived instead).
_DERIVED_MOOD_INPUTS = {"sleep_duration"}


def _normalise(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


# Sensitive / never-used columns, matched case- and punctuation-insensitively,
# plus anything mentioning suicide in any spelling.
FORBIDDEN_REQUEST_FIELDS: frozenset[str] = frozenset(
    {_normalise(c) for c in FORBIDDEN_COLUMNS} | {"suicidalthoughts", "suicidal", "sex", "personid"}
)


def is_forbidden_field(key: str) -> bool:
    n = _normalise(key)
    return n in FORBIDDEN_REQUEST_FIELDS or "suicid" in n


def _merge_specs() -> list[FeatureSpec]:
    """Union of sleep and mood specs; shared names get the stricter range."""
    merged: dict[str, FeatureSpec] = {s.name: s for s in SLEEP_EXTENDED_FEATURES}
    for spec in MOOD_FEATURES:
        if spec.name in _DERIVED_MOOD_INPUTS:
            continue
        other = merged.get(spec.name)
        if other is None:
            merged[spec.name] = spec
            continue
        if other.kind != spec.kind:
            raise TypeError(f"conflicting kinds for shared input {spec.name!r}")
        lo = max(v for v in (other.min_value, spec.min_value) if v is not None)
        hi = min(v for v in (other.max_value, spec.max_value) if v is not None)
        merged[spec.name] = FeatureSpec(
            spec.name, spec.kind, other.required or spec.required, spec.label, lo, hi, spec.choices
        )
    specs = list(merged.values())
    return [s for s in specs if s.required] + [s for s in specs if not s.required]


CHECKIN_SPECS: tuple[FeatureSpec, ...] = tuple(_merge_specs())

_DESCRIPTIONS = {"sleep_duration": "Sleep duration in hours per night"}


def _field(spec: FeatureSpec) -> tuple[Any, Any]:
    if spec.kind == "int":
        tp: Any = Annotated[int, Strict()]
    elif spec.kind == "float":
        tp = Annotated[float, Strict()]  # strict float still accepts JSON integers
    elif spec.kind == "ordinal":
        tp = Literal[spec.choices]  # type: ignore[valid-type]
    else:
        tp = Annotated[bool, Strict()]
    kwargs: dict[str, Any] = {"description": _DESCRIPTIONS.get(spec.name, spec.label)}
    if spec.kind in ("int", "float"):
        kwargs |= {"ge": spec.min_value, "le": spec.max_value}
    if spec.required:
        return tp, Field(..., **kwargs)
    return tp | None, Field(None, **kwargs)


class _CheckinBase(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    segment: Literal[MOOD_SEGMENTS] = Field(  # type: ignore[valid-type]
        ..., description="student | working | both (studying and working)"
    )

    @model_validator(mode="before")
    @classmethod
    def _reject_forbidden(cls, data: Any) -> Any:
        if isinstance(data, dict):
            bad = sorted(k for k in data if isinstance(k, str) and is_forbidden_field(k))
            if bad:
                raise PydanticCustomError(
                    "forbidden_field",
                    "Field(s) {fields} are not accepted: this service never uses sensitive or identifying data.",
                    {"fields": ", ".join(repr(b) for b in bad), "names": ",".join(bad)},
                )
        return data


CheckinRequest: type[_CheckinBase] = create_model(  # type: ignore[call-overload]
    "CheckinRequest",
    __base__=_CheckinBase,
    __doc__="One wellness check-in: mood and sleep answers in a single request.",
    **{s.name: _field(s) for s in CHECKIN_SPECS},
)

# --------------------------------------------------------------- response --


class Driver(BaseModel):
    feature: str
    label: str
    value: str | int | float | bool | None
    direction: Literal["increases", "decreases"]
    effect: str
    impact: float


class MoodResult(BaseModel):
    model: str
    segment: str
    risk_band: Literal["Low", "Moderate", "High"]
    probability: float = Field(..., description="Calibrated probability behind the band")
    confidence: Literal["low", "medium", "high"]
    confidence_reasons: list[str]
    drivers: list[Driver]
    imputed: list[str]
    ignored_inputs: list[str]
    out_of_range: list[str]


class GuidanceMessage(BaseModel):
    code: str
    text: str


class Guidance(BaseModel):
    messages: list[GuidanceMessage]
    meets_sleep_guideline: bool
    meets_activity_guideline: bool
    weekly_activity_minutes: float
    source: str


class SleepResult(BaseModel):
    model: str | None
    in_range: bool
    reason: str | None
    pattern_code: Literal["none", "insomnia_like", "apnea_like"] | None
    display_text: str | None
    see_doctor: bool | None
    probability: float | None
    probabilities: dict[str, float] | None
    probabilities_calibrated: bool | None
    confidence: Literal["low", "medium", "high"] | None
    confidence_reasons: list[str]
    drivers: list[Driver]
    imputed: list[str]
    out_of_range: list[str]
    guidance: Guidance


class ModelVersion(BaseModel):
    version: str = Field(..., description="<training time, YYYYMMDDTHHMMSSZ>-<first 12 hex of the joblib sha256>")
    trained_at_utc: str
    algorithm: str
    sha256: str


class CheckinResponse(BaseModel):
    request_id: str
    mood: MoodResult
    sleep: SleepResult
    model_versions: dict[str, ModelVersion]
    disclaimer: str


class HealthResponse(BaseModel):
    status: Literal["ok", "starting"]
    loaded: bool
    model_versions: dict[str, ModelVersion]


class ErrorField(BaseModel):
    field: str | None
    message: str
    type: str


class ErrorBody(BaseModel):
    code: str
    message: str
    request_id: str
    fields: list[ErrorField] | None = None


class ErrorResponse(BaseModel):
    error: ErrorBody
