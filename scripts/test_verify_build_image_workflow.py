#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_build_image_workflow as contract


class BuildImageWorkflowContractTest(unittest.TestCase):
    def test_checked_in_workflows_pass(self) -> None:
        self.assertEqual([], contract.verify())

    def test_publication_policy_truth_table(self) -> None:
        self.assertTrue(contract.publication_allowed("refs/heads/main", "push", False))
        self.assertTrue(contract.publication_allowed(
            "refs/heads/main", "workflow_dispatch", True))
        self.assertFalse(contract.publication_allowed(
            "refs/heads/main", "workflow_dispatch", False))
        self.assertFalse(contract.publication_allowed(
            "refs/heads/develop", "workflow_dispatch", True))
        self.assertFalse(contract.publication_allowed(
            "refs/heads/feature/a", "workflow_dispatch", True))

    def test_rejects_non_main_publish_arm64_and_unlinked_verification(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build = root / "build.yml"
            production = root / "verify.yml"
            build.write_text(
                "push_image:\n"
                "if: github.event_name == 'workflow_dispatch' && inputs.push_image == true\n"
                "platforms: linux/amd64,linux/arm64\n",
                encoding="utf-8",
            )
            production.write_text("workflow_run:\n", encoding="utf-8")
            with (
                patch.object(contract, "BUILD_WORKFLOW", build),
                patch.object(contract, "VERIFY_WORKFLOW", production),
                patch.object(contract, "CONTAINER_SMOKE", root / "missing-smoke.sh"),
            ):
                errors = contract.verify()
        self.assertTrue(any("default-false push_image" in error for error in errors))
        self.assertTrue(any("main push" in error for error in errors))
        self.assertTrue(any("linux/amd64-only" in error for error in errors))
        self.assertTrue(any("follows the Build Image" in error for error in errors))
        self.assertTrue(any("restricted to main" in error for error in errors))
        self.assertTrue(any("container smoke referenced" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
