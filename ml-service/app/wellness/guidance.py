"""Rule-based sleep guidance from adult guidelines (no ML).

Used as the fallback when the sleep-pattern model cannot be applied, and
callable on its own. Thresholds and wording live in :mod:`display_config`.
"""

from __future__ import annotations

from typing import Any

from . import display_config as C
from .schema import SLEEP_CORE_FEATURES

_SPECS = {s.name: s for s in SLEEP_CORE_FEATURES}


def sleep_guidance(sleep_hours: float, quality: int, activity_minutes: float) -> dict[str, Any]:
    """Guideline-based messages for sleep duration, quality and activity.

    Args:
        sleep_hours: Typical hours of sleep per night (2-14).
        quality: Self-rated sleep quality, 1-10.
        activity_minutes: Physical activity in minutes **per day** (0-600),
            the same unit as the check-in; compared with 150 min/week.

    Returns:
        ``{"messages": [{"code", "text"}], "meets_sleep_guideline",
        "meets_activity_guideline", "weekly_activity_minutes", "source"}``.

    Raises:
        SchemaError: any input of the wrong type or out of range.
    """
    hours = _SPECS["sleep_duration"].encode(sleep_hours)
    q = int(_SPECS["quality_of_sleep"].encode(quality))
    weekly = round(_SPECS["physical_activity_minutes"].encode(activity_minutes) * 7, 1)

    msgs: list[dict[str, str]] = []

    def add(code: str, **fmt: Any) -> None:
        msgs.append({"code": code, "text": C.GUIDANCE_MESSAGES[code].format(**fmt)})

    if hours < C.GUIDELINE_SLEEP_HOURS_MIN:
        add("sleep_short", hours=hours)
    elif hours > C.GUIDELINE_SLEEP_HOURS_LONG:
        add("sleep_long", hours=hours)
    else:
        add("sleep_ok", hours=hours)
    if q <= C.LOW_SLEEP_QUALITY_AT_OR_BELOW:
        add("quality_low", quality=q)
    if weekly < C.GUIDELINE_ACTIVITY_MIN_PER_WEEK:
        add("activity_low", weekly=weekly)
    else:
        add("activity_ok", weekly=weekly)

    return {
        "messages": msgs,
        "meets_sleep_guideline": hours >= C.GUIDELINE_SLEEP_HOURS_MIN,
        "meets_activity_guideline": weekly >= C.GUIDELINE_ACTIVITY_MIN_PER_WEEK,
        "weekly_activity_minutes": weekly,
        "source": C.GUIDANCE_SOURCE,
    }
