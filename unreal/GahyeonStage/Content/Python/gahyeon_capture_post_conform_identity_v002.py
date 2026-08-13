"""Capture five immutable early post-conform identity views in interactive UE 5.6."""

import hashlib
import json
import os
import time
from pathlib import Path

import unreal


_driver = None
VIEWS = ("face-front", "face-left-45", "face-right-45", "face-left-profile", "face-right-profile")


def sha256(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def one_actor(label, expected_class=None):
    hits = [actor for actor in unreal.EditorLevelLibrary.get_all_level_actors() if actor.get_actor_label() == label]
    if len(hits) != 1:
        raise RuntimeError(f"expected exactly one actor labelled {label}, got {len(hits)}")
    if expected_class and not isinstance(hits[0], expected_class):
        raise RuntimeError(f"actor {label} has wrong class")
    return hits[0]


class Driver:
    def __init__(self):
        workspace = Path(os.environ.get("GAHYEON_WORKSPACE", Path.cwd())).resolve()
        self.job_path = Path(os.environ["GAHYEON_POST_CONFORM_CAPTURE_JOB"]).resolve()
        self.job = json.loads(self.job_path.read_text(encoding="utf-8"))
        self.output = Path(self.job["outputDirectory"]).resolve()
        if self.output.exists():
            raise RuntimeError(f"refusing to use existing immutable capture directory: {self.output}")
        if (self.job.get("state") != "ready-for-head-only-identity-capture"
                or self.job.get("views") != list(VIEWS) or self.job.get("resolution") != [1440, 2560]
                or self.job.get("profile") != "looking-glass-go" or self.job.get("qualityClaim") is not None):
            raise RuntimeError("post-conform capture job differs")
        conform = Path(self.job["conformReceipt"]["path"])
        if not conform.is_file() or conform.is_symlink() or sha256(conform) != self.job["conformReceipt"]["sha256"]:
            raise RuntimeError("post-conform receipt lineage differs")
        character = unreal.load_asset(self.job["characterAsset"]["path"])
        if character is None or character.get_class().get_name() != "MetaHumanCharacter":
            raise RuntimeError("conformed MetaHumanCharacter asset missing")
        hero_label = os.environ["GAHYEON_POST_CONFORM_HERO_ACTOR"]
        self.hero = one_actor(hero_label)
        self.cameras = {}
        for view in VIEWS:
            env = "GAHYEON_POST_CONFORM_CAMERA_" + view.upper().replace("-", "_")
            self.cameras[view] = one_actor(os.environ[env], unreal.CameraActor)
        self.output.mkdir(parents=True)
        self.queue = list(VIEWS); self.renders = []; self.pending = None; self.handle = None

    def start(self):
        self.handle = unreal.register_slate_post_tick_callback(self.tick)
        unreal.log("Gahyeon P48 post-conform identity capture started")

    def capture(self, view):
        camera = self.cameras[view]; target = self.output / f"{view}.png"
        component = camera.get_cine_camera_component() if isinstance(camera, unreal.CineCameraActor) else camera.get_camera_component()
        focal = float(component.get_editor_property("current_focal_length")) if isinstance(camera, unreal.CineCameraActor) else 50.0
        if focal < 50.0 or focal > 120.0:
            raise RuntimeError(f"identity camera focal length outside 50-120mm: {view}={focal}")
        transform = camera.get_actor_transform(); location = transform.translation; rotation = transform.rotation.rotator()
        unreal.AutomationLibrary.take_high_res_screenshot(1440, 2560, str(target), camera)
        self.pending = (view, target, {"actorPath": camera.get_path_name(),
            "location": [location.x, location.y, location.z],
            "rotation": [rotation.roll, rotation.pitch, rotation.yaw], "focalLengthMm": focal}, time.monotonic())

    def tick(self, delta):
        try:
            if self.pending:
                view, target, camera, started = self.pending
                if target.is_file() and target.stat().st_size > 24:
                    self.renders.append({"view": view, "uri": target.name, "sha256": sha256(target), "camera": camera})
                    self.pending = None
                elif time.monotonic() - started > 120:
                    raise RuntimeError(f"post-conform capture timed out: {view}")
                return
            if self.queue:
                self.capture(self.queue.pop(0)); return
            manifest = {"schemaVersion": 1, "jobId": "gahyeon-post-conform-identity-v002",
                "editorRuntimeVerified": True, "headOnlyCheckpoint": True,
                "job": {"path": str(self.job_path), "sha256": sha256(self.job_path)},
                "characterAsset": self.job["characterAsset"], "heroActorPath": self.hero.get_path_name(),
                "profile": "looking-glass-go", "resolution": [1440, 2560],
                "background": "neutral-mid-gray", "renders": self.renders,
                "automaticApproval": False, "qualityClaim": None}
            (self.output / "render-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            unreal.unregister_slate_post_tick_callback(self.handle)
            unreal.log(f"Gahyeon P48 completed: {self.output}")
        except Exception as exc:
            if self.handle: unreal.unregister_slate_post_tick_callback(self.handle)
            (self.output / "failure.json").write_text(json.dumps({"state": "failed", "error": str(exc)}, indent=2) + "\n")
            unreal.log_error(str(exc)); raise


def main():
    global _driver
    if _driver is not None: raise RuntimeError("P48 post-conform capture already running")
    _driver = Driver(); _driver.start()


main()
