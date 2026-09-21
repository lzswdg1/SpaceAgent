#!/usr/bin/env python3
"""Fail closed on public-snapshot paths and private Git-history identifiers."""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
ALLOWED_OUTPUT = Path("cli/internal/output")
PROHIBITED_DIRS = {
    "copy", ".run", "output", "testapikey", "node_modules", "target", "dist", ".gomodcache",
}
PROHIBITED_SUFFIXES = {
    ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx",
    ".sqlite", ".sqlite3", ".db", ".dump", ".backup", ".bak", ".log",
}
UPSTREAM_COMMITS = {
    "94f432d7",
    "94f432d7126f5884d30a2cdde6f4e89908ebb6fd",
}
BACKTICK_HEX = re.compile(r"`([0-9a-f]{7,40})`")
PRIVATE_GIT_CONTEXT = re.compile(
    r"(?i)(?:implementation\s+(?:sha|commit)|implementation\s+baseline|"
    r"private\s+(?:head|commit)|source\s+(?:head|commit)|archive\s+(?:tag|commit)|"
    r"源码基线|私有.*提交|基线|checkpoint).{0,100}\b([0-9a-f]{7,40})\b"
)
PRIVATE_ARCHIVE_TAG = "legacy-v1-" "final"
WORKSTATION_PATH = re.compile(
    r"/" r"Users/(?!me/project)|/private/" r"var/folders/|[A-Za-z]:\\" r"Users\\"
)


def relative(path: Path) -> Path:
    return path.relative_to(ROOT)


def allowed_output(path: Path) -> bool:
    rel = relative(path)
    return rel == ALLOWED_OUTPUT or ALLOWED_OUTPUT in rel.parents


def candidate_text(path: Path) -> str | None:
    if path.suffix.lower() not in {".md", ".txt", ".html", ".yml", ".yaml"}:
        return None
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return None


def main() -> int:
    errors: list[str] = []
    module_path = ""
    go_mod = ROOT / "cli/go.mod"
    for line in go_mod.read_text(encoding="utf-8").splitlines():
        if line.startswith("module "):
            module_path = line.removeprefix("module ").strip()
            break
    if not module_path:
        errors.append("cli/go.mod has no module path")

    for path in ROOT.rglob("*"):
        rel = relative(path)
        if rel.parts and rel.parts[0] == ".git":
            continue
        if any(part in PROHIBITED_DIRS for part in rel.parts[:-1]) and not allowed_output(path):
            continue
        if path.is_symlink():
            if path.name in PROHIBITED_DIRS:
                errors.append(f"generated/private directory is forbidden: {rel}")
                continue
            errors.append(f"tracked symlink is forbidden: {rel}")
            continue
        if path.is_dir():
            if path.name in PROHIBITED_DIRS:
                if path.name != "output" or not allowed_output(path):
                    errors.append(f"generated/private directory is forbidden: {rel}")
            continue
        if not path.is_file():
            errors.append(f"special filesystem entry is forbidden: {rel}")
            continue
        if path.suffix.lower() in PROHIBITED_SUFFIXES:
            errors.append(f"credential/database/log artifact is forbidden: {rel}")
        if path.name == ".env" or (path.name.startswith(".env.") and not path.name.endswith(".example")):
            errors.append(f"non-example environment file is forbidden: {rel}")

        text = candidate_text(path)
        if text is None:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for match in BACKTICK_HEX.finditer(line):
                value = match.group(1)
                if re.search(r"[a-f]", value) is None:
                    continue
                if value in UPSTREAM_COMMITS:
                    continue
                if "uses:" in line and re.search(r"@[0-9a-f]{40}", line):
                    continue
                errors.append(f"unexplained commit-like identifier: {rel}:{number}")
            match = PRIVATE_GIT_CONTEXT.search(line)
            if match and match.group(1) not in UPSTREAM_COMMITS:
                errors.append(f"private Git identifier context: {rel}:{number}")
            if PRIVATE_ARCHIVE_TAG in line:
                errors.append(f"private archive tag is forbidden: {rel}:{number}")
            if re.search(r"(?i)^\s*>?\s*branch:\s*`", line):
                errors.append(f"private branch metadata is forbidden: {rel}:{number}")
            if re.search(r"最近提交.{0,80}\b[0-9a-f]{7,40}\b", line):
                errors.append(f"prototype contains a commit-like fixture: {rel}:{number}")
            if WORKSTATION_PATH.search(line):
                errors.append(f"developer workstation path is forbidden: {rel}:{number}")

    required = [
        ROOT / "PUBLIC-SOURCE-MANIFEST.txt",
        ROOT / "ASSET-PROVENANCE.md",
        ROOT / ".env.example",
        ROOT / ".env.release.example",
        ROOT / "cli/internal/output/print.go",
    ]
    for path in required:
        if not path.is_file():
            errors.append(f"required public source is missing: {relative(path)}")

    if errors:
        for error in sorted(set(errors)):
            print(f"PUBLIC_SOURCE_BOUNDARY_INVALID: {error}", file=sys.stderr)
        return 1
    print("PASS: public source paths and private-history boundaries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
