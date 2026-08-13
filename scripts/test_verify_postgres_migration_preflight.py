#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_postgres_migration_preflight as contract


class PostgreSqlMigrationPreflightContractTest(unittest.TestCase):
    def test_checked_in_contract_passes(self) -> None:
        self.assertEqual([], contract.verify())

    def test_missing_runtime_guards_and_ci_wiring_fail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runtime = root / "preflight.sh"
            workflow = root / "workflow.yml"
            core = root / "core.yml"
            doc = root / "preflight.md"
            guide = root / "deployment.md"
            runtime.write_text("#!/bin/sh\ndocker system prune\nPOSTGRES_PROD_HOST=x\n", encoding="utf-8")
            workflow.write_text("name: unsafe\n", encoding="utf-8")
            core.write_text("name: incomplete\n", encoding="utf-8")
            doc.write_text("not live-production\n", encoding="utf-8")
            guide.write_text("not live-production\n", encoding="utf-8")
            with (
                patch.object(contract, "RUNTIME", runtime),
                patch.object(contract, "WORKFLOW", workflow),
                patch.object(contract, "CORE_WORKFLOW", core),
                patch.object(contract, "DOC", doc),
                patch.object(contract, "GUIDE", guide),
            ):
                errors = contract.verify()
        self.assertTrue(any("fail-closed marker" in error for error in errors))
        self.assertTrue(any("production-specific" in error for error in errors))
        self.assertTrue(any("over-broad" in error for error in errors))
        self.assertTrue(any("workflow is missing marker" in error for error in errors))
        self.assertTrue(any("Core Boundaries" in error for error in errors))
        self.assertTrue(any("preflight document" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
