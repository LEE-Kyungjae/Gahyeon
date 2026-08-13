"""Collect real UE 5.6 skin/eye asset evidence; creates and approves nothing."""
import json, os
from pathlib import Path
import unreal

def asset(path, expected=None):
    exists=bool(path) and unreal.EditorAssetLibrary.does_asset_exist(path)
    obj=unreal.load_asset(path) if exists else None
    cls=obj.get_class().get_name() if obj else None
    if expected and cls!=expected: raise RuntimeError(f"asset class differs: {path}: {cls}")
    return obj,{"path":path,"exists":bool(obj),"class":cls}

def main():
    w=Path(os.environ.get("GAHYEON_WORKSPACE",Path.cwd())).resolve()
    request_path=Path(os.environ["GAHYEON_SURFACE_EVIDENCE_REQUEST"]).resolve()
    output=Path(os.environ["GAHYEON_SURFACE_EVIDENCE_OUTPUT"]).resolve()
    if output.exists(): raise RuntimeError(f"refusing to overwrite immutable evidence: {output}")
    request=json.loads(request_path.read_text())
    skin_obj,skin=asset(request["skinMaterialInstance"],"MaterialInstanceConstant")
    channels={}
    for name,path in request["textureChannels"].items():
        obj,item=asset(path,"Texture2D")
        item["dimensions"]=[int(obj.blueprint_get_size_x()),int(obj.blueprint_get_size_y())]
        channels[name]=item
    eye_materials=[]
    for path in request["eyeMaterialInstances"]: asset(path,"MaterialInstanceConstant"); eye_materials.append(path)
    parts={name:asset(path)[1] for name,path in request["eyeParts"].items()}
    checks={"skinMaterialObserved":skin["exists"],"allTexturesObserved":all(x["exists"] and min(x["dimensions"])>=4096 for x in channels.values()),"eyeMaterialsObserved":bool(eye_materials),"allEyePartsObserved":all(x["exists"] for x in parts.values())}
    value={"schemaVersion":1,"kind":"metahuman-production-surface-editor-evidence","engine":"5.6","editorRuntimeVerified":True,"skin":{"materialInstance":request["skinMaterialInstance"],"textureChannels":channels},"eyes":{"materialInstances":eye_materials,"parts":parts},"checks":checks,"automaticApproval":False,"qualityClaim":None}
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(value,indent=2)+"\n");unreal.log(json.dumps(value))
    if not all(checks.values()): raise RuntimeError("hero surface evidence failed closed")
main()
