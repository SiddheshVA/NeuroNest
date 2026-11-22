"""Single source of truth for every user-facing label and message.

Edit wording here only; the predictor and guidance modules read from this
file. Raw dataset class names ("Insomnia", "Sleep Apnea") never leave the ML
service: they are mapped to neutral codes and display text below.
"""

from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping


@dataclass(frozen=True)
class SleepLabel:
    """How one sleep-model class is presented to the user."""

    code: str           # stable API code
    short_label: str    # used inside driver sentences
    display_text: str   # one-line message for the result card
    see_doctor: bool    # whether to suggest talking to a doctor


# Keyed by the dataset class name the model was trained on.
SLEEP_LABELS: Mapping[str, SleepLabel] = MappingProxyType(
    {
        "None": SleepLabel(
            code="none",
            short_label="no particular sleep pattern",
            display_text="Your answers don't resemble a specific sleep-problem pattern.",
            see_doctor=False,
        ),
        "Insomnia": SleepLabel(
            code="insomnia_like",
            short_label="insomnia-like pattern",
            display_text=(
                "Your answers resemble a difficulty-sleeping (insomnia-like) pattern. "
                "If poor sleep persists for several weeks, consider talking to a doctor."
            ),
            see_doctor=True,
        ),
        "Sleep Apnea": SleepLabel(
            code="apnea_like",
            short_label="apnea-like pattern",
            display_text=(
                "Your answers resemble a pattern sometimes linked to breathing-related sleep problems "
                "(apnea-like). Loud snoring, gasping at night or daytime sleepiness are worth checking with a doctor."
            ),
            see_doctor=True,
        ),
    }
)

DISCLAIMER = (
    "This is an estimate based on a few self-reported answers, not a diagnosis. "
    "If you are struggling, please talk to a professional."
)

# Shown instead of a sleep pattern when inputs fall outside the training data.
SLEEP_OUT_OF_RANGE_REASON = (
    "The sleep-pattern model was built from adults aged {age_lo:g}-{age_hi:g} sleeping {sleep_lo:g}-{sleep_hi:g} hours, "
    "so it can't give a reliable pattern for your answers ({fields}). General sleep guidance is shown instead."
)

# --- Rule-based sleep guidance (adult guidelines; no ML) -------------------
GUIDELINE_SLEEP_HOURS_MIN = 7.0          # adults: at least 7 h per night
GUIDELINE_SLEEP_HOURS_LONG = 9.0         # regularly above this: worth noting
GUIDELINE_ACTIVITY_MIN_PER_WEEK = 150.0  # moderate activity, minutes per week
LOW_SLEEP_QUALITY_AT_OR_BELOW = 4        # on the 1-10 check-in scale

GUIDANCE_MESSAGES: Mapping[str, str] = MappingProxyType(
    {
        "sleep_short": (
            "You're sleeping about {hours:g} hours. Adults are generally advised to get at least 7 hours a night; "
            "a consistent bedtime and less screen time before bed can help."
        ),
        "sleep_ok": "You're getting about {hours:g} hours of sleep, in line with the 7+ hours advised for adults.",
        "sleep_long": (
            "You're sleeping about {hours:g} hours. If you regularly need more than 9 hours and still feel tired, "
            "it may be worth mentioning to a doctor."
        ),
        "quality_low": (
            "You rated your sleep quality {quality}/10. If restless or unrefreshing sleep continues for a few weeks, "
            "consider talking to a doctor."
        ),
        "activity_low": (
            "About {weekly:g} minutes of activity a week is below the 150 minutes advised for adults. "
            "Short daily walks add up and can also help sleep."
        ),
        "activity_ok": "About {weekly:g} minutes of activity a week meets the 150-minute adult guideline.",
    }
)
GUIDANCE_SOURCE = "General adult guidelines: 7+ hours of sleep per night; 150 minutes of moderate activity per week."
