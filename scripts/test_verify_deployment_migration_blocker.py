#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_deployment_migration_blocker as contract


class DeploymentMigrationPreflightContractTest(unittest.TestCase):
    def test_checked_in_preflight_passes(self) -> None:
        self.assertEqual([], contract.verify())

    def test_rejects_reused_rename_version_and_wrong_v36_column(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            migrations = Path(directory)
            (migrations / "V30__Reused.sql").write_text("SELECT 1;\n", encoding="utf-8")
            v36 = migrations / "V36__Index_agent_run_supersession.sql"
            v36.write_text(
                "CREATE INDEX example ON agent_runs(actor_id, status, created_at);\n",
                encoding="utf-8",
            )
            with (
                patch.object(contract, "MIGRATIONS", migrations),
                patch.object(contract, "V36", v36),
            ):
                errors = contract.verify()
        self.assertTrue(any("must remain absent and unused" in error for error in errors))
        self.assertTrue(any("must target the V24" in error for error in errors))
        self.assertTrue(any("must not target" in error for error in errors))

    def test_rejects_missing_live_gate_and_unsafe_publish_architecture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc = root / "preflight.md"
            guide = root / "deployment.md"
            workflow = root / "build-image.yml"
            doc.write_text("Status: SOURCE COMPATIBILITY RESOLVED\n", encoding="utf-8")
            guide.write_text("no preflight link\n", encoding="utf-8")
            workflow.write_text("platforms: linux/amd64,linux/arm64\n", encoding="utf-8")
            with (
                patch.object(contract, "DOC", doc),
                patch.object(contract, "DEPLOYMENT_GUIDE", guide),
                patch.object(contract, "IMAGE_WORKFLOW", workflow),
            ):
                errors = contract.verify()
        self.assertTrue(any("missing marker" in error for error in errors))
        self.assertTrue(any("does not link" in error for error in errors))
        self.assertTrue(any("must not publish arm64" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
