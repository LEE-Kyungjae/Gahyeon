#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_api_documentation.py")
SPEC = importlib.util.spec_from_file_location("verify_api_documentation", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class ApiDocumentationContractTest(unittest.TestCase):
    def test_repository_contract_is_current(self) -> None:
        self.assertEqual([], MODULE.verify())

    def test_all_documented_paths_include_api_context(self) -> None:
        self.assertTrue(MODULE.DOCUMENTED_PATHS)
        self.assertTrue(all(path.startswith("/api/") for path in MODULE.DOCUMENTED_PATHS))

    def test_local_links_resolve_relative_to_document(self) -> None:
        for relative_link in MODULE.LOCAL_LINKS:
            self.assertTrue((MODULE.DOC.parent / relative_link).is_file(), relative_link)


if __name__ == "__main__":
    unittest.main()
