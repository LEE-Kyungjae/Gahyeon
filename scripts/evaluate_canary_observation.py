#!/usr/bin/env python3
"""Evaluate production evidence without confusing no traffic with success."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


class ObservationError(ValueError):
    pass


OPERATORS = {
    ">": lambda actual, threshold: actual > threshold,
    ">=": lambda actual, threshold: actual >= threshold,
    "<": lambda actual, threshold: actual < threshold,
    "<=": lambda actual, threshold: actual <= threshold,
    "==": lambda actual, threshold: actual == threshold,
}


def evaluate(contract: dict[str, Any], observation: dict[str, Any]) -> dict[str, Any]:
    canary = contract.get("deployment", {}).get("canary", {})
    required_identity = canary.get("requiredIdentity", [])
    identity = observation.get("identity", {})
    missing_identity = [name for name in required_identity if not str(identity.get(name, "")).strip()]
    if missing_identity:
        raise ObservationError(f"missing deployment identity: {', '.join(missing_identity)}")
    expected_identity = contract.get("deployment", {}).get("identity", {})
    mismatches = [name for name in required_identity
                  if expected_identity.get(name) and expected_identity[name] != identity.get(name)]
    if mismatches:
        raise ObservationError(f"observation identity mismatch: {', '.join(mismatches)}")

    qualifying = int(observation.get("qualifyingRequests", -1))
    duration = int(observation.get("observationSeconds", -1))
    if qualifying < 0 or duration < 0:
        raise ObservationError("observation counts must be non-negative")
    if qualifying == 0:
        return {"verdict": "inconclusive-no-traffic", "reasons": ["zero qualifying requests"]}

    metrics = observation.get("metrics", {})
    violations = []
    for threshold in canary.get("rollbackThresholds", []):
        metric = threshold["metric"]
        operator = threshold["operator"]
        if operator not in OPERATORS:
            raise ObservationError(f"unsupported threshold operator: {operator}")
        if metric not in metrics or not isinstance(metrics[metric], (int, float)):
            raise ObservationError(f"missing numeric canary metric: {metric}")
        if OPERATORS[operator](metrics[metric], threshold["value"]):
            violations.append(
                f"{metric}={metrics[metric]} {operator} {threshold['value']}"
            )
    if violations:
        return {"verdict": "rollback", "reasons": violations}

    evidence_gaps = []
    if qualifying < int(canary["minimumQualifyingRequests"]):
        evidence_gaps.append(
            f"qualifyingRequests={qualifying} < {canary['minimumQualifyingRequests']}"
        )
    if duration < int(canary["minimumObservationSeconds"]):
        evidence_gaps.append(
            f"observationSeconds={duration} < {canary['minimumObservationSeconds']}"
        )
    if evidence_gaps:
        return {"verdict": "continue-observing", "reasons": evidence_gaps}
    return {"verdict": "promote", "reasons": ["all thresholds and evidence minima passed"]}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--observation", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = evaluate(
        json.loads(args.contract.read_text(encoding="utf-8")),
        json.loads(args.observation.read_text(encoding="utf-8")),
    )
    encoded = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)


if __name__ == "__main__":
    main()
