#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from create_autonomy_contract import ContractError, build_contract, load_policy, write_once


ROOT = Path(__file__).resolve().parents[1]


class CreateAutonomyContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.policy = load_policy(ROOT / "quality/autonomy-policy.json")

    def test_contract_identity_is_deterministic_and_constraints_are_normalized(self) -> None:
        first = build_contract(self.policy, " Improve English TTS ", "tts", ["B", "A", "A"])
        second = build_contract(self.policy, "Improve English TTS", "tts", ["A", "B"])
        self.assertEqual(first["contractId"], second["contractId"])
        self.assertEqual(first["constraints"], ["A", "B"])
        self.assertIn("blind-listening", [gate["name"] for gate in first["humanGates"]])
        self.assertFalse(first["deployment"]["authorized"])

    def test_unknown_domain_is_rejected(self) -> None:
        with self.assertRaisesRegex(ContractError, "unsupported domain"):
            build_contract(self.policy, "Do something", "unknown", [])

    def test_existing_different_contract_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "contract.json"
            contract = build_contract(self.policy, "First", "agent", [])
            self.assertEqual(write_once(output, contract), "created")
            self.assertEqual(write_once(output, contract), "already-exists")
            with self.assertRaisesRegex(ContractError, "refusing to overwrite"):
                write_once(output, build_contract(self.policy, "Second", "agent", []))
            self.assertEqual(json.loads(output.read_text())["objective"], "First")


if __name__ == "__main__":
    unittest.main()
