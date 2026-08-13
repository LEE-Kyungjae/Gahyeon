"""Collect post-P32 face mesh, morph target and 4K texture evidence in Editor."""
import hashlib,json,os
from pathlib import Path
import unreal
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def asset(path,expected):
 exists=bool(path) and unreal.EditorAssetLibrary.does_asset_exist(path);obj=unreal.load_asset(path) if exists else None;name=obj.get_class().get_name() if obj else None
 return obj,{"path":path,"exists":bool(obj),"class":name,"expectedClass":expected}
def main():
 w=Path(os.environ.get("GAHYEON_WORKSPACE",Path.cwd())).resolve();job_path=Path(os.environ.get("GAHYEON_CLOUD_JOB",w/"character_pipeline/metahuman/conform/v001/cloud-enrichment-job.json")).resolve();receipt_path=Path(os.environ.get("GAHYEON_CLOUD_RECEIPT",w/"character_pipeline/metahuman/conform/v001/cloud-enrichment-receipt.json")).resolve();request_path=Path(os.environ.get("GAHYEON_CLOUD_EVIDENCE_REQUEST",w/"character_pipeline/metahuman/conform/v001/cloud-asset-evidence-request.json")).resolve();out=Path(os.environ.get("GAHYEON_CLOUD_EVIDENCE",w/"character_pipeline/metahuman/conform/v001/cloud-asset-evidence.json")).resolve()
 if out.exists():raise RuntimeError(f"refusing to overwrite immutable cloud evidence: {out}")
 job=json.loads(job_path.read_text());receipt=json.loads(receipt_path.read_text());request=json.loads(request_path.read_text())
 if receipt.get("job",{}).get("sha256")!=sha(job_path) or request.get("cloudReceiptSha256")!=sha(receipt_path):raise RuntimeError("cloud asset request lineage differs")
 character,ce=asset(job["characterAsset"]["path"],"MetaHumanCharacter");face,fe=asset(request.get("faceSkeletalMesh"),"SkeletalMesh")
 morphs=[]
 if face:
  try:morphs=sorted(str(x.get_name()) for x in face.get_editor_property("morph_targets"))
  except Exception as ex:raise RuntimeError(f"cannot inspect face morph targets: {ex}")
 textures=[]
 for path in request.get("textureSources",[]):
  obj,item=asset(path,"Texture2D")
  if obj:
   try:item["dimensions"]=[int(obj.blueprint_get_size_x()),int(obj.blueprint_get_size_y())]
   except Exception as ex:raise RuntimeError(f"cannot inspect texture dimensions: {path}: {ex}")
  textures.append(item)
 checks={"faceSkeletalMeshExists":fe["exists"] and fe["class"]=="SkeletalMesh","morphTargetsObserved":bool(morphs),"textureSourcesObserved":bool(textures) and all(x["exists"] and x["class"]=="Texture2D" for x in textures),"rigRequestWasBlendshapeType":receipt.get("rigType")=="JOINTS_AND_BLENDSHAPES"}
 value={"schemaVersion":1,"state":"editor-cloud-assets-observed","engine":"5.6","editorRuntimeVerified":True,"cloudJob":{"path":str(job_path),"sha256":sha(job_path)},"cloudReceipt":{"path":str(receipt_path),"sha256":sha(receipt_path)},"request":{"path":str(request_path),"sha256":sha(request_path)},"rigType":receipt.get("rigType"),"assets":{"character":ce,"faceSkeletalMesh":{**fe,"morphTargets":morphs},"textureSources":textures},"checks":checks,"automaticApproval":False,"productionReady":False,"warning":"Observed rig and texture sources are not final AAA surface, deformation or runtime approval."}
 out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(value,indent=2)+"\n");unreal.log(json.dumps(value));
 if not all(checks.values()):raise RuntimeError("MetaHuman cloud asset evidence failed closed")
main()
