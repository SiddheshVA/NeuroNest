"""HTTP endpoints. Thin: validate, delegate to the service, shape the response."""

from __future__ import annotations

import hmac

from fastapi import APIRouter, Depends, HTTPException, Request
from starlette.concurrency import run_in_threadpool

from .schemas import CheckinRequest, CheckinResponse, ErrorResponse, HealthResponse
from .service import ModelRegistry, run_checkin
from .settings import INTERNAL_KEY_HEADER, Settings

_ERRORS = {401: {"model": ErrorResponse}, 422: {"model": ErrorResponse}, 500: {"model": ErrorResponse}}


def require_internal_key(request: Request) -> None:
    """Reject the call unless ``X-Internal-Key`` matches the configured secret."""
    settings: Settings = request.app.state.settings
    if not settings.auth_enabled:
        return  # dev mode without a key
    supplied = request.headers.get(INTERNAL_KEY_HEADER, "")
    assert settings.internal_key is not None
    if not hmac.compare_digest(supplied.encode(), settings.internal_key.encode()):
        raise HTTPException(status_code=401, detail=f"Missing or invalid {INTERNAL_KEY_HEADER} header.")


health_router = APIRouter()
v1 = APIRouter(prefix="/v1/wellness", dependencies=[Depends(require_internal_key)], responses=_ERRORS)


@health_router.get("/health", response_model=HealthResponse, tags=["ops"])
def health(request: Request) -> HealthResponse:
    registry: ModelRegistry | None = getattr(request.app.state, "registry", None)
    return HealthResponse(
        status="ok" if registry else "starting",
        loaded=registry is not None,
        model_versions=registry.versions if registry else {},
    )


@v1.post("/checkin", response_model=CheckinResponse, tags=["wellness"])
async def checkin(body: CheckinRequest, request: Request) -> CheckinResponse:  # type: ignore[valid-type]
    """Mood risk band + sleep pattern (or guidance only) for one check-in.

    Estimates for self-reflection, never a diagnosis.
    """
    registry: ModelRegistry = request.app.state.registry
    answers = body.model_dump(exclude={"segment"})
    result = await run_in_threadpool(run_checkin, registry, body.segment, answers)
    return CheckinResponse(request_id=request.state.request_id, **result)
