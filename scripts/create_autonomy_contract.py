#!/usr/bin/env python3
"""Create a deterministic, machine-readable Gahyeon work contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any


class ContractError(ValueError):
    pass


def load_policy(path: Path) -> dict[str, Any]:
    policy = json.loads(path.read_text(encoding="utf-8"))
    if policy.get("schemaVersion") != 1 or not isinstance(policy.get("domains"), dict):
        raise ContractError("unsupported autonomy policy")
    return policy


def build_contract(
    policy: dict[str, Any], objective: str, domain: str, constraints: list[str]
) -> dict[str, Any]:
    objective = objective.strip()
    if not objective:
        raise ContractError("objective must not be blank")
    domains = policy["domains"]
    if domain not in domains:
        raise ContractError(f"unsupported domain: {domain}")
    normalized_constraints = sorted({item.strip() for item in constraints if item.strip()})
    identity = json.dumps(
        {"policyId": policy["policyId"], "objective": objective, "domain": domain,
         "constraints": normalized_constraints},
        ensure_ascii=False,
        sort_keys=True,
    )
    contract_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:16]
    domain_policy = domains[domain]
    return {
        "schemaVersion": 1,
        "contractId": contract_id,
        "policyId": policy["policyId"],
        "objective": objective,
        "domain": domain,
        "constraints": normalized_constraints,
        "requiredGates": [
            {"name": name, "status": "pending", "evidence": None}
            for name in domain_policy["requiredGates"]
        ],
        "humanGates": [
            {"name": name, "status": "pending", "approvedBy": None, "evidence": None}
            for name in domain_policy["humanGates"]
        ],
        "deployment": {
            "authorized": False,
            "identity": {name: None for name in policy["defaultCanary"]["requiredIdentity"]},
            "canary": policy["defaultCanary"],
        },
        "status": "active",
    }


def write_once(path: Path, contract: dict[str, Any]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        existing = json.loads(path.read_text(encoding="utf-8"))
        if existing == contract:
            return "already-exists"
        raise ContractError(f"refusing to overwrite a different contract: {path}")
    descriptor, temporary_name = tempfile.mkstemp(dir=path.parent, text=True)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(contract, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise
    return "created"


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--objective", required=True)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--constraint", action="append", default=[])
    parser.add_argument("--policy", type=Path, default=root / "quality/autonomy-policy.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    contract = build_contract(load_policy(args.policy), args.objective, args.domain, args.constraint)
    status = write_once(args.output, contract)
    print(json.dumps({"status": status, "contractId": contract["contractId"],
                      "output": str(args.output.resolve())}, ensure_ascii=False))


if __name__ == "__main__":
    main()
