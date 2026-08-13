"""Collect actual UE 5.6 Groom/binding dependencies; approves nothing."""
import json, os
from pathlib import Path
import unreal

EXPECTED={"groom":"GroomAsset","binding":"GroomBindingAsset","targetSkeletalMesh":"SkeletalMesh","physicsAsset":"PhysicsAsset","materialInstance":"MaterialInstanceConstant"}
def observed(path,expected):
 exists=bool(path) and unreal.EditorAssetLibrary.does_asset_exist(path);obj=unreal.load_asset(path) if exists else None;cls=obj.get_class().get_name() if obj else None
 if cls!=expected: raise RuntimeError(f"expected {expected}: {path}: {cls}")
 return {"path":path,"exists":bool(obj),"class":cls}
def main():
 request_path=Path(os.environ["GAHYEON_GROOM_EVIDENCE_REQUEST"]).resolve();output=Path(os.environ["GAHYEON_GROOM_EVIDENCE_OUTPUT"]).resolve()
 if output.exists(): raise RuntimeError(f"refusing to overwrite immutable evidence: {output}")
 request=json.loads(request_path.read_text());assets={name:observed(request["assets"][name],expected) for name,expected in EXPECTED.items()}
 lods=request.get("lods",[]);modes={x.get("mode") for x in lods};checks=dict(request.get("runtimeChecks",{}))
 structural={"allAssetsObserved":all(x["exists"] for x in assets.values()),"strandsAndFallbackObserved":"strands" in modes and bool(modes&{"cards","mesh"}),"runtimeChecksComplete":bool(checks) and all(checks.values())}
 value={"schemaVersion":1,"kind":"metahuman-production-groom-editor-evidence","engine":"5.6","editorRuntimeVerified":True,"assets":assets,"lods":lods,"checks":checks,"structuralChecks":structural,"automaticApproval":False,"qualityClaim":None}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(value,indent=2)+"\n");unreal.log(json.dumps(value))
 if not all(structural.values()): raise RuntimeError("hero Groom evidence failed closed")
main()
