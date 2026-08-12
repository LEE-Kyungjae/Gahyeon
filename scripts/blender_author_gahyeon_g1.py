#!/usr/bin/env python3
"""Author a provenance-bound Gahyeon G1 blockout from a CC0 MakeHuman base.

This script is intentionally a G1 authoring tool, not an approval tool. It creates
an actual humanoid mesh, a game-engine armature, fitted eyes/teeth/hair/clothing,
and an artist-authored jacket blockout inside the sealed G1 Blender scene.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import math
import sys
from pathlib import Path

try:
    import bpy
    from mathutils import Matrix, Vector
except ImportError as error:  # pragma: no cover - Blender runtime only
    raise SystemExit("Run this script with Blender's Python runtime") from error


MPFB_TAG = "v2.0.17"
MPFB_COMMIT = "80919fa4682335c41847f761a4d79dcad4124732"
MPFB_REPOSITORY = "https://github.com/makehumancommunity/mpfb2"
SYSTEM_ASSET_SOURCE = (
    "https://files.makehumancommunity.org/asset_packs/"
    "makehuman_system_assets/makehuman_system_assets_cc0.zip"
)
SYSTEM_ASSET_ARCHIVE_SHA256 = (
    "b542127a8e25547c7c29c19f2d1d2adb9a664c80396ecd694095dbc8028a0107"
)
HAIR_EDITOR_SOURCE = "https://files2.makehumancommunity.org/functional/haireditor.zip"
HAIR_EDITOR_ARCHIVE_SHA256 = (
    "39420056faba6aaa0726a5168c9c41f2d01e278a12e216c0385e8f13d4d98ab7"
)
HAIR_EDITOR_BLEND_SHA256 = (
    "93aedaef061dc14618a0fcfad33c3df816fa755e8093381cbdfd0fd4c34527f5"
)
HAIR_EDITOR_OBJECT = "straight_hair_to_shoulder"
HAIR_EDITOR_TEMPLATE_HEIGHT_M = 1.6594313383102417
MODEL_COLLECTIONS = {
    "G1_MODEL_BODY",
    "G1_MODEL_FACE",
    "G1_MODEL_EYES_TEETH",
    "G1_MODEL_HAIR",
    "G1_MODEL_OUTFIT",
}
MACRO_DETAILS = {
    "gender": 0.0,
    "age": 0.5,
    "muscle": 0.20,
    "weight": 0.28,
    "proportions": 0.58,
    "height": 0.58,
    "cupsize": 0.24,
    "firmness": 0.68,
    "race": {"african": 0.01, "asian": 0.98, "caucasian": 0.01},
}
MICRO_TARGETS = {
    "head/head-oval.target.gz": 0.38,
    "head/head-invertedtriangular.target.gz": 0.20,
    "head/head-round.target.gz": 0.06,
    "head/head-scale-horiz-incr.target.gz": 0.05,
    "head/head-scale-depth-decr.target.gz": 0.04,
    "head/head-scale-vert-decr.target.gz": 0.04,
    "forehead/forehead-temple-incr.target.gz": 0.08,
    "forehead/forehead-trans-backward.target.gz": 0.02,
    "eyebrows/eyebrows-trans-up.target.gz": 0.05,
    "eyes/l-eye-scale-incr.target.gz": 0.40,
    "eyes/r-eye-scale-incr.target.gz": 0.40,
    "eyes/l-eye-trans-in.target.gz": 0.08,
    "eyes/r-eye-trans-in.target.gz": 0.08,
    "eyes/l-eye-height1-incr.target.gz": 0.11,
    "eyes/r-eye-height1-incr.target.gz": 0.11,
    "eyes/l-eye-height2-incr.target.gz": 0.13,
    "eyes/r-eye-height2-incr.target.gz": 0.13,
    "eyes/l-eye-height3-incr.target.gz": 0.05,
    "eyes/r-eye-height3-incr.target.gz": 0.05,
    "eyes/l-eye-epicanthus-in.target.gz": 0.28,
    "eyes/r-eye-epicanthus-in.target.gz": 0.28,
    "eyes/l-eye-corner1-up.target.gz": 0.03,
    "eyes/r-eye-corner1-up.target.gz": 0.03,
    "eyes/l-eye-push1-out.target.gz": 0.08,
    "eyes/r-eye-push1-out.target.gz": 0.08,
    "nose/nose-volume-decr.target.gz": 0.24,
    "nose/nose-scale-depth-decr.target.gz": 0.20,
    "nose/nose-scale-vert-decr.target.gz": 0.10,
    "nose/nose-width1-decr.target.gz": 0.08,
    "nose/nose-width2-decr.target.gz": 0.11,
    "nose/nose-width3-decr.target.gz": 0.07,
    "nose/nose-point-width-decr.target.gz": 0.05,
    "nose/nose-nostrils-width-decr.target.gz": 0.05,
    "nose/nose-curve-concave.target.gz": 0.06,
    "nose/nose-greek-decr.target.gz": 0.08,
    "nose/nose-hump-decr.target.gz": 0.05,
    "nose/nose-base-up.target.gz": 0.02,
    "mouth/mouth-scale-horiz-incr.target.gz": 0.08,
    "mouth/mouth-scale-depth-decr.target.gz": 0.08,
    "mouth/mouth-trans-up.target.gz": 0.04,
    "mouth/mouth-upperlip-volume-incr.target.gz": 0.10,
    "mouth/mouth-lowerlip-volume-incr.target.gz": 0.12,
    "mouth/mouth-cupidsbow-incr.target.gz": 0.08,
    "mouth/mouth-upperlip-middle-down.target.gz": 0.025,
    "mouth/mouth-lowerlip-middle-up.target.gz": 0.025,
    "chin/chin-width-decr.target.gz": 0.10,
    "chin/chin-triangle.target.gz": 0.08,
    "chin/chin-height-decr.target.gz": 0.08,
    "chin/chin-prominent-decr.target.gz": 0.03,
    "cheek/l-cheek-volume-incr.target.gz": 0.23,
    "cheek/r-cheek-volume-incr.target.gz": 0.23,
    "cheek/l-cheek-inner-incr.target.gz": 0.10,
    "cheek/r-cheek-inner-incr.target.gz": 0.10,
    "cheek/l-cheek-bones-incr.target.gz": 0.03,
    "cheek/r-cheek-bones-incr.target.gz": 0.03,
    "neck/neck-scale-horiz-decr.target.gz": 0.12,
    "neck/measure-neck-height-incr.target.gz": 0.04,
    "torso/measure-shoulder-dist-decr.target.gz": 0.10,
    "torso/torso-scale-depth-decr.target.gz": 0.08,
    "torso/torso-scale-vert-decr.target.gz": 0.05,
    "torso/measure-waist-circ-decr.target.gz": 0.12,
    "torso/measure-hips-circ-decr.target.gz": 0.05,
    "legs/upperlegs-height-incr.target.gz": 0.05,
    "legs/lowerlegs-height-incr.target.gz": 0.03,
    "legs/measure-thigh-circ-decr.target.gz": 0.06,
    "legs/measure-calf-circ-decr.target.gz": 0.05,
    "arms/measure-upperarm-circ-decr.target.gz": 0.10,
    "hands/l-hand-scale-decr.target.gz": 0.12,
    "hands/r-hand-scale-decr.target.gz": 0.12,
    "hands/l-hand-fingers-diameter-decr.target.gz": 0.10,
    "hands/r-hand-fingers-diameter-decr.target.gz": 0.10,
}
IDENTITY_SCULPT = {
    "lowerFaceWidthScale": 0.95,
    "lowerFaceHeightScale": 0.78,
    "midFaceWidthScale": 1.00,
    "foreheadWidthScale": 1.03,
    "eyeWidthScale": 0.96,
    "eyeHeightScale": 1.10,
    "eyeCenterInwardCm": 0.18,
    "eyeSurfaceForwardCm": 0.04,
    "outerEyeCornerLiftCm": 0.06,
    "browRidgeBackwardCm": 0.10,
    "mouthWidthScale": 0.90,
    "mouthHeightScale": 0.86,
    "mouthSurfaceBackwardCm": 0.03,
    "noseWidthScale": 0.90,
    "noseSurfaceBackwardCm": 0.32,
    "cheekSurfaceForwardCm": 0.14,
}
ASSETS = (
    ("eyes/high-poly/high-poly.mhclo", "Eyes", "G1_MODEL_EYES_TEETH"),
    ("eyebrows/eyebrow001/eyebrow001.mhclo", "Eyebrows", "G1_MODEL_EYES_TEETH"),
    ("eyelashes/eyelashes01/eyelashes01.mhclo", "Eyelashes", "G1_MODEL_EYES_TEETH"),
    ("tongue/tongue01/tongue01.mhclo", "Tongue", "G1_MODEL_EYES_TEETH"),
    ("teeth/teeth_base/teeth_base.mhclo", "Teeth", "G1_MODEL_EYES_TEETH"),
    ("clothes/female_casualsuit02/female_casualsuit02.mhclo", "Clothes", "G1_MODEL_OUTFIT"),
)


def parse_args() -> argparse.Namespace:
    values = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset-root", type=Path, required=True)
    parser.add_argument("--identity", type=Path, required=True)
    parser.add_argument("--modeling", type=Path, required=True)
    parser.add_argument("--hair-template", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--provenance-output", type=Path, required=True)
    parser.add_argument("--target-height-cm", type=float, default=172.0)
    parser.add_argument("--revision", default="g1-mpfb-v10")
    return parser.parse_args(values)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_file(path: Path) -> Path:
    resolved = path.resolve()
    if not resolved.is_file() or resolved.is_symlink() or resolved.stat().st_size == 0:
        raise SystemExit(f"Missing or unsafe G1 input: {resolved}")
    return resolved


def dynamic_import(package_suffix: str, symbol: str):
    for module_name in tuple(sys.modules):
        if module_name.endswith(package_suffix):
            module = importlib.import_module(module_name)
            return getattr(module, symbol)
    raise SystemExit(f"MPFB module is not active in Blender: {package_suffix}")


def move_only(obj, collection_name: str) -> None:
    collection = bpy.data.collections.get(collection_name)
    if collection is None:
        raise SystemExit(f"G1 bootstrap collection is missing: {collection_name}")
    for current in tuple(obj.users_collection):
        current.objects.unlink(obj)
    collection.objects.link(obj)


def parent_keep_world(obj, parent) -> None:
    transform = obj.matrix_world.copy()
    obj.parent = parent
    obj.matrix_world = transform


def bind_mesh_to_bone(obj, rig, bone_name: str) -> None:
    """Rigid-weight a G1 blockout piece so it follows its nearest body bone."""
    if obj.type != "MESH" or rig.data.bones.get(bone_name) is None:
        raise SystemExit(f"Cannot bind {obj.name} to missing G1 bone {bone_name}")
    group = obj.vertex_groups.get(bone_name) or obj.vertex_groups.new(name=bone_name)
    group.add(range(len(obj.data.vertices)), 1.0, "REPLACE")
    modifier = obj.modifiers.new(f"{obj.name}_G1_RigidBind", "ARMATURE")
    modifier.object = rig
    obj["gahyeon_rig_status"] = f"rigid-weighted:{bone_name}"


def parent_to_bone_keep_world(obj, rig, bone_name: str) -> None:
    """Bone-parent non-mesh G1 guide geometry while preserving authored placement."""
    if rig.data.bones.get(bone_name) is None:
        raise SystemExit(f"Cannot parent {obj.name} to missing G1 bone {bone_name}")
    transform = obj.matrix_world.copy()
    obj.parent = rig
    obj.parent_type = "BONE"
    obj.parent_bone = bone_name
    obj.matrix_world = transform
    obj["gahyeon_rig_status"] = f"bone-parented:{bone_name}"


def set_principled(obj, *, base_color=None, roughness=None, metallic=None,
                   coat_weight=None, coat_roughness=None, anisotropic=None) -> None:
    if obj.type != "MESH":
        return
    for material in obj.data.materials:
        if material is None or not material.use_nodes:
            continue
        node = next((item for item in material.node_tree.nodes
                     if item.type == "BSDF_PRINCIPLED"), None)
        if node is None:
            continue
        if base_color is not None:
            socket = node.inputs.get("Base Color")
            if socket is not None:
                for link in tuple(socket.links):
                    material.node_tree.links.remove(link)
                socket.default_value = base_color
        if roughness is not None and node.inputs.get("Roughness") is not None:
            node.inputs["Roughness"].default_value = roughness
        if metallic is not None and node.inputs.get("Metallic") is not None:
            node.inputs["Metallic"].default_value = metallic
        if coat_weight is not None and node.inputs.get("Coat Weight") is not None:
            node.inputs["Coat Weight"].default_value = coat_weight
        if coat_roughness is not None and node.inputs.get("Coat Roughness") is not None:
            node.inputs["Coat Roughness"].default_value = coat_roughness
        anisotropy_socket = (node.inputs.get("Anisotropic")
                             or node.inputs.get("Anisotropic IOR Level"))
        if anisotropic is not None and anisotropy_socket is not None:
            anisotropy_socket.default_value = anisotropic


def tune_skin_materials(human) -> dict:
    """Tune MPFB's packed skin shader without replacing its CC0 albedo maps."""
    tuned = []
    for material in human.data.materials:
        if material is None or not material.use_nodes:
            continue
        shader = next((node for node in material.node_tree.nodes
                       if node.type == "GROUP" and node.outputs.get("shaderOutput")), None)
        if shader is None:
            continue
        name = material.name.rsplit(".", 1)[-1]
        values = {
            "colorMixIn": (1.0, 0.66, 0.54, 1.0),
            "colorMixInStrength": 0.075,
            "Roughness": 0.40,
            "Brightness": 0.035,
            "Contrast": 0.025,
            "Clearcoat": 0.16,
            "Clearcoat Roughness": 0.24,
            "Pore scale": 1900.0,
            "Pore detail": 3.0,
            "Pore distortion": 1.25,
            "Pore strength": 0.14,
            "SSS strength": 0.30,
            "SSS radius scale": 0.12,
        }
        if name == "lips":
            values.update({
                "colorMixIn": (0.72, 0.16, 0.20, 1.0),
                "colorMixInStrength": 0.28,
                "Roughness": 0.27,
                "Clearcoat": 0.28,
                "Clearcoat Roughness": 0.16,
                "Pore strength": 0.06,
            })
        for socket_name, value in values.items():
            socket = shader.inputs.get(socket_name)
            if socket is not None:
                socket.default_value = value
        tuned.append(name)
    return {"materials": sorted(tuned), "method": "mpfb-packed-shader-parameter-tuning"}


def tune_eye_material(eyes) -> dict:
    """Give the fitted CC0 eyes a readable wet corneal response."""
    set_principled(
        eyes,
        roughness=0.22,
        coat_weight=0.38,
        coat_roughness=0.08,
    )
    return {"roughness": 0.22, "coatWeight": 0.38, "coatRoughness": 0.08}


def ensure_hair_rest_position(human) -> dict:
    """Store the authored rest pose required by Blender's hair surface deform."""
    attribute = human.data.attributes.get("rest_position")
    if attribute is None:
        attribute = human.data.attributes.new(
            "rest_position", "FLOAT_VECTOR", "POINT")
    if attribute.domain != "POINT" or attribute.data_type != "FLOAT_VECTOR":
        raise SystemExit("G1 body has an incompatible rest_position attribute")
    if len(attribute.data) != len(human.data.vertices):
        raise SystemExit("G1 body rest_position vertex count does not match the mesh")
    for vertex, value in zip(human.data.vertices, attribute.data):
        value.vector = vertex.co
    human.data.update()
    return {
        "attribute": attribute.name,
        "domain": attribute.domain,
        "dataType": attribute.data_type,
        "vertices": len(attribute.data),
    }


def scale_geometry_nodes_distances(hair, scale: float) -> list[dict]:
    """Bake a meter-to-centimeter conversion into GN distance inputs only."""
    scaled = []
    for modifier in hair.modifiers:
        if modifier.type != "NODES" or modifier.node_group is None:
            continue
        for item in modifier.node_group.interface.items_tree:
            if (getattr(item, "item_type", None) != "SOCKET"
                    or getattr(item, "in_out", None) != "INPUT"
                    or getattr(item, "socket_type", None) != "NodeSocketFloat"
                    or getattr(item, "subtype", None) != "DISTANCE"):
                continue
            proxy = getattr(modifier.properties.inputs, item.identifier, None)
            if proxy is None or not hasattr(proxy, "value"):
                raise SystemExit(
                    f"Unable to scale Hair Editor distance input: "
                    f"{modifier.name}/{item.name}"
                )
            before = float(proxy.value)
            proxy.value = before * scale
            scaled.append({
                "modifier": modifier.name,
                "input": item.name,
                "identifier": item.identifier,
                "sourceMeters": before,
                "authoredCentimeters": float(proxy.value),
            })
    if not scaled:
        raise SystemExit("Hair Editor has no scalable Geometry Nodes distance inputs")
    return scaled


def scale_hair_interpolation_radius(hair, scale: float) -> dict:
    """Scale the template's nested interpolation radius into centimeters."""
    modifier = hair.modifiers.get("GeometryNodes")
    if modifier is None or modifier.type != "NODES" or modifier.node_group is None:
        raise SystemExit("Hair Editor interpolation modifier is missing")
    candidates = [
        node for node in modifier.node_group.nodes
        if node.type == "GROUP" and node.inputs.get("Amount") is not None
        and node.inputs.get("Viewport Amount") is not None
        and node.inputs.get("Radius") is not None
    ]
    if len(candidates) != 1:
        raise SystemExit(
            f"Expected one Hair Editor interpolation node, found {len(candidates)}"
        )
    node = candidates[0]
    radius = node.inputs["Radius"]
    interface_radius = [
        item for item in node.node_tree.interface.items_tree
        if getattr(item, "item_type", None) == "SOCKET"
        and getattr(item, "in_out", None) == "INPUT"
        and item.name == "Radius"
        and getattr(item, "socket_type", None) == "NodeSocketFloat"
        and getattr(item, "subtype", None) == "DISTANCE"
    ]
    if (radius.is_linked or radius.bl_idname != "NodeSocketFloatDistance"
            or len(interface_radius) != 1):
        raise SystemExit("Hair Editor interpolation Radius is not a direct distance")
    source_radius = float(radius.default_value)
    radius.default_value = source_radius * scale
    return {
        "nodeGroup": node.node_tree.name,
        "sourceMeters": source_radius,
        "authoredCentimeters": float(radius.default_value),
        "amount": int(node.inputs["Amount"].default_value),
        "viewportAmount": float(node.inputs["Viewport Amount"].default_value),
    }


def tune_hair_material(hair) -> dict:
    """Tune the CC0 Hair Editor shader toward the canonical near-black hair."""
    if len(hair.data.materials) != 1:
        raise SystemExit("Hair Editor groom must have exactly one material")
    material = hair.data.materials[0]
    groups = [node for node in material.node_tree.nodes if node.type == "GROUP"]
    candidates = [
        node for node in groups
        if all(node.inputs.get(name) is not None for name in (
            "Color 1", "Color 2", "Darken root", "Root color length",
            "Random color", "Noise Scale",
        ))
    ]
    if len(candidates) != 1:
        raise SystemExit("Hair Editor material control group is missing or ambiguous")
    controls = candidates[0]
    values = {
        "Color 1": (0.020, 0.012, 0.017, 1.0),
        "Color 2": (0.055, 0.032, 0.038, 1.0),
        "Darken root": 0.78,
        "Root color length": 0.30,
        "Random color": 0.32,
        "Noise Scale": 22.0,
    }
    for name, value in values.items():
        controls.inputs[name].default_value = value
    return values


def append_geometry_nodes_hair(template_path: Path, human,
                               collection_name: str):
    """Append and retarget the official CC0 Hair Editor curves template."""
    template = require_file(template_path)
    template_sha256 = sha256(template)
    if template_sha256 != HAIR_EDITOR_BLEND_SHA256:
        raise SystemExit(
            "Unexpected MakeHuman Hair Editor template checksum: "
            f"{template_sha256}"
        )
    with bpy.data.libraries.load(str(template), link=False) as (source, target):
        if HAIR_EDITOR_OBJECT not in source.objects:
            raise SystemExit(
                f"Hair Editor template is missing {HAIR_EDITOR_OBJECT}: {template}"
            )
        target.objects = [HAIR_EDITOR_OBJECT]
    hair = target.objects[0]
    if hair is None or hair.type != "CURVES":
        raise SystemExit("Hair Editor did not append a Blender Curves groom")
    source_parent = hair.parent
    source_surface = hair.data.surface
    move_only(hair, collection_name)
    hair.name = "Gahyeon_G1_HairCurves_CenterPart"

    local_height = max(point[2] for point in human.bound_box) - min(
        point[2] for point in human.bound_box)
    scale = local_height / HAIR_EDITOR_TEMPLATE_HEIGHT_M
    for point in hair.data.points:
        point.position *= scale
    distance_inputs = scale_geometry_nodes_distances(hair, scale)
    interpolation = scale_hair_interpolation_radius(hair, scale)
    material_record = tune_hair_material(hair)

    for curve_index, curve in enumerate(hair.data.curves):
        start = curve.first_point_index
        count = curve.points_length
        root_point = hair.data.points[start].position.copy()
        side = -1.0 if root_point.x < 0.0 else 1.0
        phase = root_point.x * 0.17 + root_point.y * 0.11
        length_scale = 1.62 + 0.08 * math.sin(phase)
        for offset in range(count):
            point = hair.data.points[start + offset]
            t = offset / max(count - 1, 1)
            coordinate = point.position
            envelope = t * t * (3.0 - 2.0 * t)
            coordinate.z = root_point.z + (coordinate.z - root_point.z) * length_scale
            coordinate.x += math.sin(t * 5.2 + phase) * 1.8 * envelope
            coordinate.x += side * 0.8 * t * t
            coordinate.y += math.cos(t * 4.1 + phase) * 0.8 * envelope

    active_uv = human.data.uv_layers.active
    if active_uv is None:
        raise SystemExit("G1 body is missing the UV map required by Hair Editor")
    hair.data.surface = human
    hair.data.surface_uv_map = active_uv.name
    hair.parent = human
    hair.matrix_parent_inverse = Matrix.Identity(4)
    hair.matrix_basis = Matrix.Identity(4)
    for dependency in {source_parent, source_surface} - {None, human}:
        if dependency is hair.data.surface or dependency is hair.parent:
            raise SystemExit("Hair Editor template dependency was not fully retargeted")
        bpy.data.objects.remove(dependency, do_unlink=True)

    bpy.context.view_layer.update()
    evaluated = hair.evaluated_get(bpy.context.evaluated_depsgraph_get())
    node_warnings = [
        f"{modifier.name}: {warning.message}"
        for modifier in hair.modifiers if modifier.type == "NODES"
        for warning in modifier.node_warnings
    ]
    if node_warnings:
        raise SystemExit(f"Hair Editor evaluation warnings: {node_warnings}")

    hair["gahyeon_hair_authority"] = (
        "canonical-observed-front-side_artist-authored-rear-top"
    )
    hair["gahyeon_rig_status"] = "surface-deformed-to-rigged-body"
    hair["gahyeon_source_license"] = "CC0-1.0"
    hair["gahyeon_source_sha256"] = template_sha256
    hair["gahyeon_template_object"] = HAIR_EDITOR_OBJECT
    record = {
        "role": "HairGroomTemplate",
        "source": template.name,
        "sourceUrl": HAIR_EDITOR_SOURCE,
        "sourceArchiveSha256": HAIR_EDITOR_ARCHIVE_SHA256,
        "bytes": template.stat().st_size,
        "sha256": template_sha256,
        "license": "CC0-1.0",
        "licenseAuthority": "https://static.makehumancommunity.org/assets/assetpacks.html",
        "templateObject": HAIR_EDITOR_OBJECT,
        "guideCurves": len(hair.data.curves),
        "guidePoints": len(hair.data.points),
        "lengthScale": {"base": 1.62, "variation": 0.08},
        "coordinateScale": scale,
        "surfaceObject": human.name,
        "surfaceUvMap": active_uv.name,
        "scaledDistanceInputs": distance_inputs,
        "interpolation": interpolation,
        "material": material_record,
        "evaluatedCurves": len(evaluated.data.curves),
        "evaluatedPoints": len(evaluated.data.points),
    }
    return hair, record


def add_material(name: str, color, *, roughness: float = 0.5, metallic: float = 0.0):
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    node = next(item for item in material.node_tree.nodes if item.type == "BSDF_PRINCIPLED")
    node.inputs["Base Color"].default_value = color
    node.inputs["Roughness"].default_value = roughness
    node.inputs["Metallic"].default_value = metallic
    return material


def make_box_mesh(name: str, collection_name: str, minimum, maximum, material,
                  *, bevel: float = 0.0):
    x0, y0, z0 = minimum
    x1, y1, z1 = maximum
    vertices = [
        (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
    ]
    faces = [
        (0, 1, 2, 3), (4, 7, 6, 5), (0, 4, 5, 1),
        (1, 5, 6, 2), (2, 6, 7, 3), (4, 0, 3, 7),
    ]
    mesh = bpy.data.meshes.new(f"{name}_Mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.materials.append(material)
    obj = bpy.data.objects.new(name, mesh)
    bpy.data.collections[collection_name].objects.link(obj)
    if bevel > 0:
        modifier = obj.modifiers.new(f"{name}_EdgeSoftness", "BEVEL")
        modifier.width = bevel
        modifier.segments = 3
    return obj


def make_tapered_limb(name: str, collection_name: str, start, end,
                      start_radius: float, end_radius: float, material):
    start_point = Vector(start)
    end_point = Vector(end)
    direction = end_point - start_point
    midpoint = (start_point + end_point) * 0.5
    bpy.ops.mesh.primitive_cone_add(
        vertices=32,
        radius1=end_radius,
        radius2=start_radius,
        depth=direction.length,
        end_fill_type="NGON",
        location=midpoint,
    )
    obj = bpy.context.object
    obj.name = name
    obj.rotation_euler = direction.to_track_quat("Z", "Y").to_euler()
    move_only(obj, collection_name)
    obj.data.materials.append(material)
    for polygon in obj.data.polygons:
        polygon.use_smooth = True
    bevel = obj.modifiers.new(f"{name}_EdgeSoftness", "BEVEL")
    bevel.width = 0.55
    bevel.segments = 3
    return obj


def make_tapered_panel(name: str, collection_name: str, levels, material,
                       *, front_y: float, back_y: float):
    """Build one closed garment panel from (z, inner-x, outer-x) cross-sections."""
    vertices = []
    for z, inner, outer in levels:
        vertices.extend(((inner, front_y, z), (outer, front_y, z),
                         (inner, back_y, z), (outer, back_y, z)))
    faces = []
    for level in range(len(levels) - 1):
        offset = level * 4
        following = offset + 4
        faces.extend((
            (offset, offset + 1, following + 1, following),
            (offset + 2, following + 2, following + 3, offset + 3),
            (offset, following, following + 2, offset + 2),
            (offset + 1, offset + 3, following + 3, following + 1),
        ))
    faces.extend(((0, 2, 3, 1),
                  ((len(levels) - 1) * 4, (len(levels) - 1) * 4 + 1,
                   (len(levels) - 1) * 4 + 3, (len(levels) - 1) * 4 + 2)))
    mesh = bpy.data.meshes.new(f"{name}_Mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.materials.append(material)
    obj = bpy.data.objects.new(name, mesh)
    bpy.data.collections[collection_name].objects.link(obj)
    bevel = obj.modifiers.new(f"{name}_EdgeSoftness", "BEVEL")
    bevel.width = 0.6
    bevel.segments = 3
    return obj


def bone_point(rig, bone_name: str, *, tail: bool = False) -> Vector:
    bone = rig.data.bones.get(bone_name)
    if bone is None:
        raise SystemExit(f"G1 rig landmark bone is missing: {bone_name}")
    return rig.matrix_world @ (bone.tail_local if tail else bone.head_local)


def make_curve_tube(name: str, collection_name: str, points, material,
                    *, radius: float):
    curve = bpy.data.curves.new(f"{name}_Curve", "CURVE")
    curve.dimensions = "3D"
    curve.resolution_u = 4
    curve.bevel_depth = radius
    curve.bevel_resolution = 3
    curve.materials.append(material)
    spline = curve.splines.new("BEZIER")
    spline.bezier_points.add(len(points) - 1)
    for point, coordinate in zip(spline.bezier_points, points):
        point.co = coordinate
        point.handle_left_type = "AUTO"
        point.handle_right_type = "AUTO"
    obj = bpy.data.objects.new(name, curve)
    bpy.data.collections[collection_name].objects.link(obj)
    return obj


def make_jacket_shell(human, rig, collection_name: str):
    """Create a clean, closed-panel, open-front varsity-jacket G1 blockout."""
    white = add_material("G1_Jacket_White", (0.82, 0.88, 0.90, 1.0), roughness=0.42)
    turquoise = add_material("G1_Jacket_Turquoise", (0.01, 0.64, 0.70, 1.0), roughness=0.38)
    dark = add_material("G1_Jacket_Zipper", (0.025, 0.035, 0.045, 1.0), roughness=0.3)
    pieces = []
    pelvis_top = bone_point(rig, "pelvis", tail=True)
    chest = bone_point(rig, "spine_03", tail=True)
    shoulders = [bone_point(rig, f"upperarm_{suffix}") for suffix in ("l", "r")]
    hem_z = pelvis_top.z - 1.5
    collar_z = max(point.z for point in shoulders) + 5.0
    outer_top = max(abs(point.x) for point in shoulders) - 1.0
    torso_front = min((human.matrix_world @ vertex.co).y for vertex in human.data.vertices
                      if 97.0 < (human.matrix_world @ vertex.co).z < collar_z
                      and abs((human.matrix_world @ vertex.co).x) < 18.0)
    panel_front_y = torso_front - 1.8
    panel_back_y = 5.8
    for side, sign in (("L", -1.0), ("R", 1.0)):
        levels = [
            (hem_z, sign * 2.3, sign * 17.0),
            ((hem_z + chest.z) * 0.5, sign * 2.7, sign * 17.5),
            (chest.z, sign * 3.4, sign * 17.0),
            (collar_z, sign * 6.0, sign * outer_top),
        ]
        levels = [(z, min(inner, outer), max(inner, outer)) for z, inner, outer in levels]
        panel = make_tapered_panel(
            f"Gahyeon_G1_Jacket_Body_{side}", collection_name, levels, white,
            front_y=panel_front_y, back_y=panel_back_y)
        bind_mesh_to_bone(panel, rig, "spine_02")
        pieces.append(panel)

        suffix = "r" if sign < 0 else "l"
        shoulder = bone_point(rig, f"upperarm_{suffix}")
        elbow = bone_point(rig, f"upperarm_{suffix}", tail=True)
        wrist = bone_point(rig, f"lowerarm_{suffix}", tail=True)
        cuff_start = elbow.lerp(wrist, 0.82)
        cuff_end = elbow.lerp(wrist, 1.04)
        upper = make_tapered_limb(
            f"Gahyeon_G1_Jacket_UpperSleeve_{side}", collection_name,
            shoulder, elbow, 6.3, 5.5, white)
        lower = make_tapered_limb(
            f"Gahyeon_G1_Jacket_LowerSleeve_{side}", collection_name,
            elbow, cuff_start, 5.5, 4.8, white)
        cuff = make_tapered_limb(
            f"Gahyeon_G1_Jacket_Cuff_{side}", collection_name,
            cuff_start, cuff_end, 5.1, 4.7, turquoise)
        bind_mesh_to_bone(upper, rig, f"upperarm_{suffix}")
        bind_mesh_to_bone(lower, rig, f"lowerarm_{suffix}")
        bind_mesh_to_bone(cuff, rig, f"lowerarm_{suffix}")
        pieces.extend((upper, lower, cuff))

    hem = make_box_mesh("Gahyeon_G1_Jacket_Hem", collection_name,
                        (-18.0, panel_front_y - 0.3, hem_z - 1.0),
                        (18.0, panel_back_y + 0.3, hem_z + 3.5),
                        turquoise, bevel=0.8)
    hood = make_curve_tube(
        "Gahyeon_G1_Jacket_FoldedHood", collection_name,
        ((-14.0, 3.0, collar_z - 3.0), (-9.0, 6.0, collar_z + 1.0),
         (0.0, 7.5, collar_z + 3.0), (9.0, 6.0, collar_z + 1.0),
         (14.0, 3.0, collar_z - 3.0)),
        turquoise, radius=2.2)
    zipper_left = make_box_mesh("Gahyeon_G1_Jacket_Zipper_L", collection_name,
                                (-3.3, panel_front_y - 0.6, hem_z + 3.0),
                                (-2.7, panel_front_y - 0.1, collar_z - 1.0), dark)
    zipper_right = make_box_mesh("Gahyeon_G1_Jacket_Zipper_R", collection_name,
                                 (2.7, panel_front_y - 0.6, hem_z + 3.0),
                                 (3.3, panel_front_y - 0.1, collar_z - 1.0), dark)
    for piece in (hem, zipper_left, zipper_right):
        bind_mesh_to_bone(piece, rig, "spine_02")
    parent_to_bone_keep_world(hood, rig, "spine_03")
    pieces.extend((hem, hood, zipper_left, zipper_right))
    for piece in pieces:
        piece["gahyeon_design_authority"] = "artist-authored-completion"
        piece["gahyeon_blockout_role"] = "primary-varsity-jacket"
        if "gahyeon_rig_status" not in piece:
            piece["gahyeon_rig_status"] = "g1-garment-blockout"
    return pieces


def create_curtain_fringe(collection_name: str, material, rig):
    """Add four readable G1 guide locks around the canonical center part."""
    head_base = bone_point(rig, "head")
    crown = bone_point(rig, "head", tail=True)
    start_z = crown.z - 2.0
    end_z = head_base.z - 0.5
    start_y = crown.y - 2.5
    end_y = head_base.y - 7.0
    guides = (
        ((-0.6, start_y, start_z), (-2.4, start_y - 1.2, start_z - 4.0),
         (-5.0, end_y - 0.8, end_z + 5.5), (-7.0, end_y, end_z)),
        ((0.6, start_y, start_z), (2.4, start_y - 1.2, start_z - 4.0),
         (5.0, end_y - 0.8, end_z + 5.5), (7.0, end_y, end_z)),
        ((-1.8, start_y + 0.2, start_z - 0.5), (-4.4, start_y - 1.0, start_z - 6.0),
         (-7.5, end_y + 0.8, end_z - 2.0)),
        ((1.8, start_y + 0.2, start_z - 0.5), (4.4, start_y - 1.0, start_z - 6.0),
         (7.5, end_y + 0.8, end_z - 2.0)),
    )
    locks = []
    for index, points in enumerate(guides, 1):
        curve = bpy.data.curves.new(f"Gahyeon_G1_CurtainFringe_{index}_Curve", "CURVE")
        curve.dimensions = "3D"
        curve.resolution_u = 3
        curve.bevel_depth = 0.18 if index <= 2 else 0.12
        curve.bevel_resolution = 2
        curve.taper_radius_mode = "OVERRIDE"
        curve.materials.append(material)
        spline = curve.splines.new("BEZIER")
        spline.bezier_points.add(len(points) - 1)
        for point, coordinate in zip(spline.bezier_points, points):
            point.co = coordinate
            point.handle_left_type = "AUTO"
            point.handle_right_type = "AUTO"
        obj = bpy.data.objects.new(f"Gahyeon_G1_CurtainFringe_{index}", curve)
        bpy.data.collections[collection_name].objects.link(obj)
        obj["gahyeon_hair_authority"] = "canonical-observed-front"
        parent_to_bone_keep_world(obj, rig, "head")
        locks.append(obj)
    return locks


def make_g1_sneakers(human, collection_name: str, root, rig):
    """Create grounded closed-mesh G1 sneakers from evaluated foot bounds."""
    depsgraph = bpy.context.evaluated_depsgraph_get()
    evaluated = human.evaluated_get(depsgraph)
    source = evaluated.to_mesh()
    white = add_material("G1_Sneaker_White", (0.80, 0.84, 0.85, 1.0), roughness=0.48)
    sole_material = add_material("G1_Sneaker_Sole", (0.12, 0.14, 0.16, 1.0), roughness=0.62)
    turquoise = add_material("G1_Sneaker_Accent", (0.01, 0.58, 0.64, 1.0), roughness=0.42)
    pieces = []

    def outline(center_x, half_width, front_y, back_y, heights):
        length = back_y - front_y
        return (
            (center_x - half_width * 0.70, back_y, heights[0]),
            (center_x + half_width * 0.70, back_y, heights[1]),
            (center_x + half_width, back_y - length * 0.28, heights[2]),
            (center_x + half_width * 0.90, front_y + length * 0.16, heights[3]),
            (center_x + half_width * 0.34, front_y, heights[4]),
            (center_x - half_width * 0.34, front_y, heights[5]),
            (center_x - half_width * 0.90, front_y + length * 0.16, heights[6]),
            (center_x - half_width, back_y - length * 0.28, heights[7]),
        )

    def mesh_from_rings(name, rings, material, bevel):
        count = len(rings[0])
        vertices = [point for ring in rings for point in ring]
        faces = []
        for ring_index in range(len(rings) - 1):
            start = ring_index * count
            following = start + count
            for index in range(count):
                next_index = (index + 1) % count
                faces.append((start + index, start + next_index,
                              following + next_index, following + index))
        faces.append(tuple(reversed(range(count))))
        last = (len(rings) - 1) * count
        faces.append(tuple(last + index for index in range(count)))
        mesh = bpy.data.meshes.new(f"{name}_Mesh")
        mesh.from_pydata(vertices, [], faces)
        mesh.materials.append(material)
        obj = bpy.data.objects.new(name, mesh)
        bpy.data.collections[collection_name].objects.link(obj)
        modifier = obj.modifiers.new(f"{name}_EdgeSoftness", "BEVEL")
        modifier.width = bevel
        modifier.segments = 3
        return obj

    try:
        world_points = [evaluated.matrix_world @ vertex.co for vertex in source.vertices]
        for side, selector in (("L", lambda point: point.x > 0.0),
                               ("R", lambda point: point.x < 0.0)):
            points = [point for point in world_points if selector(point) and point.z < 15.0]
            if not points:
                raise SystemExit(f"Unable to resolve G1 {side} foot bounds")
            min_x = min(point.x for point in points) - 1.0
            max_x = max(point.x for point in points) + 1.0
            min_y = min(point.y for point in points) - 2.0
            max_y = max(point.y for point in points) + 1.0
            center_x = (min_x + max_x) * 0.5
            half_width = (max_x - min_x) * 0.5
            upper = mesh_from_rings(
                f"Gahyeon_G1_Sneaker_Upper_{side}",
                (outline(center_x, half_width, min_y, max_y, (1.5,) * 8),
                 outline(center_x, half_width * 0.82, min_y + 0.7, max_y - 0.5,
                         (7.0, 7.0, 6.4, 4.4, 3.5, 3.5, 4.4, 6.4))),
                white, 0.65)
            sole = mesh_from_rings(
                f"Gahyeon_G1_Sneaker_Sole_{side}",
                (outline(center_x, half_width + 0.7, min_y - 0.8, max_y + 0.5,
                         (0.0,) * 8),
                 outline(center_x, half_width + 0.7, min_y - 0.8, max_y + 0.5,
                         (1.8,) * 8)),
                sole_material, 0.45)
            accent = make_box_mesh(
                f"Gahyeon_G1_Sneaker_Accent_{side}", collection_name,
                (center_x - half_width * 0.55, max_y - 1.0, 2.1),
                (center_x + half_width * 0.55, max_y + 0.45, 5.2),
                turquoise, bevel=0.45)
            for piece in (upper, sole, accent):
                parent_keep_world(piece, root)
                bind_mesh_to_bone(piece, rig, "foot_l" if side == "L" else "foot_r")
                piece["gahyeon_design_authority"] = "canonical-observed"
                piece["gahyeon_blockout_role"] = "grounded-sneaker"
                pieces.append(piece)
    finally:
        evaluated.to_mesh_clear()
    return pieces


def add_lighting() -> None:
    scene = bpy.context.scene
    if scene.world is not None:
        scene.world.use_nodes = True
        background = next((node for node in scene.world.node_tree.nodes
                           if node.type == "BACKGROUND"), None)
        if background is None:
            raise SystemExit("G1 world is missing its Background shader")
        background.inputs["Color"].default_value = (0.16, 0.18, 0.22, 1.0)
        background.inputs["Strength"].default_value = 0.35
    lights = (
        ("G1_Key", (-110, -155, 210), 5200.0, 110.0, (1.0, 0.94, 0.90), 136.0),
        ("G1_Fill", (115, -105, 165), 4000.0, 120.0, (0.90, 0.94, 1.0), 136.0),
        ("G1_FaceFill", (0, -105, 164), 4200.0, 82.0, (1.0, 0.96, 0.93), 158.0),
        ("G1_Rim", (0, 140, 210), 3600.0, 100.0, (0.82, 0.88, 1.0), 142.0),
    )
    for name, location, energy, size, color, target_z in lights:
        data = bpy.data.lights.new(name, "AREA")
        data.energy = energy
        data.shape = "DISK"
        data.size = size
        data.color = color
        obj = bpy.data.objects.new(name, data)
        scene.collection.objects.link(obj)
        obj.location = location
        direction = Vector((0, 0, target_z)) - obj.location
        obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    scene.render.film_transparent = True
    scene.render.image_settings.file_format = "PNG"
    scene.view_settings.look = "AgX - Medium High Contrast"
    scene.view_settings.exposure = 1.75


def apply_identity_sculpt(human) -> dict:
    """Apply a small symmetric vertex pass after MPFB targets are baked."""
    group_indices = {group.name: group.index for group in human.vertex_groups}
    required = {
        "body", "lips", "joint-l-upperlid", "joint-l-lowerlid",
        "joint-r-upperlid", "joint-r-lowerlid", "joint-mouth",
        "joint-jaw", "joint-head-2",
    }
    missing = required - set(group_indices)
    if missing:
        raise SystemExit(f"G1 identity sculpt groups are missing: {sorted(missing)}")

    def member(vertex, group_name: str, minimum: float = 0.5) -> bool:
        target = group_indices[group_name]
        return any(item.group == target and item.weight >= minimum for item in vertex.groups)

    def center(group_name: str) -> Vector:
        points = [vertex.co.copy() for vertex in human.data.vertices
                  if member(vertex, group_name, 0.01)]
        if not points:
            raise SystemExit(f"G1 identity sculpt group is empty: {group_name}")
        return sum(points, Vector((0.0, 0.0, 0.0))) / len(points)

    body_vertices = [vertex for vertex in human.data.vertices if member(vertex, "body")]
    lip_indices = {vertex.index for vertex in human.data.vertices if member(vertex, "lips")}
    lip_points = [human.data.vertices[index].co for index in lip_indices]
    lip_center = sum(lip_points, Vector((0.0, 0.0, 0.0))) / len(lip_points)
    mouth = center("joint-mouth")
    jaw = center("joint-jaw")
    crown = center("joint-head-2")
    eyes = []
    for side in ("l", "r"):
        eyes.append((center(f"joint-{side}-upperlid")
                     + center(f"joint-{side}-lowerlid")) * 0.5)
    eye_line = sum(point.z for point in eyes) / len(eyes)

    def compact(value: float, minimum: float = 0.0, maximum: float = 1.0) -> float:
        return max(minimum, min(maximum, value))

    changed = set()
    for vertex in body_vertices:
        coordinate = vertex.co
        if coordinate.y < -1.0 and mouth.z + 1.0 < coordinate.z < eye_line + 1.2 \
                and 2.5 < abs(coordinate.x) < 9.5:
            vertical = compact(1.0 - abs(coordinate.z - (mouth.z + eye_line) * 0.5)
                               / max((eye_line - mouth.z) * 0.65, 0.1))
            horizontal = compact((abs(coordinate.x) - 2.5) / 5.5)
            weight = vertical * horizontal
            coordinate.x *= 1.0 + (IDENTITY_SCULPT["midFaceWidthScale"] - 1.0) * weight
            changed.add(vertex.index)
        if coordinate.y < 0.0 and eye_line + 1.0 < coordinate.z < crown.z - 1.0 \
                and abs(coordinate.x) < 9.5:
            weight = compact((coordinate.z - eye_line) / max(crown.z - eye_line, 0.1))
            coordinate.x *= 1.0 + (IDENTITY_SCULPT["foreheadWidthScale"] - 1.0) * weight
            changed.add(vertex.index)

    eye_region_indices = set()
    for eye in eyes:
        side = 1.0 if eye.x > 0.0 else -1.0
        for vertex in body_vertices:
            coordinate = vertex.co
            if coordinate.x * side <= 0.65:
                continue
            dx = coordinate.x - eye.x
            dz = coordinate.z - eye.z
            if (abs(dx) >= 3.8 or abs(dz) >= 2.05
                    or coordinate.y >= eye.y + 2.2 or coordinate.y <= eye.y - 1.8):
                continue
            weight = (1.0 - (dx / 3.8) ** 2) * (1.0 - (dz / 2.05) ** 2)
            weight = compact(weight)
            coordinate.x = eye.x + dx * (
                1.0 + (IDENTITY_SCULPT["eyeWidthScale"] - 1.0) * weight)
            coordinate.x -= side * IDENTITY_SCULPT["eyeCenterInwardCm"] * weight
            coordinate.z = eye.z + dz * (
                1.0 + (IDENTITY_SCULPT["eyeHeightScale"] - 1.0) * weight)
            coordinate.y -= IDENTITY_SCULPT["eyeSurfaceForwardCm"] * weight
            corner_weight = compact((dx * side - 1.45) / 2.15) * weight
            coordinate.z += IDENTITY_SCULPT["outerEyeCornerLiftCm"] * corner_weight
            changed.add(vertex.index)
            eye_region_indices.add(vertex.index)

        for vertex in body_vertices:
            coordinate = vertex.co
            if coordinate.x * side <= 0.65 or vertex.index in eye_region_indices:
                continue
            dx = coordinate.x - eye.x
            dz = coordinate.z - eye.z
            if abs(dx) < 4.2 and 2.05 < dz < 3.5 and coordinate.y < -4.0:
                weight = compact((1.0 - (dx / 4.2) ** 2) * (1.0 - (dz - 2.0) ** 2 / 2.25))
                coordinate.y += IDENTITY_SCULPT["browRidgeBackwardCm"] * weight
                changed.add(vertex.index)

    for vertex in body_vertices:
        if vertex.index not in lip_indices:
            continue
        coordinate = vertex.co
        dx = coordinate.x - lip_center.x
        dz = coordinate.z - lip_center.z
        if abs(dx) < 4.8 and abs(dz) < 2.8 and coordinate.y < lip_center.y + 2.2:
            weight = compact((1.0 - (dx / 4.8) ** 2) * (1.0 - (dz / 2.8) ** 2))
            coordinate.x = lip_center.x + dx * IDENTITY_SCULPT["mouthWidthScale"]
            coordinate.z = lip_center.z + dz * IDENTITY_SCULPT["mouthHeightScale"]
            coordinate.y += IDENTITY_SCULPT["mouthSurfaceBackwardCm"] * weight
            changed.add(vertex.index)

    nose_center_z = lip_center.z + (eye_line - lip_center.z) * 0.52
    for vertex in body_vertices:
        if vertex.index in lip_indices:
            continue
        coordinate = vertex.co
        dx = coordinate.x
        dz = coordinate.z - nose_center_z
        if abs(dx) < 1.9 and abs(dz) < 3.1 and coordinate.y < -11.8:
            radial = compact((1.0 - (dx / 1.9) ** 2) * (1.0 - (dz / 3.1) ** 2))
            front = compact((-coordinate.y - 11.8) / 3.0)
            weight = radial * front
            coordinate.x *= 1.0 - (1.0 - IDENTITY_SCULPT["noseWidthScale"]) * weight
            coordinate.y += IDENTITY_SCULPT["noseSurfaceBackwardCm"] * weight
            changed.add(vertex.index)

    cheek_z = lip_center.z + (eye_line - lip_center.z) * 0.68
    for vertex in body_vertices:
        if vertex.index in eye_region_indices:
            continue
        coordinate = vertex.co
        horizontal = abs(coordinate.x)
        if 3.2 < horizontal < 8.2 and abs(coordinate.z - cheek_z) < 3.0 \
                and coordinate.y < -8.5:
            x_weight = 1.0 - abs(horizontal - 5.7) / 2.5
            z_weight = 1.0 - abs(coordinate.z - cheek_z) / 3.0
            weight = compact(x_weight * z_weight)
            coordinate.y -= IDENTITY_SCULPT["cheekSurfaceForwardCm"] * weight
            changed.add(vertex.index)

    lower_floor = jaw.z - 1.2
    lower_top = lip_center.z - 0.4
    for vertex in body_vertices:
        if vertex.index in lip_indices:
            continue
        coordinate = vertex.co
        if (coordinate.y >= -8.0 or not lower_floor < coordinate.z < lower_top
                or abs(coordinate.x) >= 8.0):
            continue
        vertical = compact(
            (lower_top - coordinate.z) / max(lower_top - lower_floor, 0.1))
        front = compact((-coordinate.y - 8.0) / 4.5)
        weight = vertical * front
        distance = lower_top - coordinate.z
        coordinate.z += distance * (
            1.0 - IDENTITY_SCULPT["lowerFaceHeightScale"]
        ) * weight
        if abs(coordinate.x) > 2.4:
            coordinate.x *= 1.0 - (
                1.0 - IDENTITY_SCULPT["lowerFaceWidthScale"]
            ) * weight
        changed.add(vertex.index)

    human.data.update()
    return {"changedVertices": len(changed), "symmetric": True,
            "authority": "canonical-references-003-006-007-008"}


def world_height(obj) -> tuple[float, float, float]:
    points = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
    minimum = min(point.z for point in points)
    maximum = max(point.z for point in points)
    return minimum, maximum, maximum - minimum


def object_world_bounds(obj):
    points = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
    return {
        axis: [round(min(point[index] for point in points), 3),
               round(max(point[index] for point in points), 3)]
        for index, axis in enumerate(("x", "y", "z"))
    }


def main() -> None:
    args = parse_args()
    asset_root = args.asset_root.resolve()
    identity = require_file(args.identity)
    modeling = require_file(args.modeling)
    hair_template = require_file(args.hair_template)
    output = args.output.resolve()
    provenance_output = args.provenance_output.resolve()
    if output.exists() or provenance_output.exists():
        raise SystemExit("Refusing to overwrite an existing G1 model or provenance record")
    if args.target_height_cm < 150 or args.target_height_cm > 200:
        raise SystemExit("G1 target height must be between 150 and 200 centimeters")
    scene = bpy.context.scene
    if scene.get("gahyeon_character_id") != "gahyeon" or scene.get("gahyeon_gate") != "G1":
        raise SystemExit("Open the sealed Gahyeon G1 bootstrap before authoring")
    collection_names = {collection.name for collection in bpy.data.collections}
    missing_collections = (MODEL_COLLECTIONS | {"G1_RIG"}) - collection_names
    if missing_collections:
        raise SystemExit(f"G1 bootstrap collections are missing: {sorted(missing_collections)}")
    existing_meshes = [obj.name for name in MODEL_COLLECTIONS
                       for obj in bpy.data.collections[name].all_objects if obj.type == "MESH"]
    if existing_meshes:
        raise SystemExit(f"Refusing to mix a new G1 model with existing meshes: {existing_meshes}")

    HumanService = dynamic_import("mpfb.services.humanservice", "HumanService")
    TargetService = dynamic_import("mpfb.services.targetservice", "TargetService")
    LocationService = dynamic_import("mpfb.services.locationservice", "LocationService")
    target_root = Path(LocationService.get_mpfb_data("targets"))
    base_mesh = require_file(Path(LocationService.get_mpfb_data("3dobjs")) / "base.obj")

    human = HumanService.create_human(
        mask_helpers=True,
        detailed_helpers=True,
        extra_vertex_groups=True,
        feet_on_ground=True,
        scale=10.0,
        macro_detail_dict=MACRO_DETAILS,
    )
    human.name = "Gahyeon_G1_BodyFace_CC0"
    for relative, weight in MICRO_TARGETS.items():
        TargetService.load_target(human, str(require_file(target_root / relative)), weight=weight)
    TargetService.bake_targets(human)
    identity_sculpt_record = apply_identity_sculpt(human)
    hair_rest_position_record = ensure_hair_rest_position(human)
    bpy.context.view_layer.update()

    skin = require_file(asset_root / "skins/young_asian_female/young_asian_female.mhmat")
    HumanService.set_character_skin(str(skin), human, skin_type="ENHANCED_SSS")
    skin_material_record = tune_skin_materials(human)
    rig = HumanService.add_builtin_rig(human, "game_engine")
    if rig is None:
        raise SystemExit("MPFB did not create the G1 game-engine armature")
    rig.name = "Gahyeon_G1_GameEngine_Rig"
    jacket_pieces = make_jacket_shell(human, rig, "G1_MODEL_OUTFIT")

    imported_assets = []
    asset_records = []
    for relative, asset_type, collection_name in ASSETS:
        source = require_file(asset_root / relative)
        obj = HumanService.add_mhclo_asset(
            str(source), human, asset_type=asset_type,
            subdiv_levels=1, material_type="MAKESKIN",
        )
        obj.name = f"Gahyeon_G1_{asset_type}_{source.stem}"
        move_only(obj, collection_name)
        imported_assets.append(obj)
        asset_records.append({
            "role": asset_type,
            "source": relative,
            "bytes": source.stat().st_size,
            "sha256": sha256(source),
            "license": "CC0-1.0",
        })

    move_only(human, "G1_MODEL_BODY")
    bpy.data.collections["G1_MODEL_FACE"].objects.link(human)
    move_only(rig, "G1_RIG")
    root = bpy.data.objects.get("ROOT_Gahyeon_G1")
    if root is None:
        raise SystemExit("G1 coordinate root is missing")
    hair, hair_groom_record = append_geometry_nodes_hair(
        hair_template, human, "G1_MODEL_HAIR")
    asset_records.append(hair_groom_record)
    eyes = next(obj for obj in imported_assets if "Eyes" in obj.name)
    eye_material_record = tune_eye_material(eyes)
    fringe_locks = []

    subdivision = human.modifiers.new("G1_BodyFace_Subdivision", "SUBSURF")
    subdivision.subdivision_type = "CATMULL_CLARK"
    subdivision.levels = 2
    subdivision.render_levels = 3
    subdivision.show_only_control_edges = True
    for polygon in human.data.polygons:
        polygon.use_smooth = True

    rig_world = rig.matrix_world.copy()
    rig.parent = root
    rig.matrix_world = rig_world
    for piece in jacket_pieces:
        if piece.parent is None:
            piece.parent = root
    for piece in fringe_locks:
        if piece.parent is None:
            piece.parent = root

    bpy.context.view_layer.update()
    _, _, current_height = world_height(human)
    scale = args.target_height_cm / current_height
    root.scale = (scale, scale, scale)
    bpy.context.view_layer.update()
    minimum, maximum, final_height = world_height(human)
    if abs(minimum) > 0.5:
        root.location.z -= minimum
        bpy.context.view_layer.update()
        minimum, maximum, final_height = world_height(human)
    sneaker_pieces = make_g1_sneakers(human, "G1_MODEL_OUTFIT", root, rig)
    bpy.context.view_layer.update()
    sneaker_bottom = min(
        min((piece.matrix_world @ Vector(corner)).z for corner in piece.bound_box)
        for piece in sneaker_pieces
    )
    if abs(sneaker_bottom) > 0.05:
        raise SystemExit(f"G1 sneaker ground invariant failed: {sneaker_bottom:.3f} cm")

    human["gahyeon_identity_authority"] = "user-provided-originals"
    human["gahyeon_identity_master"] = "reference-003"
    human["gahyeon_geometry_cross_checks"] = "references-006-007-008-016-019-020"
    human["gahyeon_base_license"] = "CC0-1.0"
    scene["gahyeon_model_revision"] = args.revision
    scene["gahyeon_model_status"] = "draft-for-human-review"
    scene["gahyeon_identity_manifest_sha256"] = sha256(identity)
    scene["gahyeon_modeling_manifest_sha256"] = sha256(modeling)
    scene["gahyeon_mpfb_tag"] = MPFB_TAG
    scene["gahyeon_mpfb_commit"] = MPFB_COMMIT
    scene["gahyeon_system_assets_sha256"] = SYSTEM_ASSET_ARCHIVE_SHA256
    scene["gahyeon_hair_editor_sha256"] = hair_groom_record["sha256"]
    scene["gahyeon_hair_editor_license"] = hair_groom_record["license"]
    scene["gahyeon_target_height_cm"] = args.target_height_cm
    scene["gahyeon_macro_details"] = json.dumps(MACRO_DETAILS, sort_keys=True)
    scene["gahyeon_micro_targets"] = json.dumps(MICRO_TARGETS, sort_keys=True)
    scene["gahyeon_identity_sculpt"] = json.dumps(IDENTITY_SCULPT, sort_keys=True)
    scene["gahyeon_identity_sculpt_changed_vertices"] = identity_sculpt_record["changedVertices"]
    scene["gahyeon_sneaker_ground_cm"] = round(sneaker_bottom, 3)
    add_lighting()

    output.parent.mkdir(parents=True, exist_ok=True)
    provenance_output.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.file.pack_all()
    bpy.ops.wm.save_as_mainfile(filepath=str(output), check_existing=False)

    mesh_objects = [obj for obj in bpy.data.objects if obj.type == "MESH"
                    and any(collection.name in MODEL_COLLECTIONS for collection in obj.users_collection)]
    curve_objects = [obj for obj in bpy.data.objects if obj.type == "CURVES"
                     and any(collection.name in MODEL_COLLECTIONS for collection in obj.users_collection)]
    provenance = {
        "schemaVersion": 1,
        "characterId": "gahyeon",
        "gate": "G1",
        "revision": args.revision,
        "status": "draft-for-human-review",
        "model": {
            "uri": output.name,
            "bytes": output.stat().st_size,
            "sha256": sha256(output),
            "meshObjects": len(mesh_objects),
            "meshVertices": sum(len(obj.data.vertices) for obj in mesh_objects),
            "meshPolygons": sum(len(obj.data.polygons) for obj in mesh_objects),
            "curveObjects": len(curve_objects),
            "hairGuideCurves": hair_groom_record["guideCurves"],
            "hairGuidePoints": hair_groom_record["guidePoints"],
            "armatures": 1,
            "bones": len(rig.data.bones),
            "heightCm": round(final_height, 3),
        },
        "sourceManifests": [
            {"role": "identity", "uri": identity.name, "sha256": sha256(identity)},
            {"role": "modeling-input", "uri": modeling.name, "sha256": sha256(modeling)},
        ],
        "identityAnchors": {
            "masterNeutralFace": 3,
            "faceDepthAndProfiles": [6, 7, 8],
            "primaryBody": [16, 19, 20],
        },
        "base": {
            "generator": "MPFB",
            "tag": MPFB_TAG,
            "commit": MPFB_COMMIT,
            "repository": MPFB_REPOSITORY,
            "codeLicense": "GPL-3.0-or-later",
            "coreAssetLicense": "CC0-1.0",
            "baseMeshSha256": sha256(base_mesh),
            "systemAssetArchive": SYSTEM_ASSET_SOURCE,
            "systemAssetArchiveSha256": SYSTEM_ASSET_ARCHIVE_SHA256,
            "assets": asset_records,
        },
        "authoring": {
            "scriptSha256": sha256(Path(__file__).resolve()),
            "scenePlanSha256": scene.get("gahyeon_scene_plan_sha256"),
            "macroDetails": MACRO_DETAILS,
            "microTargets": MICRO_TARGETS,
            "identitySculpt": {**IDENTITY_SCULPT, **identity_sculpt_record},
            "skinMaterial": skin_material_record,
            "eyeMaterial": eye_material_record,
            "hairGroom": hair_groom_record,
            "hairRestPosition": hair_rest_position_record,
            "detailHairStrands": hair_groom_record["evaluatedCurves"],
            "neutralPose": "A-pose",
            "workingUnits": "centimeters",
            "artistAuthoredRegions": ["rear-body", "rear-hair", "top-hair", "rear-outfit"],
            "knownLimits": [
                "Exact real-world height is not observable from the canonical images; 172 cm is a G1 proportion assumption.",
                "The jacket uses closed G1 blockout panels and must be retopologized and weighted at G2/G3.",
                "Rear and top hair construction remain artist-authored completion pending additional reference.",
                "Exact pore-level likeness and final production groom require the G2/G3 sculpt and groom passes.",
                "The grounded G1 sneakers are closed-mesh proportion blockouts and require G2/G3 retopology and weighting.",
            ],
            "invariants": {"sneakerGroundCm": round(sneaker_bottom, 3)},
            "objectWorldBounds": [
                {"name": obj.name, "boundsCm": object_world_bounds(obj)}
                for obj in sorted(mesh_objects, key=lambda item: item.name)
            ],
        },
    }
    provenance_output.write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "saved": str(output),
        "provenance": str(provenance_output),
        "meshObjects": provenance["model"]["meshObjects"],
        "vertices": provenance["model"]["meshVertices"],
        "polygons": provenance["model"]["meshPolygons"],
        "armatures": 1,
        "bones": provenance["model"]["bones"],
        "heightCm": provenance["model"]["heightCm"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
