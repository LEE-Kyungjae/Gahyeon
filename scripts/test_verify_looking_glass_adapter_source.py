#!/usr/bin/env python3

import unittest

from verify_looking_glass_adapter_source import verify


class LookingGlassAdapterSourceTest(unittest.TestCase):
    def test_current_source_is_optional_and_fail_closed(self):
        result = verify()
        self.assertTrue(result["baseIndependent"])
        self.assertTrue(result["runtimeAttestation"])


if __name__ == "__main__":
    unittest.main()
