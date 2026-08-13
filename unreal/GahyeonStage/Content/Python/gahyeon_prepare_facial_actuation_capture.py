"""Fail-closed UE 5.6 preflight for the immutable 57-sample facial actuation capture."""
import hashlib,json,os
from pathlib import Path
import unreal
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
 w=Path(os.environ.get("GAHYEON_WORKSPACE",Path.cwd())).resolve();job_path=Path(os.environ["GAHYEON_FACIAL_ACTUATION_JOB"]).resolve();receipt=Path(os.environ.get("GAHYEON_FACIAL_ACTUATION_PREFLIGHT",w/"artifacts/gahyeon-ch/facial-actuation-preflight.json")).resolve()
 if receipt.exists():raise RuntimeError(f"refusing to overwrite immutable facial actuation preflight: {receipt}")
 job=json.loads(job_path.read_text());profile_path=os.environ.get("GAHYEON_PRESENTATION_PROFILE");hero_path=os.environ.get("GAHYEON_HERO_BLUEPRINT")
 profile=unreal.load_asset(profile_path) if profile_path and unreal.EditorAssetLibrary.does_asset_exist(profile_path) else None;hero=unreal.load_asset(hero_path) if hero_path and unreal.EditorAssetLibrary.does_asset_exist(hero_path) else None
 face_anim_class_path=os.environ.get("GAHYEON_FACE_ANIM_BLUEPRINT_CLASS");face_anim_class=unreal.load_class(None,face_anim_class_path) if face_anim_class_path else None;base_anim_class=unreal.load_class(None,"/Script/GahyeonStage.GahyeonCharacterAnimInstance")
 sequence_path=job["sequence"]["path"];preset_path=job["sequence"]["moviePipelinePreset"]
 checks={"engineContract":job.get("engine")=="5.6","exactSampleCount":job.get("expectedSamples")==57,"lookingGlassGo":job.get("displayProfile",{}).get("resolution")==[1440,2560] and job.get("displayProfile",{}).get("viewCount")==66,"presentationProfileExists":profile is not None,"heroBlueprintExists":hero is not None,"nativeFaceAnimBridgeAvailable":base_anim_class is not None,"faceAnimBlueprintClassExists":face_anim_class is not None,"faceAnimBlueprintInheritsNativeBridge":bool(face_anim_class and base_anim_class and face_anim_class.is_child_of(base_anim_class)),"levelSequenceDoesNotExist":not unreal.EditorAssetLibrary.does_asset_exist(sequence_path),"moviePipelinePresetExists":unreal.EditorAssetLibrary.does_asset_exist(preset_path),"neverOverwrite":job.get("sequence",{}).get("neverOverwrite") is True}
 value={"schemaVersion":1,"state":"ready-to-author-facial-actuation-sequence" if all(checks.values()) else "blocked","engine":"5.6","job":{"path":str(job_path),"sha256":sha(job_path)},"profile":profile_path,"hero":hero_path,"sequence":sequence_path,"preset":preset_path,"checks":checks,"automaticApproval":False,"deformationVerified":False,"productionReady":False,"nextAction":"Author LevelSequence tracks, drive semantic inputs through UGahyeonCharacterPresentationProfile, capture 57 unique MRQ frames, and export evaluated curve weights."}
 receipt.parent.mkdir(parents=True,exist_ok=True);receipt.write_text(json.dumps(value,indent=2)+"\n");unreal.log(json.dumps(value))
 if not all(checks.values()):raise RuntimeError("facial actuation capture preflight failed closed")
 raise RuntimeError("preflight passed; sequence authoring and Movie Render Queue capture remain intentionally explicit")
main()
