"""Secret-safe environment projection for OCI sandbox containers."""

from __future__ import annotations

import json
import os
import re


SECRET_KEY_PATTERN = re.compile(
    r"(?i)(token|secret|password|passwd|api[_-]?key|access[_-]?key|private[_-]?key|credential)"
)

SAFE_HOST_ENV_KEYS = {"PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR"}


class IsolationError(ValueError):
    """Raised when a request violates the declared isolation boundary."""


def sanitize_environment(environment_json: str) -> dict[str, str]:
    environment: dict[str, str] = {
        key: os.environ[key] for key in SAFE_HOST_ENV_KEYS if key in os.environ
    }
    try:
        provided = json.loads(environment_json or "{}")
    except json.JSONDecodeError as error:
        raise IsolationError(f"environment must be a JSON object: {error}") from error
    if not isinstance(provided, dict):
        raise IsolationError("environment must be a JSON object")
    for key, value in provided.items():
        if SECRET_KEY_PATTERN.search(key):
            continue
        environment[str(key)] = str(value)
    return environment
