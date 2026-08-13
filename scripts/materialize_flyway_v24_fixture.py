#!/usr/bin/env python3
"""Materialize the immutable V1-V24 Flyway fixture from the verified GitOps commit."""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "scripts/fixtures/flyway-v24.sha256"
MIGRATION_PREFIX = "src/main/resources/db/migration/"


class FixtureError(RuntimeError):
    pass


@dataclass(frozen=True)
class FixtureManifest:
    source_commit: str
    entries: tuple[tuple[str, str], ...]


def load_manifest(path: Path) -> FixtureManifest:
    source_commit: str | None = None
    entries: list[tuple[str, str]] = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            match = re.fullmatch(r"# source-commit: ([0-9a-f]{40})", line)
            if match:
                source_commit = match.group(1)
            continue
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if not match:
            raise FixtureError(f"invalid manifest line {line_number}: {raw}")
        entries.append((match.group(1), match.group(2)))

    if source_commit is None:
        raise FixtureError("manifest is missing its 40-character source commit")
    versions: list[int] = []
    for _, relative in entries:
        if not relative.startswith(MIGRATION_PREFIX):
            raise FixtureError(f"fixture path is outside the migration directory: {relative}")
        filename = relative.removeprefix(MIGRATION_PREFIX)
        if "/" in filename or ".." in filename:
            raise FixtureError(f"unsafe fixture path: {relative}")
        match = re.fullmatch(r"V(\d+)__.+\.sql", filename)
        if not match:
            raise FixtureError(f"invalid Flyway fixture filename: {filename}")
        versions.append(int(match.group(1)))
    if versions != list(range(1, 25)):
        raise FixtureError(f"fixture must contain ordered versions 1 through 24, found {versions}")
    return FixtureManifest(source_commit, tuple(entries))


def read_git_blob(repo_root: Path, commit: str, relative: str) -> bytes:
    result = subprocess.run(
        ["git", "show", f"{commit}:{relative}"],
        cwd=repo_root,
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise FixtureError(f"cannot read authoritative fixture blob {commit}:{relative}: {detail}")
    return result.stdout


def verify_fixture_sources(
    manifest: FixtureManifest,
    checkout_root: Path = ROOT,
    blob_reader: Callable[[Path, str, str], bytes] = read_git_blob,
) -> tuple[tuple[str, bytes], ...]:
    verified: list[tuple[str, bytes]] = []
    for expected_sha, relative in manifest.entries:
        authoritative = blob_reader(checkout_root, manifest.source_commit, relative)
        actual_sha = hashlib.sha256(authoritative).hexdigest()
        if actual_sha != expected_sha:
            raise FixtureError(
                f"authoritative blob checksum mismatch for {relative}: {actual_sha} != {expected_sha}"
            )
        current_path = checkout_root / relative
        if not current_path.is_file():
            raise FixtureError(f"current applied migration is missing: {relative}")
        current_sha = hashlib.sha256(current_path.read_bytes()).hexdigest()
        if current_sha != expected_sha:
            raise FixtureError(
                f"current applied migration changed for {relative}: {current_sha} != {expected_sha}"
            )
        verified.append((Path(relative).name, authoritative))
    return tuple(verified)


def materialize(
    output: Path,
    manifest_path: Path = DEFAULT_MANIFEST,
    checkout_root: Path = ROOT,
    blob_reader: Callable[[Path, str, str], bytes] = read_git_blob,
) -> FixtureManifest:
    manifest = load_manifest(manifest_path)
    files = verify_fixture_sources(manifest, checkout_root, blob_reader)
    if output.exists() and any(output.iterdir()):
        raise FixtureError(f"refusing to overwrite non-empty fixture directory: {output}")
    output.mkdir(parents=True, exist_ok=True)
    for filename, content in files:
        (output / filename).write_bytes(content)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--repo-root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        manifest = materialize(args.output, args.manifest, args.repo_root)
    except (FixtureError, OSError) as error:
        print(f"ERROR: {error}")
        return 1
    print(
        f"Materialized authoritative V1-V24 fixture from {manifest.source_commit} "
        f"into {args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
