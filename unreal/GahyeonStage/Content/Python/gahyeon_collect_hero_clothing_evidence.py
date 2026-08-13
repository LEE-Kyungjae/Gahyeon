"""Collect actual modular clothing and Chaos Cloth dependencies; approves nothing."""
import json, os
from pathlib import Path
import unreal

EXPECTED={"skeletalMesh":"SkeletalMesh","materials":"MaterialInstanceConstant","physicsAsset":"PhysicsAsset","clothConfig":"ChaosClothConfig","clothDataflow":"Dataflow"}
def observed(path,expected):
 exists=bool(path) and unreal.EditorAssetLibrary.does_asset_exist(path);obj=unreal.load_asset(path) if exists else None;cls=obj.get_class().get_name() if obj else None
 if cls!=expected: raise RuntimeError(f"expected {expected}: {path}: {cls}")
 return {"path":path,"exists":bool(obj),"class":cls}
def main():
 request_path=Path(os.environ["GAHYEON_CLOTHING_EVIDENCE_REQUEST"]).resolve();output=Path(os.environ["GAHYEON_CLOTHING_EVIDENCE_OUTPUT"]).resolve()
 if output.exists(): raise RuntimeError(f"refusing to overwrite immutable evidence: {output}")
 request=json.loads(request_path.read_text());assets={name:observed(request["assets"][name],expected) for name,expected in EXPECTED.items()};slots={name:observed(path,"SkeletalMesh") for name,path in request["slots"].items()}
 checks=dict(request.get("runtimeChecks",{}));penetrations=int(request.get("observedBodyPenetrations",-1));structural={"allAssetsObserved":all(x["exists"] for x in assets.values()),"allSlotsObserved":all(x["exists"] for x in slots.values()),"slotsAreSeparate":len({x["path"] for x in slots.values()})==len(slots),"runtimeChecksComplete":bool(checks) and all(checks.values()),"zeroBodyPenetrations":penetrations==0}
 value={"schemaVersion":1,"kind":"metahuman-production-clothing-editor-evidence","engine":"5.6","editorRuntimeVerified":True,"assets":assets,"slots":slots,"observedBodyPenetrations":penetrations,"checks":checks,"structuralChecks":structural,"automaticApproval":False,"qualityClaim":None}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(value,indent=2)+"\n");unreal.log(json.dumps(value))
 if not all(structural.values()): raise RuntimeError("hero clothing evidence failed closed")
main()
