#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_root="$repo_root/unreal/RuntimeCore"
runtime_module="$repo_root/unreal/GahyeonStage/Source/GahyeonRuntimeCore"
runtime_build_dir="$(mktemp -d "${TMPDIR:-/tmp}/gahyeon-runtime-core.XXXXXX")"
trap 'rm -rf "$runtime_build_dir"' EXIT

sources=(
  "$runtime_module/Private/ClientRuntimeSaveState.cpp"
  "$runtime_module/Private/ConnectionConvergenceRuntime.cpp" \
  "$runtime_module/Private/EmotionRuntime.cpp" \
  "$runtime_module/Private/GestureRuntime.cpp" \
  "$runtime_module/Private/AmbientMotionRuntime.cpp" \
  "$runtime_module/Private/AttentionRuntime.cpp" \
  "$runtime_module/Private/IntentMailbox.cpp" \
  "$runtime_module/Private/IntentRuntime.cpp" \
  "$runtime_module/Private/LipSyncRuntime.cpp" \
  "$runtime_module/Private/LatencyTrace.cpp" \
  "$runtime_module/Private/MockCognitionRuntime.cpp" \
  "$runtime_module/Private/PcmUtteranceBuffer.cpp" \
  "$runtime_module/Private/ProtocolMessageTranslator.cpp" \
  "$runtime_module/Private/ProtocolEventRuntime.cpp" \
  "$runtime_module/Private/ProtocolGameThreadDispatcher.cpp" \
  "$runtime_module/Private/ProtocolIngressMailbox.cpp" \
  "$runtime_module/Private/ProtocolNetworkBridge.cpp" \
  "$runtime_module/Private/RealtimeCharacterCoordinator.cpp" \
  "$runtime_module/Private/ReplayCursorRuntime.cpp" \
  "$runtime_module/Private/SpeechQueue.cpp" \
  "$runtime_module/Private/SpeechPlaybackCoordinator.cpp" \
  "$runtime_module/Private/StreamingSttClientRuntime.cpp" \
  "$runtime_module/Private/VoiceActivityDetector.cpp" \
  "$runtime_module/Private/VoiceInteractionController.cpp" \
  "$runtime_module/Private/WorldStateRuntime.cpp" \
  "$runtime_module/Private/WorldActionRuntime.cpp" \
  "$runtime_module/Private/WorldActionCompletionOutbox.cpp" \
  "$runtime_module/Private/WorldActionCommandBridge.cpp"
  "$runtime_root/tests/RuntimeCoreTests.cpp"
)

runtime_cxx="${CXX:-clang++}"
export runtime_cxx runtime_build_dir runtime_module
printf '%s\0' "${sources[@]}" | xargs -0 -n 1 -P "${GAHYEON_TEST_JOBS:-2}" \
  sh -c '"$runtime_cxx" -std=c++20 -Wall -Wextra -Wpedantic -Werror -pthread \
    -DGAHYEON_RUNTIME_CORE_API= -I"$runtime_module/Public" -c "$1" \
    -o "$runtime_build_dir/$(basename "${1%.cpp}").o" && printf "."' sh
printf '\n'

"$runtime_cxx" -pthread "$runtime_build_dir"/*.o \
  -o "$runtime_build_dir/gahyeon-runtime-core-tests"

"$runtime_build_dir/gahyeon-runtime-core-tests"
