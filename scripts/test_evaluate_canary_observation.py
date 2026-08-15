#!/usr/bin/env python3

import copy
import json
import unittest
from pathlib import Path

from create_autonomy_contract import build_contract, load_policy
from evaluate_canary_observation import ObservationError, evaluate


ROOT = Path(__file__).resolve().parents[1]


class EvaluateCanaryObservationTest(unittest.TestCase):
    def setUp(self) -> None:
        policy = load_policy(ROOT / "quality/autonomy-policy.json")
        self.contract = build_contract(policy, "Improve voice", "tts", [])
        self.identity = {
            "sourceCommit": "abc123",
            "artifactDigest": "sha256:def456",
            "deploymentRevision": "green-42",
            "configurationIdentity": "prod-v3",
        }
        self.contract["deployment"]["identity"] = copy.deepcopy(self.identity)

    def observation(self, **overrides) -> dict:
        value = {
            "identity": copy.deepcopy(self.identity),
            "observationSeconds": 1800,
            "qualifyingRequests": 30,
            "metrics": {
                "errorRate": 0.0,
                "p95LatencyRegressionMs": 0,
                "unexpectedTranscriptCount": 0,
                "emptyAudioCount": 0,
            },
        }
        value.update(overrides)
        return value

    def test_zero_traffic_is_inconclusive(self) -> None:
        self.assertEqual(
            evaluate(self.contract, self.observation(qualifyingRequests=0))["verdict"],
            "inconclusive-no-traffic",
        )

    def test_threshold_violation_rolls_back_even_before_minimum_window(self) -> None:
        observation = self.observation(observationSeconds=60, qualifyingRequests=2)
        observation["metrics"]["emptyAudioCount"] = 1
        self.assertEqual(evaluate(self.contract, observation)["verdict"], "rollback")

    def test_clean_but_short_window_continues_observing(self) -> None:
        self.assertEqual(
            evaluate(self.contract, self.observation(observationSeconds=120))["verdict"],
            "continue-observing",
        )

    def test_complete_clean_window_promotes(self) -> None:
        self.assertEqual(evaluate(self.contract, self.observation())["verdict"], "promote")

    def test_identity_mismatch_is_rejected(self) -> None:
        observation = self.observation()
        observation["identity"]["deploymentRevision"] = "unknown"
        with self.assertRaisesRegex(ObservationError, "identity mismatch"):
            evaluate(self.contract, observation)


if __name__ == "__main__":
    unittest.main()
