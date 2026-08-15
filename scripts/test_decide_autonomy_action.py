#!/usr/bin/env python3

import unittest

from decide_autonomy_action import decide


def contract() -> dict:
    return {
        "schemaVersion": 1,
        "status": "active",
        "requiredGates": [
            {"name": "impact-analysis", "status": "pending"},
            {"name": "ai-quality-gate", "status": "pending"},
        ],
        "humanGates": [{"name": "production-deploy", "status": "pending"}],
        "deployment": {
            "authorized": False,
            "identity": {"sourceCommit": None, "artifactDigest": None,
                         "deploymentRevision": None, "configurationIdentity": None},
        },
    }


class DecideAutonomyActionTest(unittest.TestCase):
    def test_returns_only_the_first_unfinished_machine_gate(self) -> None:
        self.assertEqual(decide(contract()), {"action": "run-required-gate", "gate": "impact-analysis"})

    def test_requests_human_only_after_machine_gates_pass(self) -> None:
        state = contract()
        for gate in state["requiredGates"]:
            gate["status"] = "passed"
        self.assertEqual(decide(state), {"action": "request-human-gate", "gate": "production-deploy"})

    def test_routes_canary_outcomes_without_human_intervention(self) -> None:
        state = contract()
        for gate in state["requiredGates"]:
            gate["status"] = "passed"
        state["humanGates"][0].update(status="approved", approvedBy="owner")
        state["deployment"]["authorized"] = True
        state["deployment"]["identity"] = {
            "sourceCommit": "abc", "artifactDigest": "sha256:def",
            "deploymentRevision": "green-42", "configurationIdentity": "prod-v3",
        }
        self.assertEqual(decide(state, {"verdict": "rollback"})["action"], "rollback")
        self.assertEqual(decide(state, {"verdict": "promote"})["action"], "promote")
        self.assertEqual(
            decide(state, {"verdict": "inconclusive-no-traffic"})["action"],
            "collect-canary-observation",
        )


if __name__ == "__main__":
    unittest.main()
