"""Editor-only, fail-closed preflight for Gahyeon MetaHuman deformation QA.

Execute with UnrealEditor-Cmd and -ExecutePythonScript after installing UE 5.6.
It intentionally creates no assets and records no quality success.
"""

import json
import os
from pathlib import Path

import unreal


PLUGIN_NAMES = (
    "PythonScriptPlugin", "EditorScriptingUtilities", "SequencerScripting",
    "MovieRenderPipeline", "ControlRig", "MetaHumanCreator", "MetaHumanAnimator",
    "MetaHumanAnimatorDepthProcessing", "RigLogic", "HairStrands",
)


def plugin_enabled(name):
    try:
        return bool(unreal.PluginBlueprintLibrary.is_plugin_enabled(name))
    except Exception:
        return False


def main():
    workspace = Path(os.environ.get("GAHYEON_WORKSPACE", Path.cwd())).resolve()
    config_path = workspace / "character_pipeline/config/deformation_qa.json"
    output_path = workspace / "artifacts/gahyeon-ch/unreal-character-qa-preflight.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    plugins = {name: plugin_enabled(name) for name in PLUGIN_NAMES}
    hero_path = config["heroBlueprint"].split(".")[0]
    hero_exists = unreal.EditorAssetLibrary.does_asset_exist(hero_path)
    hero_class = unreal.load_class(None, config["heroBlueprint"]) if hero_exists else None
    parent_path = config["requiredParentClass"]
    parent_class = unreal.load_class(None, parent_path)
    inherits_shell = bool(hero_class and parent_class and hero_class.is_child_of(parent_class))
    checks = {
        "configPresent": config_path.is_file(),
        "allPluginsEnabled": all(plugins.values()),
        "heroBlueprintExists": hero_exists,
        "heroInheritsGahyeonCharacterPawn": inherits_shell,
        "requiredCasesPresent": len(config.get("requiredCases", [])) >= 15,
        "requiredChecksPresent": len(config.get("requiredChecks", [])) >= 11,
        "lookingGlassGoResolution": config.get("resolution") == [1440, 2560],
    }
    report = {
        "schemaVersion": 1,
        "readyToAuthorQaSequence": all(checks.values()),
        "checks": checks,
        "plugins": plugins,
        "heroBlueprint": config["heroBlueprint"],
        "qualityClaim": None,
        "nextAction": (
            "author immutable LevelSequence and Movie Render Queue jobs"
            if all(checks.values()) else
            "install missing toolchain and assemble a Hero inheriting GahyeonCharacterPawn"
        ),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    unreal.log(json.dumps(report))
    if not report["readyToAuthorQaSequence"]:
        raise RuntimeError("Gahyeon character QA preflight failed closed")


main()
