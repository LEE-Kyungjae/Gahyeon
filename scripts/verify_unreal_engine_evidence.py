#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys

SHA256 = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_TESTS = (
    "Gahyeon.Runtime.MockCognitionDelayFailureAndReordering",
    "Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded",
)


def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def contained_file(root: pathlib.Path, relative: object) -> pathlib.Path:
    if not isinstance(relative, str) or not relative or pathlib.PurePath(relative).is_absolute():
        raise ValueError(f"evidence path must be relative: {relative!r}")
    candidate = (root / relative).resolve()
    if candidate == root or root not in candidate.parents:
        raise ValueError(f"evidence path escapes root: {relative!r}")
    if not candidate.is_file():
        raise ValueError(f"evidence file is missing: {relative!r}")
    return candidate


def verify(root: pathlib.Path) -> dict:
    root = root.resolve()
    manifest_path = root / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("manifest.json is missing")
    value = json.loads(manifest_path.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != 2 or value.get("status") != "passed":
        raise ValueError("evidence manifest is not a passed schema v2 result")
    if value.get("engineVersion") != "5.6" or value.get("configuration") != "Development":
        raise ValueError("evidence was not produced by the UE 5.6 Development gate")
    if value.get("requiredAutomationTests") != list(REQUIRED_TESTS):
        raise ValueError("required Automation identities are missing or reordered")
    packaged = value.get("packagedBuild")
    if not isinstance(packaged, bool):
        raise ValueError("packagedBuild evidence flag is missing")

    project_value = value.get("project")
    project = pathlib.Path(project_value).resolve() if isinstance(project_value, str) else None
    if project is None or not project.is_file():
        raise ValueError("recorded Unreal project is missing")
    project_hash = value.get("projectSha256")
    if not isinstance(project_hash, str) or not SHA256.fullmatch(project_hash):
        raise ValueError("project SHA-256 is invalid")
    if digest(project) != project_hash:
        raise ValueError("Unreal project SHA-256 mismatch")

    evidence = value.get("evidence")
    if not isinstance(evidence, dict):
        raise ValueError("evidence file map is missing")
    resolved_evidence = {}
    required_evidence = ["buildLog", "automationLog"]
    if packaged:
        required_evidence.extend(("packageLog", "packageInventory"))
    elif "packageLog" in evidence or "packageInventory" in evidence:
        raise ValueError("package evidence is present while packagedBuild is false")
    for key in required_evidence:
        item = evidence.get(key)
        if not isinstance(item, dict):
            raise ValueError(f"{key} evidence is missing")
        expected = item.get("sha256")
        if not isinstance(expected, str) or not SHA256.fullmatch(expected):
            raise ValueError(f"{key} SHA-256 is invalid")
        path = contained_file(root, item.get("path"))
        if digest(path) != expected:
            raise ValueError(f"{key} SHA-256 mismatch")
        resolved_evidence[key] = path
    automation_log = resolved_evidence["automationLog"].read_text(
        encoding="utf-8", errors="replace")
    for test_name in REQUIRED_TESTS:
        escaped = re.escape(test_name)
        if not re.search(
            rf"Result=\{{Success\}}.*{escaped}|{escaped}.*(?:Automation Test Succeeded|Result=Passed|Success)",
            automation_log):
            raise ValueError(f"required Automation success is absent from log: {test_name}")
    if packaged:
        inventory = json.loads(resolved_evidence["packageInventory"].read_text(encoding="utf-8"))
        files = inventory.get("files") if inventory.get("schemaVersion") == 1 else None
        if not isinstance(files, list) or not files:
            raise ValueError("package inventory contains no files")
        seen = set()
        package_root = (root / "package").resolve()
        for item in files:
            if not isinstance(item, dict):
                raise ValueError("package inventory entry is invalid")
            relative = item.get("path")
            path = contained_file(package_root, relative)
            if relative in seen:
                raise ValueError(f"duplicate package inventory path: {relative}")
            seen.add(relative)
            if path.stat().st_size != item.get("bytes") or digest(path) != item.get("sha256"):
                raise ValueError(f"packaged file mismatch: {relative}")
        actual = {
            path.relative_to(package_root).as_posix()
            for path in package_root.rglob("*") if path.is_file() and not path.is_symlink()
        }
        if actual != seen:
            raise ValueError("package inventory does not exactly cover packaged output")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Gahyeon Unreal Engine gate evidence")
    parser.add_argument("evidence_root", type=pathlib.Path)
    args = parser.parse_args()
    try:
        value = verify(args.evidence_root)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Unreal Engine evidence invalid: {error}", file=sys.stderr)
        return 1
    print(
        "Unreal Engine evidence verified: "
        f"UE {value['engineVersion']} {value['configuration']}, "
        f"{len(value['requiredAutomationTests'])} required Automation tests"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
