"""Dataset discovery, cleaning and feature mapping for the wellness models.

Files are identified by their columns, never by filename. Every cleaning
rule that drops rows is recorded in a :class:`CleaningLog`, so row counts
before and after cleaning end up in the model cards.
"""

from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from .config import FORBIDDEN_COLUMNS
from .schema import (
    BMI_LEVELS,
    DIET_LEVELS,
    MOOD_FEATURES,
    SLEEP_BANDS,
    SLEEP_CLASSES,
    SLEEP_EXTENDED_FEATURES,
    feature_names,
)

log = logging.getLogger(__name__)

TARGET = "target"

# Column signatures (subsets that must be present).
_DEPRESSION_COLS = {
    "Sleep Duration",
    "Dietary Habits",
    "Work/Study Hours",
    "Financial Stress",
    "Family History of Mental Illness",
    "Depression",
}
_SIG_STUDENT_DEPRESSION = _DEPRESSION_COLS | {"Profession", "Academic Pressure", "Study Satisfaction"}
_SIG_FINAL_DEPRESSION = _DEPRESSION_COLS | {"Working Professional or Student", "Work Pressure", "Job Satisfaction"}
_SIG_SLEEP = {"Sleep Duration", "Quality of Sleep", "Stress Level", "Physical Activity Level", "Sleep Disorder"}

ROLE_STUDENT = "student_depression"
ROLE_FINAL = "final_depression"
ROLE_SLEEP = "sleep_health"

_SLEEP_DURATION_MAP = {
    "Less than 5 hours": SLEEP_BANDS.index("<5"),
    "5-6 hours": SLEEP_BANDS.index("5-6"),
    "7-8 hours": SLEEP_BANDS.index("7-8"),
    "More than 8 hours": SLEEP_BANDS.index(">8"),
}
_DIET_MAP = {"Unhealthy": DIET_LEVELS.index("unhealthy"), "Moderate": DIET_LEVELS.index("moderate"),
             "Healthy": DIET_LEVELS.index("healthy")}
_BMI_MAP = {"Normal": BMI_LEVELS.index("normal"), "Normal Weight": BMI_LEVELS.index("normal"),
            "Overweight": BMI_LEVELS.index("overweight"), "Obese": BMI_LEVELS.index("obese")}
_YES_NO = {"Yes": 1, "No": 0}

# Raw source columns read into features (plus row filter and label), per
# model. Used by the model cards and by the forbidden-column test.
_DEP_COMMON = ["Sleep Duration", "Dietary Habits", "Work/Study Hours", "Financial Stress",
               "Family History of Mental Illness"]
SOURCE_COLUMNS: dict[str, dict[str, list[str]]] = {
    "mood_student": {
        "features": ["Age"] + _DEP_COMMON + ["Academic Pressure", "Study Satisfaction"],
        "row_filter": ["Profession"],
        "label": ["Depression"],
    },
    "mood_working": {
        "features": _DEP_COMMON + ["Work Pressure", "Job Satisfaction"],
        "row_filter": ["Working Professional or Student", "Age"],  # Age: validity check only, not a feature
        "label": ["Depression"],
    },
    "sleep_pattern": {
        "features": ["Age", "Sleep Duration", "Quality of Sleep", "Stress Level", "Physical Activity Level",
                     "BMI Category", "Heart Rate", "Daily Steps"],
        "row_filter": [],
        "label": ["Sleep Disorder"],
    },
}


@dataclass
class CleaningLog:
    """Ordered record of cleaning steps and how many rows each dropped."""

    source_rows: int
    steps: list[dict[str, object]] = field(default_factory=list)

    def drop(self, df: pd.DataFrame, mask: pd.Series, rule: str) -> pd.DataFrame:
        """Drop rows where ``mask`` is True and record the count."""
        n = int(mask.sum())
        self.steps.append({"rule": rule, "rows_dropped": n})
        log.info("%-70s dropped %d", rule, n)
        return df.loc[~mask]

    def note(self, rule: str, rows_dropped: int) -> None:
        self.steps.append({"rule": rule, "rows_dropped": int(rows_dropped)})
        log.info("%-70s dropped %d", rule, rows_dropped)

    def to_card(self, final_rows: int) -> dict[str, object]:
        return {"rows_before": self.source_rows, "rows_after": final_rows, "steps": self.steps}


@dataclass
class CleanDataset:
    """Model-ready data: canonical feature columns plus ``target``."""

    frame: pd.DataFrame
    source_file: Path
    source_sha256: str
    cleaning: CleaningLog
    label_mapping: dict[str, int]

    @property
    def X(self) -> pd.DataFrame:
        return self.frame.drop(columns=[TARGET])

    @property
    def y(self) -> np.ndarray:
        return self.frame[TARGET].to_numpy()


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def discover_datasets(raw_dir: Path) -> dict[str, Path]:
    """Map each dataset role to a CSV in ``raw_dir`` by column signature.

    CSVs that match no signature (e.g. the out-of-scope emotion text files)
    are ignored. Raises if a role is missing or matched by more than one file.
    """
    found: dict[str, list[Path]] = {ROLE_STUDENT: [], ROLE_FINAL: [], ROLE_SLEEP: []}
    for path in sorted(raw_dir.glob("*.csv")):
        cols = set(pd.read_csv(path, nrows=0).columns.str.strip())
        if _SIG_SLEEP <= cols:
            found[ROLE_SLEEP].append(path)
        elif _SIG_FINAL_DEPRESSION <= cols:
            found[ROLE_FINAL].append(path)
        elif _SIG_STUDENT_DEPRESSION <= cols:
            found[ROLE_STUDENT].append(path)
        else:
            log.info("ignoring %s (no matching column signature)", path.name)
    out: dict[str, Path] = {}
    for role, paths in found.items():
        if len(paths) != 1:
            raise FileNotFoundError(f"expected exactly one CSV for {role} in {raw_dir}, found {[p.name for p in paths]}")
        out[role] = paths[0]
    return out


def _strip_object_columns(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df.columns = df.columns.str.strip()
    for c in df.select_dtypes(include="object").columns:
        df[c] = df[c].str.strip()
    return df


def _clean_depression_common(
    df: pd.DataFrame, cleaning: CleaningLog, pressure_col: str, satisfaction_col: str
) -> pd.DataFrame:
    """Shared cleaning for both depression files; returns canonical columns."""
    placeholders = {"Others", "?", ""}

    for col in ("Sleep Duration", "Dietary Habits"):
        df = cleaning.drop(df, df[col].isna() | df[col].isin(placeholders), f"{col}: placeholder ('Others', '?', blank, NaN)")
    df = cleaning.drop(df, ~df["Sleep Duration"].isin(_SLEEP_DURATION_MAP.keys()), "Sleep Duration: unrecognised value")
    df = cleaning.drop(df, ~df["Dietary Habits"].isin(_DIET_MAP.keys()), "Dietary Habits: unrecognised value")

    fin = pd.to_numeric(df["Financial Stress"].replace(list(placeholders), np.nan), errors="coerce")
    df = cleaning.drop(df, fin.isna(), "Financial Stress: placeholder / non-numeric / blank")
    fin = fin.loc[df.index]
    df = cleaning.drop(df, ~fin.between(1, 5), "Financial Stress: outside 1-5")

    for col, rule_name in ((pressure_col, "pressure"), (satisfaction_col, "satisfaction")):
        v = pd.to_numeric(df[col], errors="coerce")
        df = cleaning.drop(df, ~v.between(1, 5), f"{col} ({rule_name}): missing or outside 1-5 (e.g. 0)")

    hours = pd.to_numeric(df["Work/Study Hours"], errors="coerce")
    df = cleaning.drop(df, ~hours.between(0, 16), "Work/Study Hours: missing or outside 0-16")
    age = pd.to_numeric(df["Age"], errors="coerce")
    df = cleaning.drop(df, ~age.between(16, 80), "Age: missing or outside 16-80")
    df = cleaning.drop(df, ~df["Family History of Mental Illness"].isin(_YES_NO.keys()), "Family History: not Yes/No")

    out = pd.DataFrame(
        {
            "age": pd.to_numeric(df["Age"]).astype(float),
            "pressure": pd.to_numeric(df[pressure_col]).astype(float),
            "satisfaction": pd.to_numeric(df[satisfaction_col]).astype(float),
            "sleep_duration": df["Sleep Duration"].map(_SLEEP_DURATION_MAP).astype(float),
            "financial_stress": pd.to_numeric(df["Financial Stress"]).astype(float),
            "dietary_habits": df["Dietary Habits"].map(_DIET_MAP).astype(float),
            "work_study_hours": pd.to_numeric(df["Work/Study Hours"]).astype(float),
            "family_history": df["Family History of Mental Illness"].map(_YES_NO).astype(float),
        },
        index=df.index,
    )
    assert list(out.columns) == feature_names(MOOD_FEATURES)
    return out


def _depression_target(series: pd.Series) -> pd.Series:
    """Accepts 0/1 or Yes/No labels."""
    mapped = series.astype(str).str.strip().map({"1": 1, "0": 0, "Yes": 1, "No": 0, "1.0": 1, "0.0": 0})
    if mapped.isna().any():
        raise ValueError(f"unexpected Depression labels: {series[mapped.isna()].unique()[:5]}")
    return mapped.astype(int)


def load_student_mood(path: Path) -> CleanDataset:
    """Dataset 1, 'Student' rows only -> mood_student training data."""
    raw = _strip_object_columns(pd.read_csv(path))
    cleaning = CleaningLog(source_rows=len(raw))
    df = cleaning.drop(raw, raw["Profession"] != "Student", "Profession != 'Student'")
    feats = _clean_depression_common(df, cleaning, "Academic Pressure", "Study Satisfaction")
    feats[TARGET] = _depression_target(df.loc[feats.index, "Depression"])
    return CleanDataset(feats.reset_index(drop=True), path, sha256_of(path), cleaning, {"not_depressed": 0, "depressed": 1})


def load_working_mood(path: Path) -> CleanDataset:
    """Dataset 2, 'Working Professional' rows only -> mood_working training data."""
    raw = _strip_object_columns(pd.read_csv(path))
    cleaning = CleaningLog(source_rows=len(raw))
    df = cleaning.drop(
        raw, raw["Working Professional or Student"] != "Working Professional", "segment != 'Working Professional'"
    )
    feats = _clean_depression_common(df, cleaning, "Work Pressure", "Job Satisfaction")
    feats[TARGET] = _depression_target(df.loc[feats.index, "Depression"])
    return CleanDataset(feats.reset_index(drop=True), path, sha256_of(path), cleaning, {"not_depressed": 0, "depressed": 1})


def load_sleep(path: Path) -> CleanDataset:
    """Sleep health data: drop Person ID, deduplicate exact rows, map features.

    Deduplication happens here, before any split. Rows that are identical in
    the *model* features but differ elsewhere (gender, occupation...) or in
    the label are kept; the trainer groups them so they never straddle folds.
    """
    # keep_default_na=False: the label "None" must not become NaN.
    raw = _strip_object_columns(pd.read_csv(path, keep_default_na=False))
    cleaning = CleaningLog(source_rows=len(raw))
    df = raw.drop(columns=["Person ID"])
    df = cleaning.drop(df, df.duplicated(keep="first"), "exact duplicate rows (after dropping Person ID)")
    df = cleaning.drop(df, ~df["Sleep Disorder"].isin(SLEEP_CLASSES), "Sleep Disorder: unrecognised label")
    df = cleaning.drop(df, ~df["BMI Category"].isin(_BMI_MAP.keys()), "BMI Category: unrecognised value")

    out = pd.DataFrame(
        {
            "age": pd.to_numeric(df["Age"]).astype(float),
            "sleep_duration": pd.to_numeric(df["Sleep Duration"]).astype(float),
            "quality_of_sleep": pd.to_numeric(df["Quality of Sleep"]).astype(float),
            "stress_level": pd.to_numeric(df["Stress Level"]).astype(float),
            "physical_activity_minutes": pd.to_numeric(df["Physical Activity Level"]).astype(float),
            "bmi_category": df["BMI Category"].map(_BMI_MAP).astype(float),
            "heart_rate": pd.to_numeric(df["Heart Rate"]).astype(float),
            "daily_steps": pd.to_numeric(df["Daily Steps"]).astype(float),
        },
        index=df.index,
    )
    bad = out.isna().any(axis=1)
    if bad.any():
        cleaning.note("non-numeric feature values", int(bad.sum()))
        out = out.loc[~bad]
    assert list(out.columns) == feature_names(SLEEP_EXTENDED_FEATURES)
    mapping = {name: i for i, name in enumerate(SLEEP_CLASSES)}
    out[TARGET] = df.loc[out.index, "Sleep Disorder"].map(mapping).astype(int)
    return CleanDataset(out.reset_index(drop=True), path, sha256_of(path), cleaning, mapping)


def feature_groups(X: pd.DataFrame) -> np.ndarray:
    """Group id per row = identity of its feature tuple (for grouped CV)."""
    return X.astype(str).agg("|".join, axis=1).factorize()[0]


def label_conflict_rate(X: pd.DataFrame, y: np.ndarray) -> float:
    """Share of rows a perfect lookup table would still get wrong.

    Rows with identical features but different labels cannot all be
    predicted correctly; ``1 - rate`` is the accuracy ceiling.
    """
    g = feature_groups(X)
    counts = pd.crosstab(g, y)
    return float(1.0 - counts.max(axis=1).sum() / len(y))


def assert_no_forbidden(columns: list[str]) -> None:
    """Fail loudly if any forbidden source column would reach a model."""
    bad = FORBIDDEN_COLUMNS.intersection(columns)
    if bad:
        raise AssertionError(f"forbidden column(s) used as features: {sorted(bad)}")
