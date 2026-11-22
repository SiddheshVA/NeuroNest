"""Fixtures for API tests: an app with a known key and models loaded."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.api.settings import Settings
from app.main import create_app

TEST_KEY = "test-internal-key-0123456789"
AUTH = {"X-Internal-Key": TEST_KEY}
URL = "/v1/wellness/checkin"

# Required inputs only (in sleep training range: age 27-59, 5.8-8.5 h).
REQUIRED = {
    "segment": "student",
    "age": 30,
    "sleep_duration": 6.5,
    "quality_of_sleep": 6,
    "stress_level": 6,
    "physical_activity_minutes": 45,
    "pressure": 4,
    "satisfaction": 2,
    "financial_stress": 3,
}
FULL = {
    **REQUIRED,
    "dietary_habits": "moderate",
    "work_study_hours": 8,
    "family_history": False,
    "bmi_category": "normal",
    "heart_rate": 70,
    "daily_steps": 7000,
}


@pytest.fixture(scope="module")
def client() -> Iterator[TestClient]:
    with TestClient(create_app(Settings(internal_key=TEST_KEY))) as c:
        yield c
