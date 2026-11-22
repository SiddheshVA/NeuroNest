"""Data rules: forbidden columns never used, cleaning and dedup behave."""

from __future__ import annotations

import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import pandas.testing as pdt
import pytest

from app.wellness import data as D
from app.wellness.config import FORBIDDEN_COLUMNS, MODEL_DIR

from .conftest import SUICIDAL

MODEL_NAMES = ["mood_student", "mood_working", "sleep_pattern_core", "sleep_pattern_extended"]


@pytest.mark.parametrize("name", MODEL_NAMES)
def test_saved_models_use_no_forbidden_column(name: str) -> None:
    bundle = joblib.load(MODEL_DIR / f"{name}.joblib")
    card = json.loads((MODEL_DIR / f"{name}.model_card.json").read_text(encoding="utf-8"))
    used = set(bundle["features"]) | set(card["dataset"]["source_columns_used"]["features"])
    assert not used & FORBIDDEN_COLUMNS
    assert not any("suicid" in c.lower() for c in used)
    # The fitted estimator saw exactly the schema features.
    assert list(bundle["model"].feature_names_in_) == bundle["features"]


def test_forbidden_list_covers_the_required_columns() -> None:
    required = {SUICIDAL, "Gender", "City", "Degree", "Name", "id", "Profession", "CGPA"}
    assert required <= FORBIDDEN_COLUMNS
    with pytest.raises(AssertionError):
        D.assert_no_forbidden(["age", SUICIDAL])


def _scramble(path: Path, columns: list[str], seed: int) -> None:
    """Overwrite ``columns`` with shuffled / junk values (row filters kept)."""
    df = pd.read_csv(path, keep_default_na=False)
    rng = np.random.default_rng(seed)
    for c in columns:
        if c in df.columns:
            df[c] = rng.permutation(df[c].to_numpy())
    df.to_csv(path, index=False)


@pytest.mark.parametrize(
    "loader, role, junk",
    [
        (D.load_student_mood, D.ROLE_STUDENT, [SUICIDAL, "Gender", "City", "Degree", "id", "CGPA", "Work Pressure"]),
        (D.load_working_mood, D.ROLE_FINAL, [SUICIDAL, "Gender", "City", "Degree", "Name", "Profession", "CGPA"]),
        (D.load_sleep, D.ROLE_SLEEP, ["Gender", "Occupation", "Blood Pressure"]),
    ],
)
def test_forbidden_columns_do_not_influence_training_data(raw_dir: Path, loader, role: str, junk: list[str]) -> None:
    path = D.discover_datasets(raw_dir)[role]
    before = loader(path).frame
    _scramble(path, junk, seed=3)
    after = loader(path).frame
    if role == D.ROLE_SLEEP:
        # Exact-duplicate removal looks at every column, so scrambling can change
        # which rows count as duplicates; the set of model rows must not change.
        def canon(f: pd.DataFrame) -> pd.DataFrame:
            return f.drop_duplicates().sort_values(list(f.columns)).reset_index(drop=True)

        before, after = canon(before), canon(after)
    pdt.assert_frame_equal(before, after)


def test_discovery_is_by_columns_and_ignores_emotion_text(raw_dir: Path) -> None:
    roles = D.discover_datasets(raw_dir)
    assert {p.name for p in roles.values()} == {"a.csv", "b.csv", "c.csv"}
    assert roles[D.ROLE_STUDENT].name == "a.csv"
    assert roles[D.ROLE_FINAL].name == "b.csv"
    assert roles[D.ROLE_SLEEP].name == "c.csv"


def test_depression_cleaning_logs_each_rule(raw_dir: Path) -> None:
    ds = D.load_student_mood(D.discover_datasets(raw_dir)[D.ROLE_STUDENT])
    steps = {s["rule"]: s["rows_dropped"] for s in ds.cleaning.steps}
    assert steps["Profession != 'Student'"] == 2
    assert steps["Sleep Duration: placeholder ('Others', '?', blank, NaN)"] == 1
    assert steps["Dietary Habits: placeholder ('Others', '?', blank, NaN)"] == 1
    assert steps["Financial Stress: placeholder / non-numeric / blank"] == 1
    assert steps["Academic Pressure (pressure): missing or outside 1-5 (e.g. 0)"] == 1
    assert len(ds.frame) == 60 - 6
    assert ds.frame.notna().all().all()


def test_working_loader_keeps_only_working_professionals(raw_dir: Path) -> None:
    ds = D.load_working_mood(D.discover_datasets(raw_dir)[D.ROLE_FINAL])
    assert len(ds.frame) == 50
    assert set(ds.frame["target"].unique()) <= {0, 1}


def test_sleep_keeps_none_label_and_deduplicates(raw_dir: Path) -> None:
    ds = D.load_sleep(D.discover_datasets(raw_dir)[D.ROLE_SLEEP])
    steps = {s["rule"]: s["rows_dropped"] for s in ds.cleaning.steps}
    assert steps["exact duplicate rows (after dropping Person ID)"] == 1
    assert len(ds.frame) == 40
    assert 0 in set(ds.frame["target"])  # "None" survived as a class, not NaN
    assert set(ds.frame["bmi_category"].unique()) <= {0.0, 1.0, 2.0}  # "Normal Weight" merged into Normal


def test_label_conflict_rate() -> None:
    X = pd.DataFrame({"a": [1, 1, 1, 2], "b": [0, 0, 0, 5]})
    y = np.array([0, 0, 1, 1])
    assert D.label_conflict_rate(X, y) == pytest.approx(0.25)
    groups = D.feature_groups(X)
    assert groups[0] == groups[1] == groups[2] != groups[3]
