"""One error format everywhere: ``{"error": {code, message, request_id}}``.

Messages never include stack traces or echoed input values.
"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.wellness.schema import SchemaError

from .settings import REQUEST_ID_HEADER

_HTTP_CODES = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    413: "payload_too_large",
    415: "unsupported_media_type",
}


def request_id_of(request: Request) -> str:
    return getattr(request.state, "request_id", "unknown")


def error_response(
    request: Request, status: int, code: str, message: str, fields: list[dict[str, Any]] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    rid = request_id_of(request)
    body: dict[str, Any] = {"code": code, "message": message, "request_id": rid}
    if fields is not None:
        body["fields"] = fields
    return JSONResponse({"error": body}, status_code=status, headers={REQUEST_ID_HEADER: rid, **(headers or {})})


def _field_name(loc: tuple[Any, ...]) -> str | None:
    parts = [str(p) for p in loc if p != "body"]
    return ".".join(parts) or None


async def _validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    fields: list[dict[str, Any]] = []
    for e in exc.errors():
        name = _field_name(tuple(e.get("loc", ())))
        if e.get("type") == "forbidden_field":  # model-level error: name the offending keys
            name = (e.get("ctx") or {}).get("names", name)
        fields.append({"field": name, "message": e.get("msg", "invalid"), "type": e.get("type", "")})
    forbidden = any(f["type"] == "forbidden_field" for f in fields)
    code = "forbidden_field" if forbidden else "validation_error"
    summary = "; ".join(f"{f['field']}: {f['message']}" if f["field"] else f["message"] for f in fields)
    return error_response(request, 422, code, f"Invalid request: {summary}", fields)


async def _schema_error_handler(request: Request, exc: SchemaError) -> JSONResponse:
    # Defensive: the request model mirrors the predictor schema, so this
    # should not trigger. The message names the field but is not logged.
    return error_response(request, 422, "validation_error", f"Invalid request: {exc}")


async def _http_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    code = _HTTP_CODES.get(exc.status_code, "http_error")
    message = exc.detail if isinstance(exc.detail, str) else code.replace("_", " ")
    return error_response(request, exc.status_code, code, message, headers=getattr(exc, "headers", None))


def install_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(RequestValidationError, _validation_handler)  # type: ignore[arg-type]
    app.add_exception_handler(SchemaError, _schema_error_handler)  # type: ignore[arg-type]
    app.add_exception_handler(StarletteHTTPException, _http_handler)  # type: ignore[arg-type]
    # Unhandled exceptions are turned into 500s by RequestContextMiddleware.
