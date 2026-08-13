"""Run the accepted v002 MetaHuman conform job and emit a narrow receipt."""

import hashlib
import json
import os
from pathlib import Path

import unreal


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    workspace = Path(os.environ.get("GAHYEON_WORKSPACE", Path.cwd())).resolve()
    job_path = Path(os.environ.get(
        "GAHYEON_CONFORM_JOB",
        workspace / "character_pipeline/metahuman/conform/v002/conform-job.json",
    )).resolve()
    receipt_path = Path(os.environ.get(
        "GAHYEON_CONFORM_RECEIPT",
        workspace / "character_pipeline/metahuman/conform/v002/conform-receipt.json",
    )).resolve()
    if receipt_path.exists():
        raise RuntimeError(f"refusing to overwrite immutable conform receipt: {receipt_path}")
    job = json.loads(job_path.read_text(encoding="utf-8"))
    evidence = Path(job["solvedIdentityEvidence"]["path"])
    if not evidence.is_file() or evidence.is_symlink() or sha256(evidence) != job["solvedIdentityEvidence"]["sha256"]:
        raise RuntimeError("v002 conform evidence lineage differs")
    if (job.get("state") != "ready-to-conform-from-reviewed-identity"
            or job.get("requiredResult") != "SUCCESS" or job.get("productionReady") is not False
            or job.get("qualityClaim") is not None):
        raise RuntimeError("v002 conform job overclaims readiness")
    identity = unreal.load_asset(job["identityAsset"]["path"])
    character = unreal.load_asset(job["characterAsset"]["path"])
    if identity is None or identity.get_class().get_name() != "MetaHumanIdentity":
        raise RuntimeError("reviewed MetaHumanIdentity asset missing")
    if character is None or character.get_class().get_name() != "MetaHumanCharacter":
        raise RuntimeError("target MetaHumanCharacter asset missing")
    subsystem = unreal.get_editor_subsystem(unreal.MetaHumanCharacterEditorSubsystem)
    if not subsystem.try_add_object_to_edit(character):
        raise RuntimeError("MetaHuman Character is unavailable for editing")
    try:
        params = unreal.ImportFromIdentityParams()
        params.use_eye_meshes = True
        params.use_teeth_mesh = True
        params.use_metric_scale = True
        result = subsystem.import_from_identity(character, identity, params)
        if result != unreal.ImportErrorCode.SUCCESS:
            raise RuntimeError(f"import_from_identity failed: {result}")
        subsystem.commit_face_state(character)
        if not unreal.EditorAssetLibrary.save_loaded_asset(character, only_if_is_dirty=False):
            raise RuntimeError("failed to save conformed MetaHuman Character")
    finally:
        subsystem.remove_object_to_edit(character)
    receipt = {
        "schemaVersion": 1, "state": "conformed-head-awaiting-production-systems",
        "job": {"path": str(job_path), "sha256": sha256(job_path)},
        "identityAsset": job["identityAsset"], "characterAsset": job["characterAsset"],
        "params": job["params"], "result": "SUCCESS", "headConformed": True,
        "faceRigGenerated": False, "highResolutionTexturesDownloaded": False,
        "bodyConformed": False, "groomBound": False, "clothingBound": False,
        "deformationValidated": False, "lookingGlassGoValidated": False,
        "automaticApproval": False, "productionReady": False, "qualityClaim": None,
    }
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    unreal.log(json.dumps(receipt))
    raise RuntimeError("head conformed; rig, surface, groom, body, deformation and Go QA remain required")


main()
