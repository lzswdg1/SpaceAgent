#!/usr/bin/env python3
"""Validate release configuration without printing secret values."""

from __future__ import annotations

import ipaddress
import os
import re
import sys
from urllib.parse import urlsplit


UNSAFE = re.compile(
    r"local-dev|change-in-production|change-me|replace-with|placeholder|fixture|example|\.invalid|spaceagent123",
    re.IGNORECASE,
)

SECRET_LENGTHS = {
    "DB_PASSWORD": 16,
    "JWT_SECRET": 32,
    "INTERNAL_SERVICE_TOKEN": 32,
    "MODEL_PROVIDER_ENCRYPTION_KEY": 32,
    "MCP_CONNECTION_ENCRYPTION_KEY": 32,
    "IDENTITY_ACTIVITY_HASH_KEY": 32,
    "SYSTEM_ADMIN_JWT_SECRET": 32,
    "ADMIN_DB_PASSWORD": 16,
    "ADMIN_JWT_SECRET": 32,
}


class ReleaseEnvironmentError(ValueError):
    pass


def value(name: str) -> str:
    return os.environ.get(name, "").strip()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseEnvironmentError(message)


def safe_value(name: str, minimum: int = 1) -> str:
    configured = value(name)
    require(len(configured) >= minimum, f"{name} is missing or shorter than {minimum} characters")
    require(UNSAFE.search(configured) is None, f"{name} contains a development placeholder")
    return configured


def private_bind(name: str) -> None:
    configured = value(name)
    try:
        address = ipaddress.ip_address(configured)
    except ValueError as error:
        raise ReleaseEnvironmentError(f"{name} must be a literal loopback or private IP address") from error
    require(address.is_loopback or address.is_private,
            f"{name} must remain on a loopback or private network")
    require(not address.is_unspecified, f"{name} must not bind all interfaces")


def private_https_url(name: str) -> None:
    configured = safe_value(name)
    parsed = urlsplit(configured)
    require(parsed.scheme == "https" and parsed.hostname is not None,
            f"{name} must be an explicit HTTPS URL")
    require(parsed.username is None and parsed.password is None,
            f"{name} must not contain URL credentials")
    require(not parsed.query and not parsed.fragment,
            f"{name} must be a service root without query or fragment")


def administrator_password() -> None:
    configured = safe_value("ADMIN_PASSWORD", 14)
    require(len(configured) <= 512
            and re.search(r"[A-Z]", configured) is not None
            and re.search(r"[a-z]", configured) is not None
            and re.search(r"[0-9]", configured) is not None
            and re.search(r"[^A-Za-z0-9]", configured) is not None,
            "ADMIN_PASSWORD does not satisfy the release password policy")


def validate() -> None:
    secrets = {name: safe_value(name, minimum) for name, minimum in SECRET_LENGTHS.items()}
    safe_value("AI_EMBEDDING_API_KEY", 8)
    safe_value("SPACEAGENT_RELEASE_VERSION")
    safe_value("ADMIN_LOGIN")
    administrator_password()
    private_bind("PLATFORM_BIND_ADDRESS")
    private_bind("ADMIN_BIND_ADDRESS")
    private_bind("ADMIN_WEB_BIND_ADDRESS")
    private_https_url("ADMIN_PLATFORM_CLIENT_BASE_URL")

    require(value("PLATFORM_RELEASE_MODE") == "trusted-beta",
            "PLATFORM_RELEASE_MODE must be trusted-beta")
    require(value("PLATFORM_TRUSTED_CODE_ONLY") == "true",
            "PLATFORM_TRUSTED_CODE_ONLY must be true")
    require(value("PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED") == "false",
            "PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED must be false")
    require(value("PLATFORM_ALLOW_INSECURE_LOCAL") == "false",
            "PLATFORM_ALLOW_INSECURE_LOCAL must be false")
    require(value("SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL") == "false",
            "SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL must be false")
    require(value("ADMIN_COOKIE_SECURE") == "true",
            "ADMIN_COOKIE_SECURE must be true")

    distinct = [
        "JWT_SECRET", "INTERNAL_SERVICE_TOKEN", "MODEL_PROVIDER_ENCRYPTION_KEY",
        "MCP_CONNECTION_ENCRYPTION_KEY", "IDENTITY_ACTIVITY_HASH_KEY",
        "SYSTEM_ADMIN_JWT_SECRET", "ADMIN_JWT_SECRET",
    ]
    require(len({secrets[name] for name in distinct}) == len(distinct),
            "Release JWT, internal, encryption and administrator secrets must be distinct")
    require(secrets["DB_PASSWORD"] != secrets["ADMIN_DB_PASSWORD"],
            "ADMIN_DB_PASSWORD must differ from DB_PASSWORD")


if __name__ == "__main__":
    try:
        validate()
    except ReleaseEnvironmentError as error:
        print(f"RELEASE_ENVIRONMENT_INVALID: {error}", file=sys.stderr)
        raise SystemExit(1)
    print("PASS: release environment security fields are configured (values redacted)")
