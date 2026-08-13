#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

import jsonschema

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_gahyeon_hero_asset import verify
from verify_gahyeon_g1_review import REQUIRED_VIEWS, verify as verify_g1
from verify_gahyeon_g2_review import REQUIRED_VIEWS as G2_REQUIRED_VIEWS, verify as verify_g2
from verify_gahyeon_g3_review import REQUIRED_VIEWS as G3_REQUIRED_VIEWS, verify as verify_g3
from verify_gahyeon_g4_review import REQUIRED_VIEWS as G4_REQUIRED_VIEWS, verify as verify_g4
from verify_gahyeon_g5_review import REQUIRED_VIEWS as G5_REQUIRED_VIEWS, verify as verify_g5
from gahyeon_quality_test_fixture import write_source_pack


class GahyeonHeroAssetVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.manifest = self.root / "hero.json"
        identity_path, modeling_path = write_source_pack(self.root)
        identity_path.rename(self.root / "identity.json")
        modeling = json.loads(modeling_path.read_text())
        modeling["identityManifest"] = "identity.json"
        modeling_path.unlink()
        (self.root / "modeling.json").write_text(json.dumps(modeling), encoding="utf-8")
        self.write_g1_review()
        self.write_g2_review()
        self.write_g3_review()
        self.write_g4_review()
        self.write_g5_review()
        self.write_unreal_package()
        self.write_manifest()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def item(self, name: str, **extra) -> dict:
        path = self.root / name
        return {"uri": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), **extra}

    def write_unreal_package(self, *, asset: bytes = b"fixture-uasset",
                             extra: tuple[str, bytes] | None = None) -> None:
        files = {"Content/GahyeonGenerated/Characters/Gahyeon.uasset": asset}
        if extra:
            files[extra[0]] = extra[1]
        internal = {
            "schemaVersion": 2, "characterId": "gahyeon",
            "mountRoot": "Content/GahyeonGenerated", "engineVersion": "5.6",
            "entryAsset": "/Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon",
            "runtimeClass": "/Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon_C",
            "runtimeContract": {
                "pawnBaseClass": "/Script/GahyeonStage.GahyeonCharacterPawn",
                "animInstanceBaseClass": "/Script/GahyeonStage.GahyeonCharacterAnimInstance",
                "requiresBodySkeletalMesh": True,
                "requiresPresentationProfile": True,
            },
            "files": [{"path": name, "bytes": len(content),
                       "sha256": hashlib.sha256(content).hexdigest()}
                      for name, content in files.items()],
        }
        with zipfile.ZipFile(self.root / "gahyeon.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("hero-content-manifest.json", json.dumps(internal))
            for name, content in files.items():
                archive.writestr(name, content)

    def write_g1_review(self, *, status: str = "approved") -> None:
        evidence = []
        artist_views = {"body-neutral-rear", "hair-rear", "hair-top", "outfit-rear"}
        for index, view in enumerate(sorted(REQUIRED_VIEWS)):
            path = self.root / f"g1-{index}.png"
            path.write_bytes(f"g1:{view}".encode())
            evidence.append({
                "view": view, "captureType": "viewport-render",
                "designAuthority": ("artist-authored-completion"
                                    if view in artist_views else "canonical-observed"),
                **self.item(path.name),
            })
        model = self.root / "g1-model.glb"
        model.write_bytes(b"g1-model")
        payload = {
            "schemaVersion": 1, "characterId": "gahyeon", "gate": "G1", "status": status,
            "sourceManifests": [
                {"kind": "identity-reference", **self.item("identity.json")},
                {"kind": "modeling-input", **self.item("modeling.json")},
            ],
            "modelArtifact": {"format": "glb", **self.item("g1-model.glb"),
                              "bytes": model.stat().st_size},
            "evidence": evidence,
            "artistAuthoredRegions": ["rear-body", "rear-hair", "top-hair", "rear-outfit"],
            "findings": [],
            "approvals": ([
                {"role": role, "reviewer": role, "approvedAt": "2026-08-12T00:00:00Z"}
                for role in ("identity-reviewer", "technical-reviewer", "operator")
            ] if status == "approved" else []),
        }
        if status in {"draft", "rejected"}:
            payload.pop("modelArtifact")
            payload["evidence"] = []
        (self.root / "G1.json").write_text(json.dumps(payload), encoding="utf-8")

    def write_g2_review(self, *, status: str = "approved") -> None:
        evidence = []
        for index, view in enumerate(sorted(G2_REQUIRED_VIEWS)):
            path = self.root / f"g2-{index}.png"
            path.write_bytes(f"g2:{view}".encode())
            technical = view.startswith(("topology-", "rig-", "skin-")) \
                or view in {"eye-assembly", "mouth-interior"}
            evidence.append({
                "view": view,
                "captureType": ("wireframe" if view.startswith("topology-")
                                else "material-channel" if view.startswith("skin-")
                                else "deformation-test" if view.startswith("rig-")
                                else "viewport-render"),
                "authority": "technical-implementation" if technical else "g1-derived",
                **self.item(path.name),
            })
        artifacts = []
        for role, name in (("high-poly-master", "g2-high.blend"),
                           ("animation-mesh", "g2-animation.fbx")):
            path = self.root / name
            path.write_bytes(role.encode())
            artifacts.append({"role": role, "format": "blend" if name.endswith("blend") else "fbx",
                              **self.item(name), "bytes": path.stat().st_size})
        payload = {
            "schemaVersion": 1, "characterId": "gahyeon", "gate": "G2", "status": status,
            "g1Review": self.item("G1.json"),
            "artifacts": artifacts,
            "evidence": evidence,
            "topologyAudit": {"vertexCount": 1000, "polygonCount": 900,
                              "nonManifoldEdges": 0,
                              "openBoundaryPolicy": "Only documented eye and mouth boundaries",
                              "deformationTopologyReviewed": True},
            "findings": [],
            "approvals": ([
                {"role": role, "reviewer": role, "approvedAt": "2026-08-12T00:00:00Z"}
                for role in ("identity-reviewer", "topology-reviewer", "lookdev-reviewer", "operator")
            ] if status == "approved" else []),
        }
        if status in {"draft", "rejected"}:
            payload["artifacts"] = []
            payload["evidence"] = []
            payload["topologyAudit"] = {"vertexCount": 0, "polygonCount": 0,
                                        "nonManifoldEdges": 0, "openBoundaryPolicy": "",
                                        "deformationTopologyReviewed": False}
        (self.root / "G2.json").write_text(json.dumps(payload), encoding="utf-8")

    def write_g3_review(self, *, status: str = "approved") -> None:
        evidence = []
        for index, view in enumerate(sorted(G3_REQUIRED_VIEWS)):
            path = self.root / f"g3-{index}.png"
            path.write_bytes(f"g3:{view}".encode())
            capture = ("group-inspection" if view == "groom-group-inventory"
                       else "simulation-capture" if view in {"groom-motion", "outfit-cloth-collision"}
                       else "lod-comparison" if view.startswith("lod-")
                       else "deformation-test" if "deformation" in view or view in {"outfit-seated", "outfit-extreme-pose"}
                       else "viewport-render")
            evidence.append({"view": view, "captureType": capture, **self.item(path.name)})
        artifacts = []
        for role, name, fmt in (
                ("groom-master", "g3-groom.abc", "alembic-groom"),
                ("outfit-master", "g3-outfit.blend", "blend"),
                ("runtime-lod-package", "g3-lods.zip", "unreal-content-zip")):
            path = self.root / name
            path.write_bytes(role.encode())
            artifacts.append({"role": role, "format": fmt, **self.item(name),
                              "bytes": path.stat().st_size})
        payload = {
            "schemaVersion": 1, "characterId": "gahyeon", "gate": "G3", "status": status,
            "g2Review": self.item("G2.json"), "artifacts": artifacts, "evidence": evidence,
            "runtimeAudit": {
                "groomGroups": ["scalp", "brows", "lashes", "flyaways"],
                "lods": [{"name": "LOD0", "mode": "strands", "screenSize": 1.0},
                         {"name": "LOD1", "mode": "cards", "screenSize": 0.35}],
                "observedBodyPenetrations": 0,
                "seatedPoseReviewed": True, "extremePoseReviewed": True,
            },
            "findings": [],
            "approvals": ([
                {"role": role, "reviewer": role, "approvedAt": "2026-08-12T00:00:00Z"}
                for role in ("groom-reviewer", "clothing-reviewer", "technical-art-reviewer", "operator")
            ] if status == "approved" else []),
        }
        if status in {"draft", "rejected"}:
            payload["artifacts"] = []
            payload["evidence"] = []
            payload["runtimeAudit"] = {"groomGroups": [], "lods": [],
                                       "observedBodyPenetrations": 0,
                                       "seatedPoseReviewed": False,
                                       "extremePoseReviewed": False}
        (self.root / "G3.json").write_text(json.dumps(payload), encoding="utf-8")

    def write_g4_review(self, *, status: str = "approved") -> None:
        evidence = []
        for index, view in enumerate(sorted(G4_REQUIRED_VIEWS)):
            path = self.root / f"g4-{index}.mp4"
            path.write_bytes(f"g4:{view}".encode())
            capture = ("timing-trace" if "lipsync" in view
                       else "layer-debug" if view.startswith(("secondary-", "state-"))
                       else "deformation-test" if view.startswith(("expression-", "locomotion-"))
                       else "closeup-render" if view.startswith(("face-", "viseme-"))
                       else "viewport-video")
            evidence.append({"view": view, "captureType": capture, **self.item(path.name)})
        artifacts = []
        for role, name, fmt in (
                ("facial-rig", "g4-face.fbx", "fbx"),
                ("body-rig", "g4-body.fbx", "fbx"),
                ("animation-set", "g4-animation.zip", "unreal-content-zip")):
            path = self.root / name
            path.write_bytes(role.encode())
            artifacts.append({"role": role, "format": fmt, **self.item(name),
                              "bytes": path.stat().st_size})
        payload = {
            "schemaVersion": 1, "characterId": "gahyeon", "gate": "G4", "status": status,
            "g3Review": self.item("G3.json"), "artifacts": artifacts, "evidence": evidence,
            "performanceAudit": {
                "visemes": ["sil", "aa", "ih", "ou", "ee", "oh", "fv", "l", "mbp", "wq"],
                "animationLayers": ["base-locomotion", "posture", "upper-body-gesture", "head",
                                    "eyes", "face", "lip-sync", "secondary-motion"],
                "neutralControlMaxAbs": 0.0, "lipSyncMaxAbsOffsetMs": 50,
                "footSlideMaxCm": 1.0, "idleContinuesWithoutBackend": True,
                "reflexContinuesDuringCognition": True,
            },
            "findings": [],
            "approvals": ([
                {"role": role, "reviewer": role, "approvedAt": "2026-08-12T00:00:00Z"}
                for role in ("facial-reviewer", "animation-reviewer", "audio-reviewer", "operator")
            ] if status == "approved" else []),
        }
        if status in {"draft", "rejected"}:
            payload["artifacts"] = []
            payload["evidence"] = []
            payload["performanceAudit"] = {
                "visemes": [], "animationLayers": [], "neutralControlMaxAbs": 1,
                "lipSyncMaxAbsOffsetMs": 1000, "footSlideMaxCm": 100,
                "idleContinuesWithoutBackend": False, "reflexContinuesDuringCognition": False,
            }
        (self.root / "G4.json").write_text(json.dumps(payload), encoding="utf-8")

    def write_g5_review(self, *, status: str = "approved") -> None:
        evidence = []
        for index, view in enumerate(sorted(G5_REQUIRED_VIEWS)):
            path = self.root / f"g5-{index}.dat"
            path.write_bytes(f"g5:{view}".encode())
            capture = ("timing-trace" if view.startswith("latency-")
                       else "fault-injection" if view.startswith("resilience-")
                       else "state-snapshot" if view.startswith("persistence-")
                       else "performance-trace" if view.startswith("performance-")
                       else "render-video")
            evidence.append({"view": view, "captureType": capture, **self.item(path.name)})
        artifacts = []
        for role, name, fmt in (
                ("packaged-build", "g5-build.zip", "unreal-build-zip"),
                ("telemetry-bundle", "g5-telemetry.zip", "jsonl-zip"),
                ("fixed-scene-captures", "g5-captures.zip", "media-zip")):
            path = self.root / name
            path.write_bytes(role.encode())
            artifacts.append({"role": role, "format": fmt, **self.item(name),
                              "bytes": path.stat().st_size})
        payload = {
            "schemaVersion": 1, "characterId": "gahyeon", "gate": "G5", "status": status,
            "g4Review": self.item("G4.json"), "artifacts": artifacts, "evidence": evidence,
            "runtimeAudit": {
                "testMachine": {"gpu": "NVIDIA GeForce GTX 1660 Ti", "vramMiB": 6144,
                                "resolution": "1920x1080", "engineVersion": "5.6",
                                "buildConfiguration": "Development"},
                "frameTimeP95Ms": 30, "frameTimeP99Ms": 45, "peakVramMiB": 5800,
                "loadTimeSeconds": 20, "microphoneReflexP95Ms": 80,
                "sttFirstPartialP95Ms": 500, "behaviorTransitionP95Ms": 400,
                "ttsFirstAudioP95Ms": 1800, "bargeInCancelP95Ms": 200,
                "desktopWorksWithoutLookingGlass": True,
                "lookingGlassDisconnectPreservesWorld": True,
                "idleContinuesWithoutBackend": True,
                "reflexContinuesDuringCognition": True,
                "worldStateRestoredAfterRestart": True,
            },
            "findings": [],
            "approvals": ([
                {"role": role, "reviewer": role, "approvedAt": "2026-08-12T00:00:00Z"}
                for role in ("character-quality-reviewer", "runtime-reviewer",
                             "performance-reviewer", "operator")
            ] if status == "approved" else []),
        }
        if status in {"draft", "rejected"}:
            payload["artifacts"] = []
            payload["evidence"] = []
            payload["runtimeAudit"].update({
                "frameTimeP95Ms": 1000, "frameTimeP99Ms": 1000, "peakVramMiB": 999999,
                "loadTimeSeconds": 999999, "microphoneReflexP95Ms": 999999,
                "sttFirstPartialP95Ms": 999999, "behaviorTransitionP95Ms": 999999,
                "ttsFirstAudioP95Ms": 999999, "bargeInCancelP95Ms": 999999,
                "desktopWorksWithoutLookingGlass": False,
                "lookingGlassDisconnectPreservesWorld": False,
                "idleContinuesWithoutBackend": False,
                "reflexContinuesDuringCognition": False,
                "worldStateRestoredAfterRestart": False,
            })
        (self.root / "G5.json").write_text(json.dumps(payload), encoding="utf-8")

    def write_manifest(self, *, status: str = "approved", gate: str = "G5") -> None:
        package = self.item("gahyeon.zip", bytes=(self.root / "gahyeon.zip").stat().st_size)
        package.update({"renderer": "hero-engine", "format": "unreal-content-zip", "lods": ["LOD0"],
                        "materials": ["skin"], "groomMode": "strands"})
        payload = {
            "schemaVersion": 2, "characterId": "gahyeon", "status": status,
            "qualityTier": "hero-master", "gate": gate,
            "source": {"revision": "fixture", "coordinateSystem": "z-up-left-handed",
                       "unitMeters": 1},
            "packages": [package],
            "semantics": {"expressions": ["neutral"],
                          "visemes": ["sil", "aa", "ih", "ou", "ee", "oh", "fv", "l", "mbp", "wq"],
                          "activities": ["idle"]},
            "provenance": {"author": "fixture", "license": "private",
                           "sourceFiles": ["source.blend"],
                           "sourceManifests": [
                               {"kind": "identity-reference", **self.item("identity.json")},
                               {"kind": "modeling-input", **self.item("modeling.json")},
                           ],
                           "createdAt": "2026-08-12T00:00:00Z",
                           "approvedAt": "2026-08-12T00:00:00Z", "approvedBy": "owner"},
            "acceptanceEvidence": [
                {"gate": f"G{index}", **self.item(f"G{index}.json")} for index in range(1, 6)
            ],
        }
        self.manifest.write_text(json.dumps(payload), encoding="utf-8")

    def test_accepts_fully_sealed_approved_unreal_package(self) -> None:
        result = verify(self.manifest, require_approved=True,
                        renderer="hero-engine", verify_files=True)
        self.assertEqual("approved", result["status"])

    def test_rejects_draft_or_missing_gate_evidence_for_runtime(self) -> None:
        self.write_manifest(status="draft", gate="G1")
        with self.assertRaisesRegex(ValueError, "not G5-approved"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")
        self.write_manifest()
        payload = json.loads(self.manifest.read_text())
        payload["acceptanceEvidence"].pop()
        self.manifest.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex((ValueError, jsonschema.ValidationError),
                                    "exactly one G1-G5|too short"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_rejects_package_changed_after_approval(self) -> None:
        (self.root / "gahyeon.zip").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "byte size mismatch|checksum mismatch"):
            verify(self.manifest, require_approved=True,
                   renderer="hero-engine", verify_files=True)

    def test_rejects_unreal_package_with_undeclared_or_unsafe_content(self) -> None:
        self.write_unreal_package(extra=("Content/GahyeonGenerated/undeclared.bin", b"extra"))
        with zipfile.ZipFile(self.root / "gahyeon.zip", "a") as archive:
            archive.writestr("Content/GahyeonGenerated/not-declared.bin", b"surprise")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            verify(self.manifest, require_approved=True,
                   renderer="hero-engine", verify_files=True)

        self.write_unreal_package()
        with zipfile.ZipFile(self.root / "gahyeon.zip", "a") as archive:
            archive.writestr("../escape.uasset", b"escape")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "unsafe path"):
            verify(self.manifest, require_approved=True,
                   renderer="hero-engine", verify_files=True)

    def test_rejects_unreal_file_changed_without_inventory_update(self) -> None:
        self.write_unreal_package()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.root / "gahyeon.zip", "a") as archive:
                archive.writestr("Content/GahyeonGenerated/Characters/Gahyeon.uasset", b"tampered")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "duplicate paths"):
            verify(self.manifest, require_approved=True,
                   renderer="hero-engine", verify_files=True)

    def test_rejects_wrong_engine_or_missing_entry_asset(self) -> None:
        self.write_unreal_package()
        with zipfile.ZipFile(self.root / "gahyeon.zip", "r") as archive:
            files = {info.filename: archive.read(info) for info in archive.infolist()
                     if info.filename != "hero-content-manifest.json"}
            internal = json.loads(archive.read("hero-content-manifest.json"))
        internal["engineVersion"] = "5.5"
        with zipfile.ZipFile(self.root / "gahyeon.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("hero-content-manifest.json", json.dumps(internal))
            for name, content in files.items():
                archive.writestr(name, content)
        self.write_manifest()
        with self.assertRaises(jsonschema.ValidationError):
            verify(self.manifest, require_approved=True,
                   renderer="hero-engine", verify_files=True)

        internal["engineVersion"] = "5.6"
        internal["entryAsset"] = "/Game/GahyeonGenerated/Characters/Missing.Missing"
        with zipfile.ZipFile(self.root / "gahyeon.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("hero-content-manifest.json", json.dumps(internal))
            for name, content in files.items():
                archive.writestr(name, content)
        self.write_manifest()
        with self.assertRaises(jsonschema.ValidationError):
            verify(self.manifest, require_approved=True,
                   renderer="hero-engine", verify_files=True)

    def test_rejects_duplicate_source_manifest_roles(self) -> None:
        payload = json.loads(self.manifest.read_text())
        payload["provenance"]["sourceManifests"][1]["kind"] = "identity-reference"
        self.manifest.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "one identity and one modeling"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_rejects_draft_g1_review_in_approved_hero(self) -> None:
        self.write_g1_review(status="draft")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "G1 review is not approved"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_rejects_g1_review_bound_to_different_sources(self) -> None:
        payload = json.loads((self.root / "G1.json").read_text())
        payload["sourceManifests"][0]["sha256"] = "0" * 64
        (self.root / "G1.json").write_text(json.dumps(payload), encoding="utf-8")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "checksum mismatch|different source"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_rejects_draft_g2_review_in_approved_hero(self) -> None:
        self.write_g2_review(status="draft")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "G2 review is not approved"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_rejects_g2_not_descended_from_accepted_g1(self) -> None:
        payload = json.loads((self.root / "G2.json").read_text())
        other = self.root / "other-g1.json"
        other.write_bytes((self.root / "G1.json").read_bytes())
        payload["g1Review"] = self.item("other-g1.json")
        (self.root / "G2.json").write_text(json.dumps(payload), encoding="utf-8")
        self.write_manifest()
        # Same bytes are a valid immutable identity even at another URI; now alter the
        # Hero G1 evidence checksum only through a different valid review fixture.
        g1 = json.loads((self.root / "G1.json").read_text())
        g1["findings"].append({"id": "G1-001", "severity": "note",
                               "summary": "distinct accepted revision", "status": "resolved"})
        other.write_text(json.dumps(g1), encoding="utf-8")
        payload["g1Review"] = self.item("other-g1.json")
        (self.root / "G2.json").write_text(json.dumps(payload), encoding="utf-8")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "does not descend"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_g2_rejects_missing_required_view(self) -> None:
        payload = json.loads((self.root / "G2.json").read_text())
        payload["evidence"].pop()
        (self.root / "G2.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises((ValueError, jsonschema.ValidationError)):
            verify_g2(self.root / "G2.json", require_approved=True)

    def test_g2_rejects_evidence_checksum_mismatch(self) -> None:
        payload = json.loads((self.root / "G2.json").read_text())
        payload["evidence"][0]["sha256"] = "0" * 64
        (self.root / "G2.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            verify_g2(self.root / "G2.json", require_approved=True)

    def test_g2_rejects_non_manifold_approved_mesh(self) -> None:
        payload = json.loads((self.root / "G2.json").read_text())
        payload["topologyAudit"]["nonManifoldEdges"] = 1
        (self.root / "G2.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify_g2(self.root / "G2.json", require_approved=True)

    def test_rejects_draft_g3_review_in_approved_hero(self) -> None:
        self.write_g3_review(status="draft")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "G3 review is not approved"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_g3_rejects_missing_fallback_lod(self) -> None:
        payload = json.loads((self.root / "G3.json").read_text())
        payload["runtimeAudit"]["lods"] = [
            {"name": "LOD0", "mode": "strands", "screenSize": 1.0},
            {"name": "LOD1", "mode": "strands", "screenSize": 0.35},
        ]
        (self.root / "G3.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "fallback LOD"):
            verify_g3(self.root / "G3.json", require_approved=True)

    def test_g3_rejects_clothing_penetration(self) -> None:
        payload = json.loads((self.root / "G3.json").read_text())
        payload["runtimeAudit"]["observedBodyPenetrations"] = 1
        (self.root / "G3.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify_g3(self.root / "G3.json", require_approved=True)

    def test_rejects_g3_not_descended_from_accepted_g2(self) -> None:
        other = self.root / "other-g2.json"
        g2 = json.loads((self.root / "G2.json").read_text())
        g2["findings"].append({"id": "G2-001", "severity": "note",
                               "summary": "distinct accepted revision", "status": "resolved"})
        other.write_text(json.dumps(g2), encoding="utf-8")
        g3 = json.loads((self.root / "G3.json").read_text())
        g3["g2Review"] = self.item("other-g2.json")
        (self.root / "G3.json").write_text(json.dumps(g3), encoding="utf-8")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "G3 review does not descend"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_rejects_draft_g4_review_in_approved_hero(self) -> None:
        self.write_g4_review(status="draft")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "G4 review is not approved"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_g4_rejects_lip_sync_over_80ms(self) -> None:
        payload = json.loads((self.root / "G4.json").read_text())
        payload["performanceAudit"]["lipSyncMaxAbsOffsetMs"] = 81
        (self.root / "G4.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify_g4(self.root / "G4.json", require_approved=True)

    def test_g4_rejects_character_that_stops_during_cognition(self) -> None:
        payload = json.loads((self.root / "G4.json").read_text())
        payload["performanceAudit"]["reflexContinuesDuringCognition"] = False
        (self.root / "G4.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify_g4(self.root / "G4.json", require_approved=True)

    def test_g4_rejects_incomplete_viseme_set(self) -> None:
        payload = json.loads((self.root / "G4.json").read_text())
        payload["performanceAudit"]["visemes"].pop()
        (self.root / "G4.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises((ValueError, jsonschema.ValidationError)):
            verify_g4(self.root / "G4.json", require_approved=True)

    def test_rejects_g4_not_descended_from_accepted_g3(self) -> None:
        other = self.root / "other-g3.json"
        g3 = json.loads((self.root / "G3.json").read_text())
        g3["findings"].append({"id": "G3-001", "severity": "note",
                               "summary": "distinct accepted revision", "status": "resolved"})
        other.write_text(json.dumps(g3), encoding="utf-8")
        g4 = json.loads((self.root / "G4.json").read_text())
        g4["g3Review"] = self.item("other-g3.json")
        (self.root / "G4.json").write_text(json.dumps(g4), encoding="utf-8")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "G4 review does not descend"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_rejects_draft_g5_review_in_approved_hero(self) -> None:
        self.write_g5_review(status="draft")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "G5 review is not approved"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_complete_candidates_pass_without_approvals_but_are_not_approved(self) -> None:
        cases = [
            (self.write_g1_review, verify_g1, self.root / "G1.json"),
            (self.write_g2_review, verify_g2, self.root / "G2.json"),
            (self.write_g3_review, verify_g3, self.root / "G3.json"),
            (self.write_g4_review, verify_g4, self.root / "G4.json"),
            (self.write_g5_review, verify_g5, self.root / "G5.json"),
        ]
        for writer, verifier, path in cases:
            writer(status="candidate")
            self.assertEqual("candidate", verifier(path)["status"])
            with self.assertRaisesRegex(ValueError, "not approved"):
                verifier(path, require_approved=True)
            writer(status="approved")

    def test_empty_candidate_is_rejected(self) -> None:
        self.write_g1_review(status="draft")
        payload = json.loads((self.root / "G1.json").read_text())
        payload["status"] = "candidate"
        (self.root / "G1.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify_g1(self.root / "G1.json")

    def test_g5_rejects_slow_microphone_reflex(self) -> None:
        payload = json.loads((self.root / "G5.json").read_text())
        payload["runtimeAudit"]["microphoneReflexP95Ms"] = 101
        (self.root / "G5.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify_g5(self.root / "G5.json", require_approved=True)

    def test_g5_rejects_desktop_dependent_on_looking_glass(self) -> None:
        payload = json.loads((self.root / "G5.json").read_text())
        payload["runtimeAudit"]["desktopWorksWithoutLookingGlass"] = False
        (self.root / "G5.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify_g5(self.root / "G5.json", require_approved=True)

    def test_g5_rejects_1660ti_vram_overflow(self) -> None:
        payload = json.loads((self.root / "G5.json").read_text())
        payload["runtimeAudit"]["peakVramMiB"] = 6145
        (self.root / "G5.json").write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify_g5(self.root / "G5.json", require_approved=True)

    def test_rejects_g5_not_descended_from_accepted_g4(self) -> None:
        other = self.root / "other-g4.json"
        g4 = json.loads((self.root / "G4.json").read_text())
        g4["findings"].append({"id": "G4-001", "severity": "note",
                               "summary": "distinct accepted revision", "status": "resolved"})
        other.write_text(json.dumps(g4), encoding="utf-8")
        g5 = json.loads((self.root / "G5.json").read_text())
        g5["g4Review"] = self.item("other-g4.json")
        (self.root / "G5.json").write_text(json.dumps(g5), encoding="utf-8")
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "G5 review does not descend"):
            verify(self.manifest, require_approved=True, renderer="hero-engine")

    def test_schema_rejects_missing_required_production_metadata(self) -> None:
        payload = json.loads(self.manifest.read_text())
        del payload["source"]["coordinateSystem"]
        self.manifest.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaises(jsonschema.ValidationError):
            verify(self.manifest)


if __name__ == "__main__":
    unittest.main()
