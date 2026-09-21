"""Deterministic positive and negative release-environment checks."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "scripts" / "check-release-environment.py"


def secure_environment() -> dict[str, str]:
    return {
        "DB_PASSWORD": "Main-Database-Password-2026!",
        "JWT_SECRET": "tenant-jwt-release-secret-32-characters-long",
        "INTERNAL_SERVICE_TOKEN": "internal-release-token-32-characters-long",
        "MODEL_PROVIDER_ENCRYPTION_KEY": "provider-encryption-key-32-characters-long",
        "MCP_CONNECTION_ENCRYPTION_KEY": "mcp-encryption-key-release-32-characters-long",
        "IDENTITY_ACTIVITY_HASH_KEY": "activity-hash-key-release-32-characters-long",
        "SYSTEM_ADMIN_JWT_SECRET": "system-admin-jwt-release-32-characters-long",
        "ADMIN_DB_PASSWORD": "Admin-Database-Password-2026!",
        "ADMIN_JWT_SECRET": "admin-jwt-release-secret-32-characters-long",
        "ADMIN_LOGIN": "platform-administrator",
        "ADMIN_PASSWORD": "Strong-Admin-Password-2026!",
        "ADMIN_COOKIE_SECURE": "true",
        "ADMIN_BIND_ADDRESS": "127.0.0.1",
        "ADMIN_WEB_BIND_ADDRESS": "127.0.0.1",
        "ADMIN_PLATFORM_CLIENT_BASE_URL": "https://10.0.0.5",
        "AI_EMBEDDING_API_KEY": "provider-key-for-release-testing",
        "SPACEAGENT_RELEASE_VERSION": "1.0.0-rc1",
        "PLATFORM_BIND_ADDRESS": "127.0.0.1",
        "PLATFORM_RELEASE_MODE": "trusted-beta",
        "PLATFORM_TRUSTED_CODE_ONLY": "true",
        "PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED": "false",
        "PLATFORM_ALLOW_INSECURE_LOCAL": "false",
        "SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL": "false",
    }


class ReleaseEnvironmentTest(unittest.TestCase):
    def run_checker(self, changes: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
        environment = secure_environment()
        if changes:
            environment.update(changes)
        return subprocess.run(
            [sys.executable, str(CHECKER)],
            cwd=ROOT,
            env={**os.environ, **environment},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_secure_release_environment_passes_without_echoing_values(self):
        result = self.run_checker()
        self.assertEqual(0, result.returncode, result.stderr)
        for configured in secure_environment().values():
            if len(configured) >= 14:
                self.assertNotIn(configured, result.stdout + result.stderr)

    def test_release_rejects_missing_short_or_placeholder_secrets(self):
        for changes in (
            {"ADMIN_JWT_SECRET": ""},
            {"ADMIN_DB_PASSWORD": "short"},
            {"SYSTEM_ADMIN_JWT_SECRET": "replace-with-system-admin-secret"},
            {"DB_PASSWORD": secure_environment()["ADMIN_DB_PASSWORD"]},
        ):
            with self.subTest(changes=tuple(changes)):
                self.assertNotEqual(0, self.run_checker(changes).returncode)

    def test_release_rejects_insecure_admin_transport_and_password(self):
        for changes in (
            {"ADMIN_COOKIE_SECURE": "false"},
            {"ADMIN_BIND_ADDRESS": "0.0.0.0"},
            {"ADMIN_WEB_BIND_ADDRESS": "8.8.8.8"},
            {"ADMIN_PLATFORM_CLIENT_BASE_URL": "http://10.0.0.5"},
            {"ADMIN_PASSWORD": "replace-with-password"},
        ):
            with self.subTest(changes=tuple(changes)):
                self.assertNotEqual(0, self.run_checker(changes).returncode)


if __name__ == "__main__":
    unittest.main()
