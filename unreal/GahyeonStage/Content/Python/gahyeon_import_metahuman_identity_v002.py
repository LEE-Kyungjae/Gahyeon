"""Import the verified v002 shape; stop before guided MetaHuman Identity work."""

import json
import os
import sys
from pathlib import Path

import unreal


def main():
    workspace = Path(os.environ.get("GAHYEON_WORKSPACE", Path.cwd())).resolve()
    sys.path.insert(0, str(workspace))
    from character_pipeline.tools.metahuman_identity_session_contract import (
        build_receipt, validate_import,
    )

    session_path = Path(os.environ.get(
        "GAHYEON_IDENTITY_SESSION",
        workspace / "character_pipeline/metahuman/identity/v002/session.json",
    )).resolve()
    receipt_path = Path(os.environ.get(
        "GAHYEON_IDENTITY_IMPORT_RECEIPT",
        workspace / "character_pipeline/metahuman/identity/v002/import-receipt.json",
    )).resolve()
    if receipt_path.exists():
        raise RuntimeError(f"refusing to overwrite immutable receipt: {receipt_path}")
    session, source = validate_import(session_path)
    destination = "/Game/Gahyeon/CharacterPipeline/v002/IdentityInput"
    asset_name = "SM_Gahyeon_IdentityInput_v002"
    expected = f"{destination}/{asset_name}"
    if unreal.EditorAssetLibrary.does_asset_exist(expected):
        raise RuntimeError(f"refusing to replace existing Identity input: {expected}")

    task = unreal.AssetImportTask()
    task.set_editor_property("filename", str(source))
    task.set_editor_property("destination_path", destination)
    task.set_editor_property("destination_name", asset_name)
    task.set_editor_property("automated", True)
    task.set_editor_property("replace_existing", False)
    task.set_editor_property("save", True)
    unreal.AssetToolsHelpers.get_asset_tools().import_asset_tasks([task])
    imported = list(task.get_editor_property("imported_object_paths"))
    if imported != [expected] or not unreal.EditorAssetLibrary.does_asset_exist(expected):
        raise RuntimeError(f"Identity shape import mismatch: {imported}")
    asset = unreal.EditorAssetLibrary.load_asset(expected)
    if asset is None or asset.get_class().get_name() != "StaticMesh":
        raise RuntimeError("Identity input did not produce exactly one StaticMesh")

    receipt = build_receipt(session_path, session, expected)
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    unreal.log(json.dumps(receipt))
    raise RuntimeError(
        "v002 shape imported; guided Components From Mesh, marker correction and Identity Solve remain required"
    )


main()
