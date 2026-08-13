"""Run blocking MetaHuman Cloud rig and texture-source requests; verify assets separately."""
import hashlib,json,os
from pathlib import Path
import unreal
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
 w=Path(os.environ.get("GAHYEON_WORKSPACE",Path.cwd())).resolve();job_path=Path(os.environ.get("GAHYEON_CLOUD_JOB",w/"character_pipeline/metahuman/conform/v001/cloud-enrichment-job.json")).resolve();out=Path(os.environ.get("GAHYEON_CLOUD_RECEIPT",w/"character_pipeline/metahuman/conform/v001/cloud-enrichment-receipt.json")).resolve()
 if out.exists():raise RuntimeError(f"refusing to overwrite immutable cloud receipt: {out}")
 j=json.loads(job_path.read_text())
 for k in ("conformJob","conformReceipt"):
  p=Path(j[k]["path"])
  if not p.is_file() or p.is_symlink() or sha(p)!=j[k]["sha256"]:raise RuntimeError(f"cloud lineage differs: {k}")
 character=unreal.load_asset(j["characterAsset"]["path"])
 if character is None or character.get_class().get_name()!="MetaHumanCharacter":raise RuntimeError("cloud target MetaHumanCharacter missing")
 subsystem=unreal.get_editor_subsystem(unreal.MetaHumanCharacterEditorSubsystem)
 if not subsystem.try_add_object_to_edit(character):raise RuntimeError("MetaHuman Character unavailable for cloud enrichment")
 try:
  rig=unreal.MetaHumanCharacterAutoRiggingRequestParams();rig.blocking=True;rig.report_progress=False;rig.rig_type=unreal.MetaHumanRigType.JOINTS_AND_BLENDSHAPES
  subsystem.request_auto_rigging(character,rig)
  textures=unreal.MetaHumanCharacterTextureRequestParams();textures.blocking=True;textures.report_progress=False
  subsystem.request_texture_sources(character,textures)
  if not unreal.EditorAssetLibrary.save_loaded_asset(character,only_if_is_dirty=False):raise RuntimeError("failed to save cloud-enriched character")
 finally:subsystem.remove_object_to_edit(character)
 value={"schemaVersion":1,"state":"cloud-requests-completed-awaiting-asset-verification","job":{"path":str(job_path),"sha256":sha(job_path)},"characterAsset":j["characterAsset"],"requestsReturned":["auto-rig","texture-sources"],"rigType":"JOINTS_AND_BLENDSHAPES","faceRigVerified":False,"blendshapesVerified":False,"textureSourcesVerified":False,"automaticApproval":False,"productionReady":False}
 out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(value,indent=2)+"\n");unreal.log(json.dumps(value));raise RuntimeError("cloud requests returned; rig, blendshape and texture assets still require explicit verification")
main()
