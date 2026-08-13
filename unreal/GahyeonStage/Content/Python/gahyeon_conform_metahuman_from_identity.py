"""Conform an existing MetaHumanCharacter from reviewed solved Identity evidence."""
import hashlib,json,os
from pathlib import Path
import unreal
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
 workspace=Path(os.environ.get("GAHYEON_WORKSPACE",Path.cwd())).resolve();job_path=Path(os.environ.get("GAHYEON_CONFORM_JOB",workspace/"character_pipeline/metahuman/conform/v001/selected-conform-job.json")).resolve();receipt=Path(os.environ.get("GAHYEON_CONFORM_RECEIPT",workspace/"character_pipeline/metahuman/conform/v001/selected-conform-receipt.json")).resolve()
 if receipt.exists():raise RuntimeError(f"refusing to overwrite immutable conform receipt: {receipt}")
 job=json.loads(job_path.read_text())
 for key in ("importJob","importReceipt","solvedIdentityReceipt"):
  p=Path(job[key]["path"])
  if not p.is_file() or p.is_symlink() or sha(p)!=job[key]["sha256"]:raise RuntimeError(f"conform lineage differs: {key}")
 if job.get("state")!="ready-to-conform-from-identity" or job.get("requiredResult")!="SUCCESS" or job.get("productionReady") is not False:raise RuntimeError("conform job overclaims readiness")
 identity=unreal.load_asset(job["identityAsset"]["path"]);character=unreal.load_asset(job["characterAsset"]["path"])
 if identity is None or identity.get_class().get_name()!="MetaHumanIdentity":raise RuntimeError("reviewed MetaHumanIdentity asset missing")
 if character is None or character.get_class().get_name()!="MetaHumanCharacter":raise RuntimeError("target MetaHumanCharacter asset missing")
 subsystem=unreal.get_editor_subsystem(unreal.MetaHumanCharacterEditorSubsystem)
 if not subsystem.try_add_object_to_edit(character):raise RuntimeError("MetaHuman Character is unavailable for editing")
 try:
  params=unreal.ImportFromIdentityParams();params.use_eye_meshes=True;params.use_teeth_mesh=True;params.use_metric_scale=True
  result=subsystem.import_from_identity(character,identity,params)
  if result!=unreal.ImportErrorCode.SUCCESS:raise RuntimeError(f"import_from_identity failed: {result}")
  subsystem.commit_face_state(character)
  if not unreal.EditorAssetLibrary.save_loaded_asset(character,only_if_is_dirty=False):raise RuntimeError("failed to save conformed MetaHuman Character")
 finally:subsystem.remove_object_to_edit(character)
 value={"schemaVersion":1,"state":"conformed-head-awaiting-production-systems","job":{"path":str(job_path),"sha256":sha(job_path)},"identityAsset":job["identityAsset"],"characterAsset":job["characterAsset"],"params":job["params"],"result":"SUCCESS","headConformed":True,"faceRigGenerated":False,"highResolutionTexturesDownloaded":False,"bodyConformed":False,"groomBound":False,"clothingBound":False,"deformationValidated":False,"lookingGlassGoValidated":False,"automaticApproval":False,"productionReady":False}
 receipt.parent.mkdir(parents=True,exist_ok=True);receipt.write_text(json.dumps(value,indent=2)+"\n");unreal.log(json.dumps(value));raise RuntimeError("head conformed; auto-rig, surfaces, groom, body, deformation and Go QA remain required")
main()
