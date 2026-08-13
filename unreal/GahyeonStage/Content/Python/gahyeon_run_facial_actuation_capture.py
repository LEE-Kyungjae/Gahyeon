"""Run the immutable P36 facial actuation state machine inside interactive UE 5.6 Editor."""
import hashlib,json,os,time
from pathlib import Path
import unreal

_driver=None
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def actor(label):
 hits=[x for x in unreal.EditorLevelLibrary.get_all_level_actors() if x.get_actor_label()==label]
 if len(hits)!=1:raise RuntimeError(f"expected exactly one actor labelled {label}, got {len(hits)}")
 return hits[0]
def component(owner,name):
 hits=[x for x in owner.get_components_by_class(unreal.SkeletalMeshComponent) if x.get_name()==name]
 if len(hits)!=1:raise RuntimeError(f"expected exactly one face SkeletalMeshComponent {name}, got {len(hits)}")
 return hits[0]
def resolve(profile,group,semantic,weight):
 result=profile.resolve_facial_semantic_weights(group,semantic,float(weight),{})
 if isinstance(result,tuple) and len(result)==2:ok,weights=result
 elif isinstance(result,dict):ok,weights=True,result
 else:raise RuntimeError(f"unexpected ResolveFacialSemanticWeights result: {type(result)}")
 if not ok:raise RuntimeError(f"profile rejected facial semantic {group}:{semantic}")
 return {str(k):float(v) for k,v in weights.items()}
class Driver:
 def __init__(self):
  w=Path(os.environ.get("GAHYEON_WORKSPACE",Path.cwd())).resolve();self.job_path=Path(os.environ["GAHYEON_FACIAL_ACTUATION_JOB"]).resolve();self.job=json.loads(self.job_path.read_text());self.root=Path(self.job["outputRoot"]).resolve();self.report=self.root/"facial-actuation-evidence.json"
  if self.root.exists():raise RuntimeError(f"refusing to use existing immutable actuation output root: {self.root}")
  self.root.mkdir(parents=True);(self.root/"captures").mkdir();(self.root/"traces").mkdir()
  mapping_path=Path(self.job["mappingEvidence"]["path"]).resolve();self.mapping=json.loads(mapping_path.read_text());
  if sha(mapping_path)!=self.job["mappingEvidence"]["sha256"]:raise RuntimeError("P34 mapping checksum differs")
  self.mapping_path=mapping_path;self.profile=unreal.load_asset(self.mapping["presentationProfile"]["path"])
  if not self.profile:raise RuntimeError("presentation profile cannot be loaded")
  self.hero=actor(os.environ["GAHYEON_HERO_ACTOR"]);self.face=component(self.hero,os.environ["GAHYEON_FACE_COMPONENT"]);self.camera=actor(os.environ["GAHYEON_GO_FACE_CAMERA"]);self.anim=self.face.get_anim_instance()
  self.routes={x["curve"]:x["route"] for x in self.mapping["bindings"]};self.curves=sorted(self.routes);self.control_curves={x for x in self.curves if self.routes[x]=="control-rig-curve"}
  if self.control_curves and (not self.anim or not self.anim.get_class().implements_interface(unreal.GahyeonFacialControlRigBridge.static_class())):raise RuntimeError("Control Rig curves require a real GahyeonFacialControlRigBridge AnimInstance")
  self.queue=[(case,sample) for case in self.job["cases"] for sample in case["samples"]];self.samples=[];self.awaiting_rig=None;self.pending=None;self.handle=None
 def start(self):
  if len(self.queue)!=57:raise RuntimeError("P36 requires exactly 57 samples")
  self.handle=unreal.register_slate_post_tick_callback(self.tick);unreal.log("Gahyeon P36 facial actuation capture started")
 def apply(self,case,sample):
  morph_curves=[x for x in self.curves if x not in self.control_curves]
  for curve in morph_curves:self.face.set_morph_target(curve,0.0,False)
  weights=resolve(self.profile,case["group"],case["semantic"],sample["requestedWeight"])
  morph_active={k:v for k,v in weights.items() if k not in self.control_curves};control_active={k:v for k,v in weights.items() if k in self.control_curves}
  for curve,value in morph_active.items():self.face.set_morph_target(curve,value,False)
  if self.control_curves:
   reset=list(self.control_curves-set(control_active))
   if not self.anim.apply_facial_control_rig_curves(control_active,reset):raise RuntimeError("Control Rig bridge rejected facial curve application")
   pending_token=int(self.anim.get_pending_facial_control_rig_token());self.awaiting_rig=(case,sample,morph_active,pending_token,time.monotonic());return
  else:control_observed={}
  self.capture(case,sample,morph_active,control_observed,0,"")
 def capture(self,case,sample,morph_active,control_observed,rig_token,rig_digest):
  morph_curves=[x for x in self.curves if x not in self.control_curves]
  observed={curve:float(self.face.get_morph_target(curve)) for curve in morph_curves};observed.update({curve:control_observed.get(curve,0.0) for curve in self.control_curves})
  expected=dict(morph_active);expected.update({k:v for k,v in control_observed.items() if k in self.control_curves})
  for curve,value in expected.items():
   if abs(observed[curve]-value)>1e-4:raise RuntimeError(f"face morph observation differs: {curve}")
  capture=self.root/sample["capture"];trace=self.root/sample["trace"]
  if capture.exists() or trace.exists():raise RuntimeError("immutable capture target already exists")
  trace.write_text(json.dumps({"case":case["id"],"group":case["group"],"semantic":case["semantic"],"requestedWeight":sample["requestedWeight"],"curveWeights":observed},indent=2)+"\n")
  unreal.AutomationLibrary.take_high_res_screenshot(1440,2560,str(capture),self.camera)
  self.pending=(case,sample,capture,trace,observed,time.monotonic(),rig_token,rig_digest)
 def tick(self,delta):
  try:
   if self.pending:
    case,sample,capture,trace,observed,started,rig_token,rig_digest=self.pending
    if capture.is_file() and capture.stat().st_size>24:
     self.samples.append({"case":case["id"],"requestedWeight":sample["requestedWeight"],"curveWeights":observed,"controlRigConsumption":{"token":rig_token,"digest":rig_digest} if self.control_curves else None,"capture":{"path":sample["capture"],"sha256":sha(capture)},"trace":{"path":sample["trace"],"sha256":sha(trace)}});self.pending=None
    elif time.monotonic()-started>120:raise RuntimeError(f"capture timed out: {capture}")
    return
   if self.awaiting_rig:
    case,sample,morph_active,pending_token,started=self.awaiting_rig
    if int(self.anim.get_consumed_facial_control_rig_token())==pending_token:
     observed={str(k):float(v) for k,v in self.anim.get_facial_control_rig_curve_weights().items()};digest=self.anim.get_consumed_facial_control_rig_digest()
     if not digest:raise RuntimeError("Control Rig consumed batch lacks digest")
     self.awaiting_rig=None;self.capture(case,sample,morph_active,observed,pending_token,digest)
    elif time.monotonic()-started>10.0:raise RuntimeError("Control Rig graph did not acknowledge the pending facial batch")
    return
   if self.queue:self.apply(*self.queue.pop(0));return
   value={"schemaVersion":1,"characterId":"gahyeon","iteration":"v001","state":"candidate","editorRuntimeVerified":True,"job":{"path":str(self.job_path),"sha256":sha(self.job_path)},"mappingEvidence":{"path":str(self.mapping_path),"sha256":sha(self.mapping_path)},"displayProfile":{"id":"looking-glass-go","resolution":[1440,2560],"viewCount":66},"samples":self.samples,"summary":{"sampleCount":len(self.samples),"failureCount":0},"automaticApproval":False,"deformationVerified":False,"productionReady":False}
   self.report.write_text(json.dumps(value,indent=2)+"\n");unreal.unregister_slate_post_tick_callback(self.handle);unreal.log(f"Gahyeon P36 completed: {self.report}")
  except Exception as ex:
   if self.handle:unreal.unregister_slate_post_tick_callback(self.handle)
   (self.root/"failure.json").write_text(json.dumps({"state":"failed","error":str(ex),"captured":len(self.samples)},indent=2)+"\n");unreal.log_error(str(ex));raise
def main():
 global _driver
 if _driver is not None:raise RuntimeError("P36 facial actuation driver already exists")
 _driver=Driver();_driver.start()
main()
