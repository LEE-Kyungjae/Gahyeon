"""Collect facial semantic bindings from a real Gahyeon presentation profile in UE 5.6."""
import hashlib,json,os
from pathlib import Path
import unreal

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def prop(obj,name):return obj.get_editor_property(name)
def binding(group,semantic,curve,scale,acknowledged,route):
 return {"group":group,"semantic":str(semantic),"curve":str(curve),"scale":float(scale),"route":route,"animBridgeAcknowledged":bool(acknowledged) if route=="control-rig-curve" else None}
def main():
 w=Path(os.environ.get("GAHYEON_WORKSPACE",Path.cwd())).resolve();request_path=Path(os.environ.get("GAHYEON_FACIAL_MAPPING_REQUEST",w/"character_pipeline/metahuman/validation/v001/facial-mapping-evidence-request.json")).resolve();cloud_path=Path(os.environ.get("GAHYEON_CLOUD_EVIDENCE",w/"character_pipeline/metahuman/conform/v001/cloud-asset-evidence.json")).resolve();out=Path(os.environ.get("GAHYEON_FACIAL_MAPPING_EVIDENCE",w/"character_pipeline/metahuman/validation/v001/facial-mapping-evidence.json")).resolve()
 if out.exists():raise RuntimeError(f"refusing to overwrite immutable facial mapping evidence: {out}")
 request=json.loads(request_path.read_text());profile_path=os.environ.get("GAHYEON_PRESENTATION_PROFILE") or request.get("presentationProfile")
 if not profile_path or not unreal.EditorAssetLibrary.does_asset_exist(profile_path):raise RuntimeError("real Gahyeon presentation profile asset is required")
 profile=unreal.load_asset(profile_path);error="";valid=False
 try:valid,error=profile.validate()
 except Exception as ex:raise RuntimeError(f"cannot execute presentation profile validation: {ex}")
 if not valid:raise RuntimeError(f"presentation profile validation failed: {error}")
 acknowledgements={x["curve"]:x for x in request.get("controlRigCurveAcknowledgements",[]) if x.get("animBridgeAcknowledged") is True}
 def route(curve,target):
  is_control="CONTROL_RIG" in str(target).upper();name="control-rig-curve" if is_control else "direct-morph"
  if is_control and str(curve) not in acknowledgements:raise RuntimeError(f"Control Rig target lacks bridge acknowledgement: {curve}")
  return name,is_control
 bindings=[]
 for semantic,curve,target in (("blink-left",prop(profile,"left_blink_curve"),prop(profile,"left_blink_target")),("blink-right",prop(profile,"right_blink_curve"),prop(profile,"right_blink_target")),("jaw-open",prop(profile,"jaw_open_curve"),prop(profile,"jaw_open_target"))):
  r,a=route(curve,target);bindings.append(binding("direct",semantic,curve,1.0,a,r))
 for group,property_name in (("emotion","emotion_curves"),("viseme","viseme_curves")):
  for item in prop(profile,property_name):
   curve=prop(item,"curve_name");r,a=route(curve,prop(item,"target"));bindings.append(binding(group,prop(item,"semantic"),curve,prop(item,"scale"),a,r))
 source=(w/"unreal/GahyeonStage/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationProfile.cpp").read_text();anim=(w/"unreal/GahyeonStage/Source/GahyeonStage/Private/Animation/GahyeonCharacterAnimInstance.cpp").read_text()
 checks={"presentationProfileExists":True,"profileValidationPassed":valid,"runtimeResolverPresent":"ResolveFacialCurveWeights" in source,"animInstanceBridgePresent":"ApplyFacialControlRigCurves_Implementation" in anim and "GetFacialControlRigCurveWeights_Implementation" in anim}
 value={"schemaVersion":1,"state":"editor-facial-mapping-observed","engine":"5.6","editorRuntimeVerified":True,"request":{"path":str(request_path),"sha256":sha(request_path)},"cloudAssetEvidence":{"path":str(cloud_path),"sha256":sha(cloud_path)},"presentationProfile":{"path":profile_path,"class":profile.get_class().get_name()},"displayProfile":{"id":"looking-glass-go","resolution":[1440,2560],"viewCount":66},"bindings":bindings,"checks":checks,"automaticApproval":False,"deformationVerified":False,"productionReady":False,"warning":"Semantic coverage is not deformation, lip-sync, identity, close-up or production approval."}
 out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(value,indent=2)+"\n");unreal.log(json.dumps(value))
 if not all(checks.values()):raise RuntimeError("facial mapping evidence failed closed")
main()
