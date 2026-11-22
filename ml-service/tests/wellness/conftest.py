"""Shared fixtures: synthetic raw CSVs shaped like the real sources."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
import pytest

SUICIDAL = "Have you ever had suicidal thoughts ?"


def _depression_common(rng: np.random.Generator, n: int) -> dict[str, object]:
    return {
        "Gender": rng.choice(["Male", "Female"], n),
        "Age": rng.integers(18, 40, n),
        "City": rng.choice(["Pune", "Delhi"], n),
        "CGPA": rng.uniform(5, 10, n).round(2),
        "Sleep Duration": rng.choice(["Less than 5 hours", "5-6 hours", "7-8 hours", "More than 8 hours"], n),
        "Dietary Habits": rng.choice(["Unhealthy", "Moderate", "Healthy"], n),
        "Degree": rng.choice(["BSc", "MA"], n),
        SUICIDAL: rng.choice(["Yes", "No"], n),
        "Work/Study Hours": rng.integers(0, 13, n),
        "Financial Stress": rng.integers(1, 6, n).astype(float),
        "Family History of Mental Illness": rng.choice(["Yes", "No"], n),
    }


def make_student_csv(path: Path, n: int = 60, seed: int = 0) -> Path:
    rng = np.random.default_rng(seed)
    df = pd.DataFrame(
        {
            "id": np.arange(n),
            **_depression_common(rng, n),
            "Profession": ["Student"] * (n - 2) + ["Teacher", "Chef"],
            "Academic Pressure": rng.integers(1, 6, n).astype(float),
            "Work Pressure": 0.0,
            "Study Satisfaction": rng.integers(1, 6, n).astype(float),
            "Job Satisfaction": 0.0,
            "Depression": rng.integers(0, 2, n),
        }
    )
    df.loc[0, "Sleep Duration"] = "Others"
    df.loc[1, "Dietary Habits"] = "Others"
    df.loc[2, "Financial Stress"] = np.nan
    df.loc[3, "Academic Pressure"] = 0.0
    df.to_csv(path, index=False)
    return path


def make_final_csv(path: Path, n: int = 60, seed: int = 1) -> Path:
    rng = np.random.default_rng(seed)
    worker = np.array(["Working Professional"] * (n - 10) + ["Student"] * 10)
    df = pd.DataFrame(
        {
            "Name": rng.choice(["A", "B"], n),
            **_depression_common(rng, n),
            "Working Professional or Student": worker,
            "Profession": rng.choice(["Teacher", "Chef"], n),
            "Academic Pressure": np.where(worker == "Student", 3.0, np.nan),
            "Work Pressure": np.where(worker == "Student", np.nan, rng.integers(1, 6, n).astype(float)),
            "Study Satisfaction": np.where(worker == "Student", 3.0, np.nan),
            "Job Satisfaction": np.where(worker == "Student", np.nan, rng.integers(1, 6, n).astype(float)),
            "Depression": rng.choice(["Yes", "No"], n),
        }
    )
    df.to_csv(path, index=False)
    return path


def make_sleep_csv(path: Path, n: int = 40, seed: int = 2) -> Path:
    rng = np.random.default_rng(seed)
    df = pd.DataFrame(
        {
            "Person ID": np.arange(1, n + 1),
            "Gender": rng.choice(["Male", "Female"], n),
            "Age": rng.integers(27, 60, n),
            "Occupation": rng.choice(["Nurse", "Doctor"], n),
            "Sleep Duration": rng.uniform(5.8, 8.5, n).round(1),
            "Quality of Sleep": rng.integers(4, 10, n),
            "Physical Activity Level": rng.integers(30, 91, n),
            "Stress Level": rng.integers(3, 9, n),
            "BMI Category": rng.choice(["Normal", "Normal Weight", "Overweight", "Obese"], n),
            "Blood Pressure": "120/80",
            "Heart Rate": rng.integers(65, 87, n),
            "Daily Steps": rng.integers(3000, 10001, n),
            "Sleep Disorder": rng.choice(["None", "Insomnia", "Sleep Apnea"], n),
        }
    )
    # Exact duplicate of row 0 except Person ID (must be removed).
    dup = df.iloc[[0]].copy()
    dup["Person ID"] = n + 1
    df = pd.concat([df, dup], ignore_index=True)
    df.to_csv(path, index=False)
    return path


@pytest.fixture()
def raw_dir(tmp_path: Path) -> Path:
    d = tmp_path / "raw"
    d.mkdir()
    make_student_csv(d / "a.csv")
    make_final_csv(d / "b.csv")
    make_sleep_csv(d / "c.csv")
    pd.DataFrame({"text": ["i feel fine"], "label": [1]}).to_csv(d / "training.csv", index=False)
    return d


STUDENT_FULL = {
    "age": 21,
    "pressure": 4,
    "satisfaction": 2,
    "sleep_duration": "5-6",
    "financial_stress": 3,
    "dietary_habits": "moderate",
    "work_study_hours": 8,
    "family_history": False,
}
WORKING_FULL = {**STUDENT_FULL, "age": 32}
SLEEP_CORE = {
    "age": 40,
    "sleep_duration": 6.5,
    "quality_of_sleep": 6,
    "stress_level": 6,
    "physical_activity_minutes": 45,
}
SLEEP_FULL = {**SLEEP_CORE, "bmi_category": "overweight", "heart_rate": 75, "daily_steps": 6000}
