"""Paths, seeds and constants shared by the wellness check-in models."""

from __future__ import annotations

from pathlib import Path

SERVICE_ROOT: Path = Path(__file__).resolve().parents[2]
RAW_DATA_DIR: Path = SERVICE_ROOT / "data" / "raw"
MODEL_DIR: Path = SERVICE_ROOT / "models" / "wellness"

RANDOM_SEED: int = 42
CV_FOLDS: int = 5
# Dataset 2 (working professionals) is small, so it gets repeated CV.
CV_REPEATS_SMALL: int = 5

# Prefer the simpler / more explainable model when it is within this
# margin of the best model's primary metric.
SIMPLICITY_MARGIN: float = 0.01

# Check-in segment -> (mood model, confidence cap or None).
# "both" (studying and working) has no training data of its own, so it uses
# the student model with confidence capped at medium.
MOOD_SEGMENT_ROUTING: dict[str, tuple[str, str | None]] = {
    "student": ("mood_student", None),
    "working": ("mood_working", None),  # its "medium" cap is stored in the model bundle
    "both": ("mood_student", "medium"),
}

# Hard caps applied at training time (stored in the bundle as the ceiling).
MODEL_MAX_CONFIDENCE: dict[str, str] = {
    "mood_working": "medium",            # small data; age excluded; see model card
    "sleep_pattern_core": "medium",      # synthetic, 132 unique rows
    "sleep_pattern_extended": "medium",
}

# Columns that must never reach a model, in any dataset.
FORBIDDEN_COLUMNS: frozenset[str] = frozenset(
    {
        "Have you ever had suicidal thoughts ?",
        "Gender",
        "City",
        "Degree",
        "Name",
        "id",
        "Person ID",
        "Profession",
        "Occupation",
        "CGPA",
    }
)
