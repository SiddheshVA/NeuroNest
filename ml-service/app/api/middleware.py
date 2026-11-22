"""Request id, access log and last-resort error handling.

Privacy: the access log records only request id, method, path, status and
latency. Request bodies, inputs and predictions are never logged; for
unhandled errors only the exception *class name* is logged, because
exception messages can contain input values.
"""

from __future__ import annotations

import logging
import re
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from .errors import error_response
from .settings import REQUEST_ID_HEADER

log = logging.getLogger("wellness.api")

# A caller-supplied id (e.g. from Spring Boot) is reused only if it is safe to log.
_SAFE_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


class RequestContextMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        incoming = request.headers.get(REQUEST_ID_HEADER, "")
        rid = incoming if _SAFE_ID.match(incoming) else uuid.uuid4().hex
        request.state.request_id = rid
        start = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception as exc:  # noqa: BLE001 - last resort; never leak details
            log.error("request_id=%s unhandled_error=%s", rid, type(exc).__name__)
            response = error_response(request, 500, "internal_error", "Internal server error.")
        response.headers[REQUEST_ID_HEADER] = rid
        latency_ms = (time.perf_counter() - start) * 1000
        log.info(
            "request_id=%s method=%s path=%s status=%d latency_ms=%.1f",
            rid, request.method, request.url.path, response.status_code, latency_ms,
        )
        return response
