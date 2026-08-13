#!/usr/bin/env python3

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_core_platform_boundaries.py")
SPEC = importlib.util.spec_from_file_location("verify_core_platform_boundaries", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class CorePlatformBoundaryTest(unittest.TestCase):
    def test_accepts_inward_core_and_application_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "core/Thing.java", "import java.util.List;\n")
            self.write(root, "application/UseCase.java",
                       "import com.gahyeonbot.core.identity.ActorId;\n")
            self.assertEqual(MODULE.inspect(root), [])

    def test_rejects_platform_and_persistence_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "core/Bad.java", "import net.dv8tion.jda.api.JDA;\n")
            self.write(root, "application/Bad.java",
                       "import com.gahyeonbot.repository.UserRepository;\n")
            reasons = {item["reason"] for item in MODULE.inspect(root)}
            self.assertEqual(reasons, {"platform_sdk", "application_infrastructure_dependency"})

    def test_rejects_discord_sdk_or_lavaplayer_in_neutral_speech_services(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "services/assistant/Voice.java",
                       "import net.dv8tion.jda.api.entities.Guild;\n")
            self.write(root, "services/tts/Playback.java",
                       "import com.sedmelluq.discord.lavaplayer.track.AudioTrack;\n")
            self.write(root, "config/DiscordConfig.java",
                       "import net.dv8tion.jda.api.JDA;\n")
            violations = MODULE.inspect(root)
            assert len(violations) == 3
            self.assertEqual(
                {item["reason"] for item in violations},
                {"neutral_infrastructure_platform_dependency"},
            )

    def test_rejects_discord_adapter_imports_from_neutral_infrastructure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "services/health/CommonHealth.java",
                       "import com.gahyeonbot.adapters.discord.bootstrap.BotRunner;\n")
            self.write(root, "config/CommonConfig.java",
                       "import com.gahyeonbot.adapters.discord.audio.AudioManager;\n")
            violations = MODULE.inspect(root)
            self.assertEqual(len(violations), 2)
            self.assertEqual(
                {item["reason"] for item in violations},
                {"neutral_infrastructure_discord_adapter_dependency"},
            )

    def test_allows_discord_sdk_only_in_adapter_and_legacy_presentation_roots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write(root, "adapters/discord/Voice.java",
                       "import net.dv8tion.jda.api.JDA;\n")
            self.write(root, "commands/Command.java",
                       "import net.dv8tion.jda.api.JDA;\n")
            self.write(root, "listeners/Listener.java",
                       "import net.dv8tion.jda.api.JDA;\n")
            self.write(root, "entity/Leaked.java",
                       "import net.dv8tion.jda.api.JDA;\n")
            violations = MODULE.inspect(root)
            self.assertEqual(len(violations), 1)
            self.assertEqual(violations[0]["reason"], "discord_sdk_outside_adapter_boundary")

    @staticmethod
    def write(root: Path, relative: str, content: str) -> None:
        target = root / "com" / "gahyeonbot" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("package test;\n" + content, encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
