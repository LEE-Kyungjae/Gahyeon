"""Collect read-only MetaHuman asset evidence in Unreal Editor; never creates or approves assets."""

import hashlib
import json
import os
from pathlib import Path

import unreal


def sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def asset_evidence(path):
    if not path:
        return {"path": None, "exists": False, "class": None, "package": None}
    object_path = path.split(".")[0]
    exists = unreal.EditorAssetLibrary.does_asset_exist(object_path)
    if not exists:
        return {"path": path, "exists": False, "class": None, "package": None}
    asset = unreal.EditorAssetLibrary.load_asset(object_path)
    return {
        "path": path,
        "exists": asset is not None,
        "class": asset.get_class().get_name() if asset else None,
        "package": object_path,
    }


def collect_asset_evidence(binding):
    assets = binding["assets"]
    single = {
        name: asset_evidence(assets.get(name))
        for name in (
            "metahumanIdentity", "heroBlueprint", "faceSkeletalMesh",
            "bodySkeletalMesh", "faceControlRig", "bodyControlRig",
        )
    }
    grooms = [asset_evidence(path) for path in assets.get("grooms", [])]
    hero_path = assets.get("heroBlueprint")
    hero_class = unreal.load_class(None, hero_path) if hero_path else None
    required_parent = unreal.load_class(None, "/Script/GahyeonStage.GahyeonCharacterPawn")
    checks = {
        "bindingStateIsCandidate": binding.get("state") == "candidate",
        "identityExists": single["metahumanIdentity"]["exists"],
        "heroBlueprintExists": single["heroBlueprint"]["exists"],
        "faceMeshExists": single["faceSkeletalMesh"]["exists"],
        "bodyMeshExists": single["bodySkeletalMesh"]["exists"],
        "faceControlRigExists": single["faceControlRig"]["exists"],
        "bodyControlRigExists": single["bodyControlRig"]["exists"],
        "groomExists": bool(grooms) and all(item["exists"] for item in grooms),
        "heroInheritsGahyeonCharacterPawn": bool(
            hero_class and required_parent and hero_class.is_child_of(required_parent)
        ),
    }
    return {
        "schemaVersion": 1,
        "characterId": "gahyeon",
        "iteration": binding["iteration"],
        "editorRuntimeVerified": True,
        "readyForPostConformRender": all(checks.values()),
        "checks": checks,
        "assets": {**single, "grooms": grooms},
        "dnaOrRigLogicBound": None,
        "qualityClaim": None,
        "warning": "Asset existence does not prove identity, deformation quality, or DNA/RigLogic binding.",
    }


def verify_input_handoff(binding):
    if binding.get("state") != "candidate":
        return {"required": False, "verified": False, "path": None, "sha256": None}
    record = binding.get("inputHandoff", {})
    path = Path(record.get("path", ""))
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise RuntimeError("candidate binding requires an absolute immutable input handoff")
    checksum = sha256(path)
    if checksum != record.get("sha256"):
        raise RuntimeError("candidate input handoff checksum differs")
    value = json.loads(path.read_text(encoding="utf-8"))
    if (value.get("stage") != "metahuman-identity-conform-input" or
            value.get("productionMeshAllowed") is not False or
            value.get("automaticApproval") is not False):
        raise RuntimeError("candidate input handoff overclaims source topology or approval")
    return {"required": True, "verified": True, "path": str(path), "sha256": checksum}


def main():
    workspace = Path(os.environ.get("GAHYEON_WORKSPACE", Path.cwd())).resolve()
    binding_path = workspace / "character_pipeline/metahuman/conform/v001/candidate-binding.json"
    output_path = workspace / "character_pipeline/metahuman/validation/v001/editor-asset-evidence.json"
    binding = json.loads(binding_path.read_text(encoding="utf-8"))
    input_handoff = verify_input_handoff(binding)
    report = collect_asset_evidence(binding)
    report["binding"] = {"path": str(binding_path), "sha256": sha256(binding_path)}
    report["inputHandoff"] = input_handoff
    if output_path.exists():
        raise RuntimeError(f"refusing to overwrite immutable evidence: {output_path}")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    unreal.log(json.dumps(report))
    if not report["readyForPostConformRender"]:
        raise RuntimeError("MetaHuman asset evidence failed closed")


main()
