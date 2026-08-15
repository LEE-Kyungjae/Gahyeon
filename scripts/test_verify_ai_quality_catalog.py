#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from verify_ai_quality_catalog import CatalogError, validate_catalog


class VerifyAiQualityCatalogTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_directory.name)
        (self.root / "tests").mkdir()
        (self.root / "tests/example_test.py").write_text(
            "def test_incident_contract():\n    pass\n", encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temp_directory.cleanup()

    def write_catalog(self, cases: list[dict[str, str]]) -> Path:
        path = self.root / "catalog.json"
        path.write_text(json.dumps({"schemaVersion": 1, "cases": cases}), encoding="utf-8")
        return path

    def case(self, **overrides: str) -> dict[str, str]:
        case = {
            "id": "incident-1",
            "domain": "stt",
            "incident": "A deterministic failure occurred.",
            "invariant": "The failure cannot reach the user.",
            "testFile": "tests/example_test.py",
            "testMethod": "test_incident_contract",
            "productionSignal": "A privacy-safe rejection counter.",
            "rollbackTrigger": "Any recurrence.",
        }
        case.update(overrides)
        return case

    def test_accepts_executable_incident_contract(self) -> None:
        self.assertEqual(validate_catalog(self.write_catalog([self.case()]), self.root), 1)

    def test_rejects_duplicate_case_ids(self) -> None:
        with self.assertRaisesRegex(CatalogError, "duplicate case id"):
            validate_catalog(self.write_catalog([self.case(), self.case()]), self.root)

    def test_rejects_missing_test_method(self) -> None:
        with self.assertRaisesRegex(CatalogError, "not found"):
            validate_catalog(
                self.write_catalog([self.case(testMethod="test_does_not_exist")]), self.root
            )

    def test_rejects_repository_escape(self) -> None:
        with self.assertRaisesRegex(CatalogError, "escapes the repository"):
            validate_catalog(self.write_catalog([self.case(testFile="../outside.py")]), self.root)


if __name__ == "__main__":
    unittest.main()
