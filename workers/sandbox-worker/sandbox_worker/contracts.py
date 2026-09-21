"""Framework-neutral sandbox execution wire models.

These dataclasses mirror ``contracts/proto/execution/v1/sandbox_execution.proto``
using the standard Protobuf JSON mapping.
"""

from __future__ import annotations

import dataclasses
import types
import typing
from typing import Any, get_args, get_origin, get_type_hints


class ContractError(ValueError):
    """Raised when a JSON document does not match the execution contract."""


def _to_dict(value: Any) -> Any:
    if dataclasses.is_dataclass(value):
        return {field.name: _to_dict(getattr(value, field.name)) for field in dataclasses.fields(value)}
    if isinstance(value, list):
        return [_to_dict(item) for item in value]
    if isinstance(value, dict):
        return {key: _to_dict(item) for key, item in value.items()}
    return value


def _from_dict(cls: type, data: Any) -> Any:
    if data is None:
        return None
    origin = get_origin(cls)
    if origin is list:
        item_type = get_args(cls)[0]
        return [_from_dict(item_type, item) for item in data]
    if origin is dict:
        return data
    if origin is typing.Union or origin is types.UnionType:
        for candidate in get_args(cls):
            if candidate is type(None):
                continue
            try:
                return _from_dict(candidate, data)
            except (ContractError, TypeError, ValueError):
                continue
        raise ContractError(f"value does not match any union member for {cls!r}")
    if not dataclasses.is_dataclass(cls):
        return data
    if not isinstance(data, dict):
        raise ContractError(f"expected object for {cls.__name__}")
    hints = get_type_hints(cls)
    kwargs: dict[str, Any] = {}
    for field in dataclasses.fields(cls):
        if field.name not in data:
            if field.default is not dataclasses.MISSING or field.default_factory is not dataclasses.MISSING:
                continue
            raise ContractError(f"missing required field {cls.__name__}.{field.name}")
        kwargs[field.name] = _from_dict(hints.get(field.name, field.type), data[field.name])
    return cls(**kwargs)


@dataclasses.dataclass
class ResourcePolicy:
    maxOutputBytes: int = 65536
    maxCpuSeconds: int = 5
    maxMemoryBytes: int = 268435456
    allowNetwork: bool = False
    allowedPaths: list[str] = dataclasses.field(default_factory=list)
    readOnlyWorkspace: bool = False


@dataclasses.dataclass
class SandboxExecutionRequest:
    executionId: str
    agentRunId: str
    toolCallId: str
    workspaceRef: str | None
    taskRef: str | None
    tool: str
    command: str
    arguments: list[str]
    timeoutSeconds: int
    resourcePolicy: ResourcePolicy
    environment: str
    inputBase64: str | None = None
    sourceRef: str | None = None


@dataclasses.dataclass
class ExecutionMetadata:
    startedAtEpochMs: int
    completedAtEpochMs: int
    wallTimeMs: int
    timedOut: bool
    outputBytes: int
    resourceMetrics: dict[str, Any] | None = None


@dataclasses.dataclass
class SandboxExecutionResponse:
    executionId: str
    agentRunId: str
    toolCallId: str
    exitStatus: int
    status: str
    stdout: str
    stderr: str
    artifactRefs: list[str]
    metadata: ExecutionMetadata
    error: str | None = None


def request_from_dict(data: dict[str, Any]) -> SandboxExecutionRequest:
    return _from_dict(SandboxExecutionRequest, data)


def request_to_dict(request: SandboxExecutionRequest) -> dict[str, Any]:
    return _to_dict(request)


def response_from_dict(data: dict[str, Any]) -> SandboxExecutionResponse:
    return _from_dict(SandboxExecutionResponse, data)


def response_to_dict(response: SandboxExecutionResponse) -> dict[str, Any]:
    return _to_dict(response)
