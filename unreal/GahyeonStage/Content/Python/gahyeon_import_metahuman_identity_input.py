"""Import a reviewed P29 static mesh into UE 5.6; never solves or approves Identity."""

import hashlib,json,os
from pathlib import Path
import unreal

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def main():
 workspace=Path(os.environ.get("GAHYEON_WORKSPACE",Path.cwd())).resolve()
 job_path=Path(os.environ.get("GAHYEON_IDENTITY_IMPORT_JOB",workspace/"character_pipeline/metahuman/identity/v001/selected-import-job.json")).resolve()
 receipt_path=Path(os.environ.get("GAHYEON_IDENTITY_IMPORT_RECEIPT",workspace/"character_pipeline/metahuman/identity/v001/selected-import-receipt.json")).resolve()
 if receipt_path.exists(): raise RuntimeError(f"refusing to overwrite immutable receipt: {receipt_path}")
 job=json.loads(job_path.read_text()); source=Path(job["source"]["path"])
 if job.get("state")!="ready-for-unreal-editor-import" or job.get("engine")!="5.6" or job.get("productionMeshAllowed") is not False: raise RuntimeError("identity import job overclaims state")
 if not source.is_file() or source.is_symlink() or sha(source)!=job["source"]["sha256"]: raise RuntimeError("identity import source checksum differs")
 expected=job["expectedAssetPath"]
 if unreal.EditorAssetLibrary.does_asset_exist(expected): raise RuntimeError(f"refusing to replace existing identity input: {expected}")
 task=unreal.AssetImportTask();task.set_editor_property("filename",str(source));task.set_editor_property("destination_path",job["import"]["destinationPath"]);task.set_editor_property("destination_name",job["import"]["assetName"]);task.set_editor_property("automated",True);task.set_editor_property("replace_existing",False);task.set_editor_property("save",True)
 options=unreal.FbxImportUI();options.set_editor_property("import_mesh",True);options.set_editor_property("import_as_skeletal",False);options.set_editor_property("import_materials",True);options.set_editor_property("import_textures",True);options.static_mesh_import_data.set_editor_property("combine_meshes",True);options.static_mesh_import_data.set_editor_property("convert_scene",False);options.static_mesh_import_data.set_editor_property("convert_scene_unit",False);task.set_editor_property("options",options)
 unreal.AssetToolsHelpers.get_asset_tools().import_asset_tasks([task]); imported=list(task.get_editor_property("imported_object_paths"))
 if imported!=[expected] or not unreal.EditorAssetLibrary.does_asset_exist(expected): raise RuntimeError(f"identity static mesh import mismatch: {imported}")
 asset=unreal.EditorAssetLibrary.load_asset(expected)
 if asset is None or asset.get_class().get_name()!="StaticMesh": raise RuntimeError("identity input is not a StaticMesh")
 receipt={"schemaVersion":1,"state":"imported-awaiting-identity-guided-workflow","job":{"path":str(job_path),"sha256":sha(job_path)},"source":{"path":str(source),"sha256":sha(source)},"asset":{"path":expected,"class":"StaticMesh"},"combineMeshes":True,"identityAsset":None,"identitySolved":False,"templateMeshConformed":False,"dnaOrRigLogicBound":False,"automaticApproval":False,"productionMeshAllowed":False}
 receipt_path.parent.mkdir(parents=True,exist_ok=True);receipt_path.write_text(json.dumps(receipt,indent=2)+"\n");unreal.log(json.dumps(receipt));raise RuntimeError("static mesh imported; guided Components From Mesh, neutral tracking and Identity Solve remain required")

main()
