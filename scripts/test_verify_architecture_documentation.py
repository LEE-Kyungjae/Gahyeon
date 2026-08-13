#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_architecture_documentation as contract


class ArchitectureDocumentationContractTest(unittest.TestCase):
    def test_checked_in_architecture_passes(self) -> None:
        self.assertEqual([], contract.verify())

    def test_rejects_missing_boundary_stale_claim_and_broken_link(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            doc = Path(directory) / "ARCHITECTURE.md"
            doc.write_text(
                "GPT-4o-mini\n[missing](does-not-exist.md)\n",
                encoding="utf-8",
            )
            with patch.object(contract, "DOC", doc):
                errors = contract.verify()
        self.assertTrue(any("missing current boundary" in error for error in errors))
        self.assertTrue(any("legacy primary-architecture" in error for error in errors))
        self.assertTrue(any("missing local link" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
