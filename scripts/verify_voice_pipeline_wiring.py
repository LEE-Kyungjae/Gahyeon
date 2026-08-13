#!/usr/bin/env python3
"""Static fail-closed contract for the unattended Voicebox -> Piper pipeline."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(name: str) -> str:
    return (ROOT / "scripts" / name).read_text(encoding="utf-8")


def ordered(source: str, markers: tuple[str, ...], label: str, errors: list[str]) -> None:
    cursor = -1
    for marker in markers:
        position = source.find(marker, cursor + 1)
        if position < 0:
            errors.append(f"{label}: missing or out-of-order marker: {marker}")
            return
        cursor = position


def verify() -> list[str]:
    errors: list[str] = []
    compatibility = read("ensure_voicebox_teacher_diverse4000.sh")
    supervisor = read("ensure_voicebox_teacher_diverse5000.sh")
    generator = read("run_voicebox_teacher_diverse1000.sh")
    generator_impl = read("build_voicebox_teacher_from_catalog.py")
    audio_contract = read("voicebox_audio_contract.py")
    reporter = read("report_voice_pipeline_status.py")
    finalizer = read("finalize_voicebox_teacher_piper.sh")
    packager = read("package_voicebox_teacher_dataset.py")
    deploy = read("deploy_voicebox_teacher_piper_to_land.sh")
    monitor = read("monitor_piper_training_land.sh")
    remote_runner = read("land_run_piper_voicebox_diverse5000_stages4800.sh")
    review = read("build_piper_listening_review.py")
    promotion = read("promote_piper_candidate.py")
    runtime_deploy = read("deploy_piper_release_to_land.sh")
    runtime_server = (ROOT / "infra/images/piper/server.py").read_text(encoding="utf-8")

    if "exec /bin/zsh /Users/ze/work/gahyeonbot/scripts/ensure_voicebox_teacher_diverse5000.sh" not in compatibility:
        errors.append("4,000 compatibility entrypoint does not delegate to the 5,000 supervisor")
    if "local status=" in supervisor:
        errors.append("zsh read-only special parameter 'status' cannot hold a stage exit code")

    for marker in (
        "piper_training_submitted.json",
        "deploy_voicebox_teacher_piper_to_land.sh",
        "--target 5000",
        "--completed \"$completed\" --target 5000",
        "finalize_voicebox_teacher_piper.sh",
        "run_voicebox_teacher_diverse1000.sh",
        "pipeline_supervisor_failure.json",
        "os.replace(temporary, target)",
        "run_stage finalize",
        "run_stage generate",
        "completed=$(completed_count)",
        'if [[ "$completed" == 5000 ]]',
    ):
        if marker not in supervisor:
            errors.append(f"5,000 supervisor is missing marker: {marker}")
    ordered(supervisor, (
        'if [[ -f "$submitted" ]]',
        "deploy_voicebox_teacher_piper_to_land.sh",
        "check_voicebox_teacher_progress.py",
    ), "5,000 supervisor submitted fast path", errors)
    ordered(supervisor, (
        'generate)',
        'run_stage generate "$repo/scripts/run_voicebox_teacher_diverse1000.sh"',
        'completed=$(completed_count)',
        'if [[ "$completed" == 5000 ]]',
        'run_stage finalize "$repo/scripts/finalize_voicebox_teacher_piper.sh"',
    ), "generation-to-QC immediate handoff", errors)

    for marker in (
        "sentences-v4-diverse5000.jsonl",
        "--limit 5000",
        "caffeinate -dimsu",
        ".voicebox-generator.lock",
        'kill -0 "$(<"$lock/pid")"',
        "check_voicebox_teacher_progress.py",
        "--target 5000",
        "--probe-wav",
    ):
        if marker not in generator:
            errors.append(f"generator is missing marker: {marker}")
    ordered(generator, (
        "pgrep -f",
        'mkdir "$lock"',
        "check_voicebox_teacher_progress.py",
        "build_voicebox_teacher_from_catalog.py",
    ), "generator single-writer preflight", errors)
    for marker in (
        "def append_manifest_record(",
        "os.fsync(handle.fileno())",
        "os.replace(temporary, path)",
        "manifest has an incomplete final line",
        "def download_audio_atomic(",
        "Voicebox audio response exceeds size limit",
    ):
        if marker not in generator_impl:
            errors.append(f"generator durable output contract is incomplete: missing {marker}")
    for marker in (
        "MAX_AUDIO_BYTES = 32 * 1024 * 1024",
        "def validate_voicebox_wav(",
        "Voicebox WAV payload is truncated",
        "Voicebox audio must be mono PCM16 WAV",
    ):
        if marker not in audio_contract:
            errors.append(f"shared Voicebox WAV contract is incomplete: missing {marker}")
    if "from voicebox_audio_contract import MAX_AUDIO_BYTES, validate_voicebox_wav" not in generator_impl:
        errors.append("generator does not consume the shared Voicebox WAV contract")
    progress_checker = read("check_voicebox_teacher_progress.py")
    if "from voicebox_audio_contract import validate_voicebox_wav" not in progress_checker:
        errors.append("progress checker does not consume the shared Voicebox WAV contract")
    for marker in (
        "def supervisor_status()",
        "launchAgentConfigured",
        "compatibilityEntrypointTarget5000",
        "target5000Wired",
        '"supervisor": supervisor_status()',
        'stage = "pipeline_failed"',
        '"pipelineFailure"',
    ):
        if marker not in reporter:
            errors.append(f"status reporter does not prove unattended continuation: {marker}")

    ordered(finalizer, (
        "--probe-wav --require-complete",
        "check_voicebox_catalog_diversity.py",
        "acoustic_qc_voicebox_teacher.py",
        "stt_qc_voicebox_teacher.py",
        "package_voicebox_teacher_dataset.py",
        "deploy_voicebox_teacher_piper_to_land.sh",
    ), "finalizer", errors)
    if "piper_handoff_ready.json" not in finalizer or "os.replace(temporary, target)" not in finalizer:
        errors.append("finalizer does not publish the ready handoff atomically")
    if "--require-unique-audio" in finalizer:
        errors.append("finalizer must reject duplicate clips without aborting the entire 5,000-clip QC")
    if "--min-clips 4000 --require-audio-identity" not in finalizer:
        errors.append("finalizer does not require 4,000 unique QC-selected clips")
    if '--archive "$root/voicebox-teacher-piper-dataset.tar.gz"' not in finalizer:
        errors.append("finalizer does not verify the sealed dataset archive")
    for marker in ('mtime=0', 'os.replace(temporary, archive)', '"archive_bytes"'):
        if marker not in packager:
            errors.append(f"dataset packager is not deterministic/atomic: missing {marker}")

    ordered(deploy, (
        'if [[ -f "$submitted" ]]',
        "verify_voicebox_handoff_identity.py",
        "verify_piper_training_identity.py",
        "monitor_piper_training_land.sh",
        '[[ -f "$archive" ]]',
        "Archive digest mismatch",
        "land preflight",
        "rsync -a --partial",
        "record_piper_training_status.py",
    ), "deploy", errors)
    if "piper_training_submitted.json" not in deploy or "os.replace(temporary, target)" not in deploy:
        errors.append("deploy does not publish submitted identity atomically")
    if '--archive "$archive"' not in deploy:
        errors.append("deploy does not verify the sealed local dataset archive")
    ordered(deploy, (
        "piper_training_submitted.json",
        "os.replace(temporary, target)",
        "record_piper_training_status.py",
        'exec /bin/zsh "$repo/scripts/monitor_piper_training_land.sh"',
    ), "initial Piper submission-to-monitor handoff", errors)
    for marker in (
        "ze-studio387-fp32-2026-07-28/config-step300.yaml",
        "ze9-fp32-adapt1000-2026-07-27/final-step.ckpt",
        "import piper.train.export_onnx",
        "import faster_whisper",
        "models--Systran--faster-whisper-small",
        "cached faster-whisper small weights are incomplete",
    ):
        if marker not in deploy:
            errors.append(f"land Piper preflight is missing required asset/module gate: {marker}")

    for marker in (
        "FAILED.json",
        "verify_piper_training_failure.py",
        "COMPLETED.json",
        "600 1200 2400 3600 4800",
        "rank_piper_training_stages.py",
        "build_piper_listening_review.py",
    ):
        if marker not in monitor:
            errors.append(f"monitor is missing marker: {marker}")
    if remote_runner.count(
            "sha256sum model.onnx model.onnx.json evaluation-suite.json eval-*.wav >SHA256SUMS") != 2:
        errors.append("baseline and trained Piper stages must seal model, evaluation and audio together")
    for label, source in (("blind review", review), ("promotion", promotion)):
        if '"evaluation-suite.json"' not in source or "checksum mismatch" not in source:
            errors.append(f"{label} does not verify the sealed evaluation suite")

    ordered(runtime_deploy, (
        "from verify_piper_release import verify",
        "land preflight",
        'verify_piper_release.py" "$incoming"',
        "PIPER_CONFIG_SHA256=$config_sha",
        "systemctl restart gahyeon-piper.service",
        "Piper synthesis smoke failed; rolling back",
        "Piper reverse-tunnel health check failed",
        "Piper reverse-tunnel synthesis smoke failed; rolling back",
    ), "runtime deploy", errors)
    if runtime_deploy.count("verify_piper_release.py") < 3:
        errors.append("runtime deploy must verify the complete release locally and remotely")
    if runtime_deploy.count("verify_piper_runtime_smoke.py") < 3:
        errors.append("runtime deploy must upload and run synthesis smoke locally and through the tunnel")
    for marker in ("verify_runtime_identity()", "file_sha256(MODEL_PATH)",
                   "file_sha256(CONFIG_PATH)", "X-Piper-Model-SHA256",
                   "X-Piper-Config-SHA256"):
        if marker not in runtime_server:
            errors.append(f"Piper runtime identity contract is missing marker: {marker}")

    return errors


def main() -> int:
    errors = verify()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("Voice pipeline wiring passed: resume, QC, identity, submit, monitor, review")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
