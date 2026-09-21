"""JSON-over-HTTP transport for the sandbox worker."""

from __future__ import annotations

import json
import hmac
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Protocol

from .contracts import (
    ContractError,
    SandboxExecutionRequest,
    SandboxExecutionResponse,
    request_from_dict,
    response_to_dict,
)
from .telemetry import execute_tool_span


class SandboxExecutionEngine(Protocol):
    def capabilities(self) -> dict[str, object]: ...

    def execute(self, request: SandboxExecutionRequest) -> SandboxExecutionResponse: ...


class SandboxExecutionRequestHandler(BaseHTTPRequestHandler):
    executor: SandboxExecutionEngine
    internal_token: str

    server_version = "SpaceAgentSandboxWorker/0.1"

    def do_GET(self) -> None:
        if self.path.rstrip("/") == "/health":
            capabilities = getattr(self.executor, "capabilities", None)
            payload = capabilities() if callable(capabilities) else {"status": "ok"}
            self._send_json(HTTPStatus.OK, payload)
            return
        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path.rstrip("/") != "/execute":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        if not self._authorized():
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        try:
            request = request_from_dict(self._read_json())
            with execute_tool_span(dict(self.headers.items()), request.tool):
                response = self.executor.execute(request)
            self._send_json(HTTPStatus.OK, response_to_dict(response))
        except (ContractError, ValueError) as error:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid request"},
            )
        except Exception:  # noqa: BLE001 - transport must not leak stack traces
            self._send_json(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {"error": "execution failed"},
            )

    def _authorized(self) -> bool:
        supplied = self.headers.get("Authorization", "")
        expected = "Bearer " + self.internal_token
        return bool(self.internal_token) and hmac.compare_digest(supplied, expected)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            raise ContractError("missing request body")
        if length > 1024 * 1024:
            raise ContractError("request body too large")
        data = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(data, dict):
            raise ContractError("request body must be a JSON object")
        return data

    def _send_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        return


def make_server(
    executor: SandboxExecutionEngine,
    host: str = "127.0.0.1",
    port: int = 9200,
    internal_token: str = "",
) -> ThreadingHTTPServer:
    handler = type(
        "ConfiguredSandboxExecutionRequestHandler",
        (SandboxExecutionRequestHandler,),
        {"executor": executor, "internal_token": internal_token},
    )
    server = ThreadingHTTPServer((host, port), handler)
    server.daemon_threads = True
    return server
