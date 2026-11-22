"""FastAPI app for the NeuroNest ML service (internal; called by Spring Boot only).

Run locally (from ``ml-service/``)::

    uvicorn app.main:app --host 127.0.0.1 --port 8001 --no-access-log

Configuration comes from environment variables; see ``.env.example``.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .api.errors import install_error_handlers
from .api.middleware import RequestContextMiddleware
from .api.routes import health_router, v1
from .api.service import ModelRegistry
from .api.settings import Settings

log = logging.getLogger("wellness.api")


def _configure_logging() -> None:
    if not log.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
        log.addHandler(handler)
        log.setLevel(logging.INFO)


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the app. ``settings`` defaults to :meth:`Settings.from_env` at startup."""

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        cfg = settings or Settings.from_env()
        cfg.check()  # fails startup if the internal key is missing outside dev mode
        app.state.settings = cfg
        if not cfg.auth_enabled:
            log.warning("dev mode without WELLNESS_INTERNAL_KEY: authentication is DISABLED")
        app.state.registry = ModelRegistry.load(cfg.model_dir)  # models load once, here
        log.info("models loaded: %s", ", ".join(sorted(app.state.registry.versions)))
        yield
        app.state.registry = None

    dev = settings.dev_mode if settings is not None else Settings.from_env().dev_mode
    app = FastAPI(
        title="NeuroNest ML service",
        version="1.0.0",
        lifespan=lifespan,
        docs_url="/docs" if dev else None,
        redoc_url=None,
        openapi_url="/openapi.json" if dev else None,
    )
    app.state.registry = None
    install_error_handlers(app)
    app.add_middleware(RequestContextMiddleware)
    app.include_router(health_router)
    app.include_router(v1)
    _configure_logging()
    return app


app = create_app()
