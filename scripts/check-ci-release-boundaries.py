#!/usr/bin/env python3
"""Structural guard for public CI/release security requirements."""

from __future__ import annotations

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"CI_RELEASE_BOUNDARY_INVALID: {message}")


def main() -> None:
    files = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
    for path in files:
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), 1):
            match = re.search(r"\buses:\s*([^\s]+)", line)
            if not match or match.group(1).startswith("./"):
                continue
            require(re.fullmatch(r"[^@]+@[0-9a-f]{40}", match.group(1)) is not None,
                    f"external action is not SHA pinned: {path.name}:{number}")
            require("# v" in line, f"pinned action lacks version comment: {path.name}:{number}")

    ci = (WORKFLOWS / "ci.yml").read_text(encoding="utf-8")
    release = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
    cli_release = (WORKFLOWS / "cli-release.yml").read_text(encoding="utf-8")

    for required in (
        "./mvnw -B verify", "scripts/check-architecture.sh", "go test -race ./...",
        "npm run test:admin", "npm run build:admin", "npm test", "npm run build",
        "scripts/test-export-public-source.sh", "scripts/check-compose-configs.sh",
        "scripts/check-dockerfiles.sh", "goreleaser-action", "sbom-action",
        "scripts/check-public-source-boundaries.py",
    ):
        require(required in ci, f"CI is missing required gate: {required}")
    require(ci.count("gitleaks\" git --redact=100") == 1
            and ci.count("gitleaks\" dir --redact=100") == 1,
            "CI must scan Git range and current tree with full redaction")
    for workflow_name, workflow in (("CI", ci), ("Release", release)):
        require("tr '[:upper:]' '[:lower:]'" in workflow
                and 'prefix=ghcr.io/$repository' in workflow,
                f"{workflow_name} must lowercase the GHCR repository namespace")
        require("ghcr.io/${{ github.repository }}" not in workflow,
                f"{workflow_name} must not use the mixed-case repository name in GHCR tags")

    require("uses: ./.github/workflows/ci.yml" in release,
            "Release must reuse the complete CI gate before images")
    for service in ("platform-server", "platform-admin-server", "sandbox-worker", "web", "admin-web"):
        require(f"service: {service}" in release, f"Release image matrix is missing {service}")
    require(release.count("service:") == 5, "Release image matrix must contain exactly five services")
    require("sbom: true" in release and "provenance: mode=max" in release
            and "Generate image SBOM artifact" in release,
            "Release images must emit SBOM and provenance evidence")

    image_section = release.split("build-images:", 1)[1]
    for forbidden in ("mvnw", "npm test", "go test", "unittest discover"):
        require(forbidden not in image_section,
                f"Image matrix must not repeat the full test suite: {forbidden}")
    require("--redact=100" in cli_release and "goreleaser-action" in cli_release
            and "Generate CLI dependency SBOM" in cli_release,
            "CLI release must scan, check, package and emit dependency SBOM evidence")

    print("PASS: CI and release workflow boundaries")


if __name__ == "__main__":
    main()
