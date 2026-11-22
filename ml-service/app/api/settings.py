"""Runtime settings, read from environment variables (see ``.env.example``)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from app.wellness.config import MODEL_DIR

ENV_INTERNAL_KEY = "WELLNESS_INTERNAL_KEY"
ENV_DEV_MODE = "WELLNESS_DEV_MODE"
ENV_MODEL_DIR = "WELLNESS_MODEL_DIR"
INTERNAL_KEY_HEADER = "X-Internal-Key"
REQUEST_ID_HEADER = "X-Request-ID"


class ConfigError(RuntimeError):
    """Raised at startup when required configuration is missing."""


@dataclass(frozen=True)
class Settings:
    """Service configuration.

    Attributes:
        internal_key: Shared secret expected in ``X-Internal-Key``. Required
            unless ``dev_mode`` is on; in dev mode with no key, auth is off.
        dev_mode: Local development only: allows a missing key and enables
            the interactive docs at ``/docs``.
        model_dir: Directory holding the ``*.joblib`` bundles and model cards.
    """

    internal_key: str | None
    dev_mode: bool = False
    model_dir: Path = MODEL_DIR

    @classmethod
    def from_env(cls) -> "Settings":
        key = os.environ.get(ENV_INTERNAL_KEY, "").strip() or None
        dev = os.environ.get(ENV_DEV_MODE, "0").strip().lower() in {"1", "true", "yes"}
        model_dir = Path(os.environ.get(ENV_MODEL_DIR, "").strip() or MODEL_DIR)
        return cls(internal_key=key, dev_mode=dev, model_dir=model_dir)

    def check(self) -> None:
        """Fail fast on unsafe configuration."""
        if self.internal_key is None and not self.dev_mode:
            raise ConfigError(
                f"{ENV_INTERNAL_KEY} is not set. Set it to a long random secret "
                f"(or set {ENV_DEV_MODE}=1 for local development only)."
            )
        if self.internal_key is not None and len(self.internal_key) < 16 and not self.dev_mode:
            raise ConfigError(f"{ENV_INTERNAL_KEY} must be at least 16 characters.")

    @property
    def auth_enabled(self) -> bool:
        return self.internal_key is not None
