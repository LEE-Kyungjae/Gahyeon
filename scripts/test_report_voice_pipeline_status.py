#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from report_voice_pipeline_status import build_report, generator_mode, supervisor_status


class VoicePipelineStatusTest(unittest.TestCase):
    def test_generator_mode_prefers_concrete_5000_worker_over_ambiguous_wrapper(self) -> None:
        process_list = "\n".join((
            "wrapper build_voicebox_teacher_from_catalog.py voicebox-teacher-diverse1000-2026-08-09",
            "python build_voicebox_teacher_from_catalog.py --catalog "
            "sentences-v4-diverse5000.jsonl --output "
            "voicebox-teacher-diverse1000-2026-08-09 --limit 5000",
        ))
        with patch("report_voice_pipeline_status.subprocess.run", return_value=SimpleNamespace(
                stdout=process_list, returncode=0)):
            self.assertEqual("target_5000", generator_mode())

    def test_supervisor_requires_loaded_agent_and_transitive_5000_wiring(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            scripts = root / "scripts"
            scripts.mkdir(parents=True)
            home = Path(directory) / "home"
            agents = home / "Library/LaunchAgents"
            agents.mkdir(parents=True)
            compatibility = scripts / "ensure_voicebox_teacher_diverse4000.sh"
            compatibility.write_text(
                "exec ensure_voicebox_teacher_diverse5000.sh\n", encoding="utf-8")
            (scripts / "ensure_voicebox_teacher_diverse5000.sh").write_text(
                "sentences-v4-diverse5000.jsonl --target 5000 "
                "finalize_voicebox_teacher_piper.sh\n", encoding="utf-8")
            (scripts / "run_voicebox_teacher_diverse1000.sh").write_text(
                "sentences-v4-diverse5000.jsonl --limit 5000\n", encoding="utf-8")
            (agents / "sh.gahyeonbot.voicebox-teacher-diverse4000.plist").write_bytes(
                __import__("plistlib").dumps({
                    "ProgramArguments": ["/bin/zsh", str(compatibility)],
                }))
            with (
                patch("report_voice_pipeline_status.ROOT", root),
                patch("pathlib.Path.home", return_value=home),
                patch("report_voice_pipeline_status.subprocess.run",
                      return_value=SimpleNamespace(returncode=0)),
            ):
                status = supervisor_status()
            self.assertTrue(status["ready"])

    def fixture(self, root: Path, count: int = 3) -> tuple[Path, Path]:
        output = root / "output"
        output.mkdir()
        catalog = root / "catalog.jsonl"
        catalog.write_text("".join(
            json.dumps({"index": index, "text": f"문장 {index}"}, ensure_ascii=False) + "\n"
            for index in range(1, 6)), encoding="utf-8")
        rows = []
        for index in range(1, count + 1):
            audio = output / f"teacher_{index:04d}.wav"
            audio.write_bytes(b"RIFF" + b"x" * 64)
            rows.append({
                "index": index, "text": f"문장 {index}", "audio": str(audio),
                "generation_seconds": 10 + index, "reused": False,
            })
        (output / "manifest.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8")
        return catalog, output

    @patch("report_voice_pipeline_status.generator_mode", return_value="legacy_4000")
    @patch("report_voice_pipeline_status.process_detected", return_value=True)
    def test_reports_generation_rate_and_eta(
            self, _process: object, _mode: object) -> None:
        with tempfile.TemporaryDirectory() as directory:
            catalog, output = self.fixture(Path(directory))
            latest = (output / "teacher_0003.wav").stat().st_mtime
            report = build_report(catalog, output, 5, now=latest + 30)
            self.assertEqual("voicebox_generating", report["stage"])
            self.assertEqual(3, report["generation"]["completed"])
            self.assertEqual(24, report["generation"]["estimatedRemainingSeconds"])
            self.assertFalse(report["generation"]["stale"])
            self.assertEqual("legacy_4000", report["generation"]["generatorMode"])
            self.assertFalse(report["generation"]["legacy4000TransitionPending"])

    @patch("report_voice_pipeline_status.process_detected", return_value=False)
    def test_marks_old_generation_stale(self, _process: object) -> None:
        with tempfile.TemporaryDirectory() as directory:
            catalog, output = self.fixture(Path(directory), 1)
            latest = (output / "teacher_0001.wav").stat().st_mtime
            report = build_report(catalog, output, 5, now=latest + 2200)
            self.assertTrue(report["generation"]["stale"])

    @patch("report_voice_pipeline_status.process_detected", return_value=False)
    def test_surfaces_piper_phase(self, _process: object) -> None:
        with tempfile.TemporaryDirectory() as directory:
            catalog, output = self.fixture(Path(directory), 5)
            (output / "piper_training_status.json").write_text(json.dumps({
                "status": "training", "details": {"phase": "export_step_1200"}}),
                encoding="utf-8")
            report = build_report(catalog, output, 5)
            self.assertEqual("piper_training", report["stage"])
            self.assertEqual("export_step_1200", report["piper"]["details"]["phase"])

    @patch("report_voice_pipeline_status.process_detected", return_value=False)
    def test_surfaces_supervisor_failure_ahead_of_normal_phase(self, _process: object) -> None:
        with tempfile.TemporaryDirectory() as directory:
            catalog, output = self.fixture(Path(directory), 5)
            failure = {"status": "failed", "stage": "finalize", "exitCode": 2}
            (output / "pipeline_supervisor_failure.json").write_text(
                json.dumps(failure), encoding="utf-8")
            report = build_report(catalog, output, 5)
            self.assertEqual("pipeline_failed", report["stage"])
            self.assertEqual(failure, report["pipelineFailure"])


if __name__ == "__main__":
    unittest.main()
