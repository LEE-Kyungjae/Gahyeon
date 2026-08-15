#!/usr/bin/env python3
"""Return the single next action for a Gahyeon autonomous work contract."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


class StateError(ValueError):
    pass


def decide(contract: dict[str, Any], canary_verdict: dict[str, Any] | None = None) -> dict[str, str]:
    if contract.get("schemaVersion") != 1 or contract.get("status") != "active":
        raise StateError("contract must be an active schemaVersion 1 contract")
    for gate in contract.get("requiredGates", []):
        if gate.get("status") != "passed":
            if gate.get("name") == "blind-listening":
                return {"action": "request-human-gate", "gate": "blind-listening"}
            return {"action": "run-required-gate", "gate": str(gate.get("name"))}
    for gate in contract.get("humanGates", []):
        if gate.get("status") != "approved":
            return {"action": "request-human-gate", "gate": str(gate.get("name"))}
    deployment = contract.get("deployment", {})
    if deployment.get("authorized") is not True:
        return {"action": "request-human-gate", "gate": "production-deploy"}
    identity = deployment.get("identity", {})
    if any(not str(value or "").strip() for value in identity.values()):
        return {"action": "record-deployment-identity", "gate": "deployment-identity"}
    if canary_verdict is None:
        return {"action": "collect-canary-observation", "gate": "canary"}
    verdict = canary_verdict.get("verdict")
    if verdict in {"inconclusive-no-traffic", "continue-observing"}:
        return {"action": "collect-canary-observation", "gate": "canary"}
    if verdict == "rollback":
        return {"action": "rollback", "gate": "canary"}
    if verdict == "promote":
        return {"action": "promote", "gate": "canary"}
    raise StateError(f"unsupported canary verdict: {verdict}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--canary-verdict", type=Path)
    args = parser.parse_args()
    contract = json.loads(args.contract.read_text(encoding="utf-8"))
    verdict = (json.loads(args.canary_verdict.read_text(encoding="utf-8"))
               if args.canary_verdict else None)
    print(json.dumps(decide(contract, verdict), ensure_ascii=False))


if __name__ == "__main__":
    main()
