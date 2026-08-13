#!/usr/bin/env python3

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SUPERVISOR = Path(__file__).resolve().parent / "ensure_voicebox_teacher_diverse5000.sh"


class VoiceboxSupervisorHandoffTest(unittest.TestCase):
    def fixture(self, generated_count: int = 5000, generator_exit: int = 0):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        scripts = root / "scripts"
        output = root / "artifacts/voicebox-teacher-diverse1000-2026-08-09"
        catalog = root / "artifacts/voicebox-teacher-pure4000-2026-08-09"
        binaries = root / "bin"
        scripts.mkdir(parents=True)
        output.mkdir(parents=True)
        catalog.mkdir(parents=True)
        binaries.mkdir()
        (catalog / "sentences-v4-diverse5000.jsonl").write_text("{}\n", encoding="utf-8")
        (output / "count").write_text("4999", encoding="utf-8")
        (scripts / "check_voicebox_teacher_progress.py").write_text(
            "import pathlib, sys\n"
            "args=sys.argv; root=pathlib.Path(args[args.index('--output')+1])\n"
            "print((root/'count').read_text().strip())\n",
            encoding="utf-8")
        (scripts / "voicebox_supervisor_decision.py").write_text(
            "import sys\nargs=sys.argv\n"
            "completed=int(args[args.index('--completed')+1])\n"
            "active=args[args.index('--generator-active')+1]=='true'\n"
            "print('wait' if active else ('finalize' if completed == 5000 else 'generate'))\n",
            encoding="utf-8")
        (scripts / "run_voicebox_teacher_diverse1000.sh").write_text(
            f"#!/bin/zsh\nprint -r -- {generated_count} >'{output}/count'\nexit {generator_exit}\n",
            encoding="utf-8")
        (scripts / "finalize_voicebox_teacher_piper.sh").write_text(
            f"#!/bin/zsh\ntouch '{output}/finalized'\n",
            encoding="utf-8")
        (scripts / "deploy_voicebox_teacher_piper_to_land.sh").write_text(
            "#!/bin/zsh\nexit 0\n", encoding="utf-8")
        pgrep = binaries / "pgrep"
        pgrep.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
        pgrep.chmod(0o755)
        environment = os.environ.copy()
        environment["GAHYEON_REPO_ROOT"] = str(root)
        environment["PATH"] = f"{binaries}:{environment['PATH']}"
        return temporary, root, output, environment

    def run_fixture(self, generated_count: int = 5000, generator_exit: int = 0):
        temporary, root, output, environment = self.fixture(generated_count, generator_exit)
        result = subprocess.run(
            ["/bin/zsh", str(SUPERVISOR)], env=environment,
            text=True, capture_output=True, timeout=10)
        return temporary, root, output, result

    def test_generation_completion_immediately_enters_finalizer(self) -> None:
        temporary, _, output, result = self.run_fixture()
        with temporary:
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue((output / "finalized").is_file())

    def test_incomplete_success_waits_for_next_resume_without_finalizing(self) -> None:
        temporary, _, output, result = self.run_fixture(generated_count=4999)
        with temporary:
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse((output / "finalized").exists())

    def test_generator_failure_is_durable_and_blocks_finalizer(self) -> None:
        temporary, _, output, result = self.run_fixture(generator_exit=17)
        with temporary:
            self.assertEqual(17, result.returncode)
            self.assertFalse((output / "finalized").exists())
            failure = json.loads((output / "pipeline_supervisor_failure.json").read_text())
            self.assertEqual("generate", failure["stage"])
            self.assertEqual(17, failure["exitCode"])


if __name__ == "__main__":
    unittest.main()
