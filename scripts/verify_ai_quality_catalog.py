#!/usr/bin/env python3
"""Validate that every AI incident catalog entry points to executable coverage."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

REQUIRED_FIELDS = {
    "id",
    "domain",
    "incident",
    "invariant",
    "testFile",
    "testMethod",
    "productionSignal",
    "rollbackTrigger",
}
ALLOWED_DOMAINS = {"agent", "deployment", "stt", "tts", "weather"}


class CatalogError(ValueError):
    pass


def _nonempty_string(case: dict[str, Any], field: str, case_id: str) -> str:
    value = case.get(field)
    if not isinstance(value, str) or not value.strip():
        raise CatalogError(f"{case_id}: {field} must be a non-empty string")
    return value.strip()


def validate_catalog(catalog_path: Path, repo_root: Path) -> int:
    data = json.loads(catalog_path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        raise CatalogError("schemaVersion must be 1")
    cases = data.get("cases")
    if not isinstance(cases, list) or not cases:
        raise CatalogError("cases must be a non-empty list")

    root = repo_root.resolve()
    seen: set[str] = set()
    for index, raw_case in enumerate(cases):
        if not isinstance(raw_case, dict):
            raise CatalogError(f"case[{index}] must be an object")
        missing = REQUIRED_FIELDS - raw_case.keys()
        if missing:
            raise CatalogError(f"case[{index}] missing fields: {', '.join(sorted(missing))}")

        case_id = _nonempty_string(raw_case, "id", f"case[{index}]")
        if case_id in seen:
            raise CatalogError(f"duplicate case id: {case_id}")
        seen.add(case_id)

        domain = _nonempty_string(raw_case, "domain", case_id)
        if domain not in ALLOWED_DOMAINS:
            raise CatalogError(f"{case_id}: unsupported domain {domain!r}")
        for field in REQUIRED_FIELDS - {"id", "domain", "testFile", "testMethod"}:
            _nonempty_string(raw_case, field, case_id)

        relative_test = Path(_nonempty_string(raw_case, "testFile", case_id))
        if relative_test.is_absolute():
            raise CatalogError(f"{case_id}: testFile must be repository-relative")
        test_path = (root / relative_test).resolve()
        try:
            test_path.relative_to(root)
        except ValueError as exc:
            raise CatalogError(f"{case_id}: testFile escapes the repository") from exc
        if not test_path.is_file():
            raise CatalogError(f"{case_id}: test file does not exist: {relative_test}")

        test_method = _nonempty_string(raw_case, "testMethod", case_id)
        if test_method not in test_path.read_text(encoding="utf-8"):
            raise CatalogError(
                f"{case_id}: test method {test_method!r} not found in {relative_test}"
            )

    return len(cases)


def main(argv: list[str]) -> int:
    repo_root = Path(__file__).resolve().parents[1]
    catalog_path = Path(argv[1]).resolve() if len(argv) > 1 else repo_root / "quality/ai-regressions.json"
    try:
        count = validate_catalog(catalog_path, repo_root)
    except (CatalogError, json.JSONDecodeError, OSError) as exc:
        print(f"AI quality catalog invalid: {exc}", file=sys.stderr)
        return 1
    print(f"AI quality catalog valid: {count} executable incident contracts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
