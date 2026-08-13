#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
stage_root="$repo_root/unreal/GahyeonStage"
project="$stage_root/GahyeonStage.uproject"
runtime_module="$stage_root/Source/GahyeonRuntimeCore"

required=(
  "$repo_root/scripts/run_unreal_engine_gate.sh"
  "$repo_root/scripts/run_unreal_engine_gate.ps1"
  "$repo_root/scripts/test_run_unreal_engine_gate.sh"
  "$repo_root/scripts/test_run_unreal_engine_windows_gate.py"
  "$repo_root/scripts/verify_unreal_engine_evidence.py"
  "$repo_root/scripts/test_verify_unreal_engine_evidence.py"
  "$repo_root/scripts/install_looking_glass_unreal_plugin.py"
  "$repo_root/scripts/run_looking_glass_windows_gate.ps1"
  "$repo_root/scripts/verify_looking_glass_integration.py"
  "$repo_root/scripts/verify_looking_glass_unreal_profile.py"
  "$repo_root/scripts/verify_looking_glass_adapter_source.py"
  "$repo_root/scripts/test_verify_looking_glass_adapter_source.py"
  "$project"
  "$stage_root/GahyeonStageLookingGlass.uproject"
  "$stage_root/Config/LookingGlassIntegration.lock.json"
  "$stage_root/Source/GahyeonStage.Target.cs"
  "$stage_root/Source/GahyeonStageEditor.Target.cs"
  "$stage_root/Source/GahyeonStage/GahyeonStage.Build.cs"
  "$stage_root/Source/GahyeonStage/Public/GahyeonStageModule.h"
  "$stage_root/Source/GahyeonStage/Private/GahyeonStageModule.cpp"
  "$stage_root/Source/GahyeonStage/Public/Protocol/GahyeonProtocolEnvelope.h"
  "$stage_root/Source/GahyeonStage/Public/Protocol/GahyeonProtocolParser.h"
  "$stage_root/Source/GahyeonStage/Private/Protocol/GahyeonProtocolParser.cpp"
  "$stage_root/Source/GahyeonStage/Public/Protocol/GahyeonProtocolPayloadDecoder.h"
  "$stage_root/Source/GahyeonStage/Private/Protocol/GahyeonProtocolPayloadDecoder.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonProtocolParserTest.cpp"
  "$stage_root/Source/GahyeonStage/Public/Persistence/GahyeonRuntimeSaveGame.h"
  "$stage_root/Source/GahyeonStage/Private/Persistence/GahyeonRuntimeSaveGame.cpp"
  "$stage_root/Source/GahyeonStage/Public/Persistence/GahyeonRuntimePersistenceSubsystem.h"
  "$stage_root/Source/GahyeonStage/Private/Persistence/GahyeonRuntimePersistenceSubsystem.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonRuntimeSaveGameTest.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonProtocolPayloadDecoderTest.cpp"
  "$stage_root/Source/GahyeonStage/Public/Audio/GahyeonSpeechAudioComponent.h"
  "$stage_root/Source/GahyeonStage/Private/Audio/GahyeonSpeechAudioComponent.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonSpeechWavParserTest.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonTransportConfigTest.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonIngressAdmissionTest.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonMockCognitionRuntimeTest.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonLookingGlassProfileTest.cpp"
  "$stage_root/Source/GahyeonStage/Public/LookingGlass/GahyeonLookingGlassAttestation.h"
  "$stage_root/Source/GahyeonLookingGlassAdapter/GahyeonLookingGlassAdapter.Build.cs"
  "$stage_root/Source/GahyeonLookingGlassAdapter/Private/GahyeonLookingGlassAdapterModule.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonFacialCurveBindingTest.cpp"
  "$stage_root/Source/GahyeonStage/Public/Presentation/GahyeonPresentationHost.h"
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonPresentationHost.cpp"
  "$stage_root/Source/GahyeonStage/Public/Presentation/GahyeonCharacterPresentationComponent.h"
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
  "$stage_root/Source/GahyeonStage/Public/Presentation/GahyeonCharacterPresentationProfile.h"
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationProfile.cpp"
  "$stage_root/Source/GahyeonStage/Public/Animation/GahyeonCharacterAnimInstance.h"
  "$stage_root/Source/GahyeonStage/Private/Animation/GahyeonCharacterAnimInstance.cpp"
  "$stage_root/Source/GahyeonStage/Public/World/GahyeonWorldActionComponent.h"
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
  "$stage_root/Source/GahyeonStage/Public/World/GahyeonInteractionPointComponent.h"
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonInteractionPointComponent.cpp"
  "$stage_root/Source/GahyeonStage/Public/World/GahyeonInteractionRegistrySubsystem.h"
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonInteractionRegistrySubsystem.cpp"
  "$stage_root/Source/GahyeonStage/Public/World/GahyeonPrototypeRoom.h"
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonPrototypeRoom.cpp"
  "$stage_root/Source/GahyeonStage/Public/World/GahyeonWorldCoordinateAdapter.h"
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldCoordinateAdapter.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonWorldCoordinateAdapterTest.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonWorldActionReadinessTest.cpp"
  "$stage_root/Source/GahyeonStage/Public/Character/GahyeonCharacterPawn.h"
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
  "$stage_root/Source/GahyeonStage/Public/Character/GahyeonStageGameMode.h"
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonStageGameMode.cpp"
  "$stage_root/Source/GahyeonStage/Public/Character/GahyeonHeroRuntimeSettings.h"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonHeroRuntimeSettingsTest.cpp"
  "$stage_root/Source/GahyeonStage/Public/Debug/GahyeonRuntimeDebugComponent.h"
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
  "$stage_root/Source/GahyeonStage/Public/Debug/GahyeonLookingGlassBenchmarkComponent.h"
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonLookingGlassBenchmarkComponent.cpp"
  "$stage_root/Source/GahyeonStage/Public/Debug/GahyeonRealtimeBenchmarkComponent.h"
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRealtimeBenchmarkComponent.cpp"
  "$repo_root/scripts/build_desktop_realtime_acceptance.py"
  "$repo_root/scripts/verify_desktop_realtime_acceptance.py"
  "$repo_root/scripts/test_build_desktop_realtime_acceptance.py"
  "$repo_root/scripts/run_desktop_realtime_acceptance.ps1"
  "$repo_root/scripts/test_run_desktop_realtime_acceptance.py"
  "$runtime_module/GahyeonRuntimeCore.Build.cs"
  "$runtime_module/Private/GahyeonRuntimeCoreModule.cpp"
  "$stage_root/Source/GahyeonStage/Public/Persistence/GahyeonRuntimeSaveMapper.h"
  "$stage_root/Source/GahyeonStage/Private/Persistence/GahyeonRuntimeSaveMapper.cpp"
  "$stage_root/Source/GahyeonStage/Public/Network/GahyeonTransportSubsystem.h"
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
  "$stage_root/Source/GahyeonStage/Public/Runtime/GahyeonRuntimeSubsystem.h"
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonVoiceInputComponent.h"
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonBatchSttAudioSink.h"
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonBatchSttAudioSink.cpp"
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonStreamingSttAudioSink.h"
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttAudioSink.cpp"
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonStreamingSttWebSocketClient.h"
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttWebSocketClient.cpp"
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonStreamingSttProtocolTest.cpp"
)

for path in "${required[@]}"; do
  if [[ ! -s "$path" ]]; then
    echo "missing Unreal Stage source: $path" >&2
    exit 1
  fi
done

bash -n "$repo_root/scripts/run_unreal_engine_gate.sh"
bash -n "$repo_root/scripts/test_run_unreal_engine_gate.sh"
python3 "$repo_root/scripts/test_run_unreal_engine_windows_gate.py" >/dev/null
python3 "$repo_root/scripts/test_verify_unreal_engine_evidence.py" >/dev/null
python3 "$repo_root/scripts/test_run_desktop_realtime_acceptance.py" >/dev/null
grep -q 'engine_version" != "5.6"' "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q 'GahyeonStageEditor' "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q 'Automation RunTests Gahyeon' "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q 'VS-5 Mock Cognition Automation test did not emit a success result' \
  "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q 'VS-8 facial/viseme Automation test did not emit a success result' \
  "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q '"requiredAutomationTests":' \
  "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q 'Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded' \
  "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q 'temporary.replace(root / "manifest.json")' \
  "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q 'verify_unreal_engine_evidence.py' "$repo_root/scripts/run_unreal_engine_gate.sh"
grep -q 'GahyeonStageEditor Win64 Development' \
  "$repo_root/scripts/run_unreal_engine_gate.ps1"
grep -q 'verify_unreal_engine_evidence.py' \
  "$repo_root/scripts/run_unreal_engine_gate.ps1"
grep -q 'CreateDefaultSubobject<UGahyeonRealtimeBenchmarkComponent>' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonPresentationHost.cpp"
grep -q 'GahyeonRtDuration=' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRealtimeBenchmarkComponent.cpp"
grep -q 'physical-presentation-v1' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRealtimeBenchmarkComponent.cpp"
grep -q 'raise ValueError(f"{key} SHA-256 mismatch")' \
  "$repo_root/scripts/verify_unreal_engine_evidence.py"
python3 "$repo_root/scripts/verify_looking_glass_integration.py" >/dev/null
python3 "$repo_root/scripts/verify_looking_glass_unreal_profile.py" >/dev/null
python3 "$repo_root/scripts/test_verify_looking_glass_adapter_source.py" >/dev/null
python3 "$repo_root/scripts/verify_looking_glass_adapter_source.py" >/dev/null
grep -q 'Gahyeon.LookingGlass.PluginAvailableWhenRequired' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonLookingGlassProfileTest.cpp"
grep -q 'GahyeonRequireLookingGlass' \
  "$repo_root/scripts/run_looking_glass_windows_gate.ps1"
grep -q 'GahyeonLookingGlassAdapter' \
  "$stage_root/GahyeonStageLookingGlass.uproject"
if grep -q 'GahyeonLookingGlassAdapter\|LookingGlassRuntime' \
  "$stage_root/GahyeonStage.uproject"; then
  echo "canonical Stage acquired a Looking Glass adapter dependency" >&2
  exit 1
fi
grep -q '"Projects"' "$stage_root/Source/GahyeonStage/GahyeonStage.Build.cs"
grep -q 'ResetLookingGlassAcceptanceLatencySamples' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonLookingGlassBenchmarkComponent.cpp"
grep -q 'GahyeonLgRunId=' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonLookingGlassBenchmarkComponent.cpp"
grep -q 'Files.FileExists(\*Path) || Files.FileExists(\*CapturePath)' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonLookingGlassBenchmarkComponent.cpp"
grep -q 'SavedDir(), TEXT("GahyeonBenchmarks")' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonLookingGlassBenchmarkComponent.cpp"
grep -q 'CreateDefaultSubobject<UGahyeonLookingGlassBenchmarkComponent>' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonPresentationHost.cpp"
grep -q 'ValidateHeroRuntimeContract(HeroClass, Error)' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonStageGameMode.cpp"
grep -q 'UGahyeonCharacterAnimInstance::StaticClass' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonStageGameMode.cpp"
grep -q 'Presentation->GetProfile()->Validate' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonStageGameMode.cpp"
grep -q 'ResolveFacialCurveWeights' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationProfile.cpp"
grep -q 'Mesh->SetMorphTarget' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'Profile->ResolveProceduralPose' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'FGahyeonResolvedProceduralPose GetProceduralPose' \
  "$stage_root/Source/GahyeonStage/Public/Presentation/GahyeonCharacterPresentationComponent.h"
grep -q 'Profile->FindGesture' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'RequestAsyncLoad' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'RequestGeneration != GestureRequestGeneration' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'Gahyeon.Presentation.FacialCurveBindingsAreDataDrivenAndBounded' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonFacialCurveBindingTest.cpp"

runtime_headers="$(find "$runtime_module/Public/Gahyeon" -type f -name '*.h' | wc -l | tr -d ' ')"
runtime_sources="$(find "$runtime_module/Private" -type f -name '*.cpp' ! -name 'GahyeonRuntimeCoreModule.cpp' | wc -l | tr -d ' ')"
if [[ "$runtime_headers" -ne 28 || "$runtime_sources" -ne 28 ]]; then
  echo "RuntimeCore source-of-truth count changed unexpectedly: $runtime_headers/$runtime_sources" >&2
  exit 1
fi

grep -q 'class GAHYEON_RUNTIME_CORE_API MockCognitionRuntime' \
  "$runtime_module/Public/Gahyeon/MockCognitionRuntime.h"
grep -q 'MockCognitionRuntime::TakeDue' \
  "$runtime_module/Private/MockCognitionRuntime.cpp"
grep -q 'Private/MockCognitionRuntime.cpp' \
  "$repo_root/unreal/RuntimeCore/CMakeLists.txt"
grep -q 'Private/MockCognitionRuntime.cpp' \
  "$repo_root/scripts/test_unreal_runtime_core.sh"
grep -q 'MockCognitionHarnessExercisesDelayFailureAndReordering' \
  "$repo_root/unreal/RuntimeCore/tests/RuntimeCoreTests.cpp"
grep -q 'Gahyeon.Runtime.MockCognitionDelayFailureAndReordering' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonMockCognitionRuntimeTest.cpp"
grep -q 'Character.SpeechStarted' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonMockCognitionRuntimeTest.cpp"

grep -q 'MaximumPendingPcmChunks = 256' \
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonStreamingSttAudioSink.h"
grep -q 'PcmWrite.store(Write + 1, std::memory_order_release)' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttAudioSink.cpp"
grep -q 'TickGameThread' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttWebSocketClient.cpp"
grep -q 'NextReconnectAtSeconds' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttWebSocketClient.cpp"
grep -q 'input_audio_buffer' \
  "$repo_root/docs/adr/0009-core-owned-streaming-stt.md"

ruby -rjson -e '
  project = JSON.parse(File.read(ARGV.fetch(0)))
  abort "EngineAssociation must be 5.6" unless project["EngineAssociation"] == "5.6"
  modules = project.fetch("Modules").map { |entry| entry.fetch("Name") }
  abort "GahyeonStage runtime module missing" unless modules.include?("GahyeonStage")
  plugins = project.fetch("Plugins").select { |entry| entry["Enabled"] }.map { |entry| entry["Name"] }
  required = %w[WebSockets AudioCapture ControlRig FullBodyIK EnhancedInput]
  abort "missing plugins: #{(required - plugins).join(", ")}" unless (required - plugins).empty?
' "$project"

grep -q 'public FTickableGameObject' \
  "$stage_root/Source/GahyeonStage/Public/Runtime/GahyeonRuntimeSubsystem.h"
grep -q 'check(IsInGameThread())' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'RecoversAfterMalformedJson' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonProtocolParserTest.cpp"
grep -q 'Result.P50Ms = Summary.P50Ms' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Result.P99Ms = Summary.P99Ms' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'TQueue<FGahyeonProtocolEnvelope, EQueueMode::Mpsc>' \
  "$stage_root/Source/GahyeonStage/Public/Runtime/GahyeonRuntimeSubsystem.h"
grep -q 'MaxInboundEventsPerFrame' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'MaxLatestStateInboundQueueDepth' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'return bDroppableLatestState' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'IngressReservesCapacityForSpeechAndControl' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonIngressAdmissionTest.cpp"
grep -q 'unknown top-level field' \
  "$stage_root/Source/GahyeonStage/Private/Protocol/GahyeonProtocolParser.cpp"
grep -q 'durable event requires sequence' \
  "$stage_root/Source/GahyeonStage/Private/Protocol/GahyeonProtocolParser.cpp"
grep -q 'DecodeWorldTransition' \
  "$stage_root/Source/GahyeonStage/Private/Protocol/GahyeonProtocolPayloadDecoder.cpp"
grep -q 'AsyncSaveGameToSlot' \
  "$stage_root/Source/GahyeonStage/Private/Persistence/GahyeonRuntimePersistenceSubsystem.cpp"
grep -q 'CurrentSchemaVersion = 2' \
  "$stage_root/Source/GahyeonStage/Public/Persistence/GahyeonRuntimeSaveGame.h"
grep -q 'InteractionGeneration' \
  "$stage_root/Source/GahyeonStage/Private/Persistence/GahyeonRuntimeSaveMapper.cpp"
grep -q 'bSaveInFlight' \
  "$stage_root/Source/GahyeonStage/Private/Persistence/GahyeonRuntimePersistenceSubsystem.cpp"
grep -q 'Persistence->LoadAsync' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'Runtime->EnqueueInbound' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'ClassifyCallback' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'ConnectionGeneration != Generation' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'Runtime->GetRuntimeEpoch()' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'runtime epoch changed' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'runtime epoch changed before hello' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'RejectsCallbacksFromOldConnectionOrRuntimeEpoch' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonTransportConfigTest.cpp"
grep -q 'ValidatesServerHeartbeatContract' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonTransportConfigTest.cpp"
grep -q 'AcceptsOnlyCorrelatedHeartbeatPong' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonTransportConfigTest.cpp"
grep -q 'GetLastHeartbeatRttMillis' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'GetHeartbeatTimeoutCount' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'GetInvalidHeartbeatPongCount' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'StartHeartbeat(Generation, 10.0)' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'Envelope.Type == TEXT("server.welcome")' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q '!WeakThis->ApplyWelcomeHeartbeat' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'Self->SendProtocolJson(Self->BuildPing())' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'Envelope.Type == TEXT("server.pong")' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'WeakThis->ConsumeHeartbeatPong(' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q '!Self->PendingHeartbeatCorrelationId.IsEmpty()' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
python3 - "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
depth = 0
for character in source:
    if character == "{":
        depth += 1
    elif character == "}":
        depth -= 1
        if depth < 0:
            raise SystemExit("Unreal transport source has an unmatched closing brace")
if depth != 0:
    raise SystemExit(f"Unreal transport source has {depth} unmatched opening brace(s)")
if source.count("UGahyeonTransportSubsystem::BuildPing()") != 1:
    raise SystemExit("Unreal transport must define BuildPing exactly once")
PY
python3 - "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
depth = 0
for character in source:
    if character == "{":
        depth += 1
    elif character == "}":
        depth -= 1
        if depth < 0:
            raise SystemExit("Unreal debug source has an unmatched closing brace")
if depth != 0:
    raise SystemExit(f"Unreal debug source has {depth} unmatched opening brace(s)")
PY
grep -q 'ConnectionGeneration != Generation' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'IsCurrentCallback' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttWebSocketClient.cpp"
grep -q 'previous socket callback is rejected after reconnect' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonStreamingSttProtocolTest.cpp"
grep -q 'MaximumPendingMessageCallbacks = 128' \
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonStreamingSttWebSocketClient.h"
grep -q 'ResultIngressBackpressured' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttWebSocketClient.cpp"
grep -q 'ScheduleReconnect' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q 'FMath::FRandRange' \
  "$stage_root/Source/GahyeonStage/Private/Network/GahyeonTransportSubsystem.cpp"
grep -q '"GahyeonRuntimeCore"' \
  "$stage_root/Source/GahyeonStage/GahyeonStage.Build.cs"
grep -q 'Egress.PersistenceConfirmed' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'CompleteDurableEvent(Envelope.Sequence)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
for ephemeral_type in generation.advanced speech.prepared speech.sequence.ended gesture.intent attention.target; do
  grep -Fq "Envelope.Type == TEXT(\"$ephemeral_type\")" \
    "$stage_root/Source/GahyeonStage/Private/Protocol/GahyeonProtocolPayloadDecoder.cpp"
done
grep -q 'OnAudioInterruptRequested.Broadcast' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'AcquireNextSpeechSegment' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q '"HTTP"' "$stage_root/Source/GahyeonStage/GahyeonStage.Build.cs"
grep -q 'MaxAudioBytes = 32 \* 1024 \* 1024' \
  "$stage_root/Source/GahyeonStage/Public/Audio/GahyeonSpeechAudioComponent.h"
grep -q 'NotifySpeechPlaybackStarted' \
  "$stage_root/Source/GahyeonStage/Private/Audio/GahyeonSpeechAudioComponent.cpp"
grep -q 'HandleInterruptRequested' \
  "$stage_root/Source/GahyeonStage/Private/Audio/GahyeonSpeechAudioComponent.cpp"
grep -q 'PlaybackDeadlineSeconds' \
  "$stage_root/Source/GahyeonStage/Private/Audio/GahyeonSpeechAudioComponent.cpp"
grep -q 'ParsePcm16Wav' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonSpeechWavParserTest.cpp"
grep -q 'GetHttpOrigin' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonTransportConfigTest.cpp"
grep -q 'TActorIterator<AGahyeonPresentationHost>' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'NM_DedicatedServer' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'CreateDefaultSubobject<UGahyeonSpeechAudioComponent>' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonPresentationHost.cpp"
grep -q 'bEnableExceptions = true' "$runtime_module/GahyeonRuntimeCore.Build.cs"
grep -q 'AmbientMotionRuntime Ambient' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'TMap<FName, double> EmotionDimensions' \
  "$stage_root/Source/GahyeonStage/Public/Runtime/GahyeonRuntimeSubsystem.h"
grep -q 'Snapshot.EmotionDimensions.Add' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Snapshot.EmotionValence = Emotion.Valence' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Intents.Find(Gahyeon::IntentChannel::Posture)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Intents.Find(Gahyeon::IntentChannel::Attention)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Intents.Find(Gahyeon::IntentChannel::Expression)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'SetLocalAttentionTarget' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'GetPlayerViewPoint(CameraLocation, CameraRotation)' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'ExternalTrackerPrioritySeconds' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'LocalCameraUpdateIntervalSeconds' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'ConfigurePresentationProfile' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'OnPostureChanged.Broadcast' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'TSoftObjectPtr<UAnimMontage>' \
  "$stage_root/Source/GahyeonStage/Public/Presentation/GahyeonCharacterPresentationProfile.h"
grep -q 'Playback(Character, 16, &LipSync)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'OnAudioSingleEnvelopeValue.AddDynamic' \
  "$stage_root/Source/GahyeonStage/Private/Audio/GahyeonSpeechAudioComponent.cpp"
grep -q 'PrimaryVisemeWeight' \
  "$stage_root/Source/GahyeonStage/Public/Runtime/GahyeonRuntimeSubsystem.h"
grep -q 'NotifyWorldNavigationArrived' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'NotifyWorldActionFinished' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'Applied.ActionCompletion' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'WorldActions.Advance' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q '"AIModule"' "$stage_root/Source/GahyeonStage/GahyeonStage.Build.cs"
grep -q 'UNavigationSystemV1::SimpleMoveToLocation' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'AddTickPrerequisiteComponent(Presentation)' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'PendingCompletionActionId' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'StopLocalMotion(TEXT("authoritative_stop"))' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'OnActionStopped.Broadcast(StoppedActionId, TEXT("authoritative_stop"))' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'ResolveInteractionPoint' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'SupportedActivities' \
  "$stage_root/Source/GahyeonStage/Public/World/GahyeonInteractionPointComponent.h"
grep -q 'Existing->IsValid()' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonInteractionRegistrySubsystem.cpp"
grep -q 'SpawnActor<AGahyeonPrototypeRoom>' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonStageGameMode.cpp"
grep -q 'DeskPoint->Configure(TEXT("desk"), TEXT("workspace"), {TEXT("sit"), TEXT("work")})' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonPrototypeRoom.cpp"
grep -q 'ChairPoint->Configure(TEXT("chair"), TEXT("living_room"), {TEXT("sit"), TEXT("relax")})' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonPrototypeRoom.cpp"
grep -q 'PrimaryActorTick.bCanEverTick = false' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonPrototypeRoom.cpp"
grep -q 'FGahyeonInteractionPresentationDefinition' \
  "$stage_root/Source/GahyeonStage/Public/Presentation/GahyeonCharacterPresentationProfile.h"
grep -q 'RequestAsyncLoad' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'Montage_SetEndDelegate' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
if grep -R -q 'LoadSynchronous' \
    "$stage_root/Source/GahyeonStage/Private/Presentation" \
    "$stage_root/Source/GahyeonStage/Private/World"; then
  echo "blocking presentation asset load is forbidden" >&2
  exit 1
fi
grep -q 'AutoPossessAI = EAutoPossessAI::PlacedInWorldOrSpawned' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'CreateDefaultSubobject<UGahyeonWorldActionComponent>' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'TG_PostUpdateWork' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'LastFrameProgressAtSeconds' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'LipSync({}, &Trace)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Convergence.SnapshotApplied' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'ConnectionConvergenceState::AwaitingSnapshot' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'ConnectionConvergenceState::Converged' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'RuntimeCore->Voice.Tick(NowMs)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'TEXT("interaction.generation.advanced")' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Snapshot.CognitionTimeoutCount' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Result.InteractionGeneration.value_or(0)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Snapshot.ReconnectConvergenceTimeoutCount' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'ResetInboundForReconnect()' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'FScopeLock Lock(&InboundResetMutex)' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'NotifyInterruptedAudioStopped' \
  "$stage_root/Source/GahyeonStage/Private/Audio/GahyeonSpeechAudioComponent.cpp"
grep -q 'ConfirmConversationPoseApplied' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'ConfirmVisemeApplied' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"
grep -q 'NotifyVisemePresented' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'ConfirmVisemePresented' \
  "$stage_root/Source/GahyeonRuntimeCore/Private/LipSyncRuntime.cpp"
if sed -n '/LipSyncSample LipSyncRuntime::Sample/,/bool LipSyncRuntime::ConfirmVisemePresented/p' \
  "$stage_root/Source/GahyeonRuntimeCore/Private/LipSyncRuntime.cpp" \
  | grep -q 'LatencyMetric::VisemeOnsetOffset'; then
  echo "Viseme acceptance must not be recorded by timeline sampling" >&2
  exit 1
fi
grep -q 'IsPoseConfirmationCurrent' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonFacialCurveBindingTest.cpp"
grep -q 'ConfirmCurrentConversationPoseApplied' \
  "$stage_root/Source/GahyeonStage/Private/Animation/GahyeonCharacterAnimInstance.cpp"
grep -q 'ConfirmCurrentVisemeApplied' \
  "$stage_root/Source/GahyeonStage/Private/Animation/GahyeonCharacterAnimInstance.cpp"
grep -q 'bVisemeConfirmationPending = true' \
  "$stage_root/Source/GahyeonStage/Private/Animation/GahyeonCharacterAnimInstance.cpp"
grep -q 'bConversationPoseConfirmationPending = true' \
  "$stage_root/Source/GahyeonStage/Private/Animation/GahyeonCharacterAnimInstance.cpp"
if grep -q 'NativePostEvaluateAnimation' \
  "$stage_root/Source/GahyeonStage/Private/Animation/GahyeonCharacterAnimInstance.cpp"; then
  echo "Anim bridge must not auto-confirm a pose merely because graph evaluation ran" >&2
  exit 1
fi
if grep -q 'if (bListeningEntered)' \
  "$stage_root/Source/GahyeonStage/Private/Presentation/GahyeonCharacterPresentationComponent.cpp"; then
  echo "Listening latency must not auto-complete before the visible pose is applied" >&2
  exit 1
fi
grep -q 'FirstAudioPlayableLatency' \
  "$stage_root/Source/GahyeonStage/Public/Runtime/GahyeonRuntimeSubsystem.h"
grep -q 'perception.voice.started' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'perception.voice.ended' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'SubmitPartialTranscript' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'Character.PartialTranscriptObserved' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'SubmitFinalTranscript' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'if (bSent) NotifyFinalTranscriptSubmitted' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'TQueue<FVoiceObservation, EQueueMode::Mpsc>' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'MaximumPendingObservations = 512' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'ObserveMicrophoneLevelAt' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'CreateDefaultSubobject<UGahyeonVoiceInputComponent>' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'CreateDefaultSubobject<UStaticMeshComponent>(TEXT("DiagnosticBody"))' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'GetSkeletalMeshAsset() != nullptr' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'SetMovementMode(MOVE_Flying)' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'SetDrawOnScreen(bEnableDiagnosticOverlayWhenNoAvatar)' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'CreateDefaultSubobject<USpringArmComponent>' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'FRotator(-8.0f, 180.0f, 0.0f)' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q 'DefaultPawnClass = AGahyeonCharacterPawn::StaticClass()' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonStageGameMode.cpp"
grep -q 'ResolveHeroPawnClass' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonStageGameMode.cpp"
grep -q 'Settings->bRequireHeroAsset' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonStageGameMode.cpp"
grep -q 'Gahyeon.Hero.RuntimeClassBoundary' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonHeroRuntimeSettingsTest.cpp"
grep -q 'HeroPawnClass=/Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon_C' \
  "$stage_root/Config/DefaultGame.ini"
grep -q 'DirectoriesToAlwaysCook=(Path="/Game/GahyeonGenerated")' \
  "$stage_root/Config/DefaultGame.ini"
grep -q '"DeveloperSettings"' "$stage_root/Source/GahyeonStage/GahyeonStage.Build.cs"
grep -q 'GlobalDefaultGameMode=/Script/GahyeonStage.GahyeonStageGameMode' \
  "$stage_root/Config/DefaultEngine.ini"
grep -q 'Presentation->AddTickPrerequisiteComponent(VoiceInput)' \
  "$stage_root/Source/GahyeonStage/Private/Character/GahyeonCharacterPawn.cpp"
grep -q '"AudioCaptureCore"' "$stage_root/Source/GahyeonStage/GahyeonStage.Build.cs"
grep -q 'AudioCapture->OpenCaptureStream' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'const TSharedPtr<FGahyeonVoiceInputState, ESPMode::ThreadSafe> CallbackState' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'SumSquares += Sample \* Sample' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'IGahyeonStreamingSttAudioSink' \
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonVoiceInputComponent.h"
grep -q 'SttBackpressureCount.Increment' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'bStartCaptureOnBeginPlay = false' \
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonVoiceInputComponent.h"
grep -q 'capture_requires_client_world' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'VoiceActivityCancelled' \
  "$stage_root/Source/GahyeonStage/Public/Voice/GahyeonVoiceInputComponent.h"
grep -q 'DiscardAudioLevelObservations' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'Runtime->AbortMicrophoneCapture(Generation)' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'Runtime.Cancel(' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttAudioSink.cpp"
grep -q 'PcmRead.store(PcmWrite.load' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonStreamingSttAudioSink.cpp"
grep -q 'Voice.AbortActiveCapture' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'SendGenerationAdvance' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'if (ReconnectRequester) ReconnectRequester()' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'PcmUtteranceBuffer Buffer' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonBatchSttAudioSink.cpp"
grep -q 'MaximumPendingPcmChunks = 256' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonBatchSttAudioSink.cpp"
grep -q 'std::memcpy' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonBatchSttAudioSink.cpp"
grep -q 'std::counting_semaphore' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonBatchSttAudioSink.cpp"
grep -q 'bResetRequested.exchange' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonBatchSttAudioSink.cpp"
grep -q 'ResetSequence.fetch_add' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonBatchSttAudioSink.cpp"
grep -q 'GetRuntimeEpoch() != RuntimeEpoch' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'InvalidateWorkFromPreviousRuntime' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'GetRuntimeEpoch() != ObservedRuntimeEpoch' \
  "$stage_root/Source/GahyeonStage/Private/Audio/GahyeonSpeechAudioComponent.cpp"
grep -q 'SetTimeout(FMath::Clamp(BatchSttTimeoutSeconds' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'SetTimeout(FMath::Clamp(AudioDownloadTimeoutSeconds' \
  "$stage_root/Source/GahyeonStage/Private/Audio/GahyeonSpeechAudioComponent.cpp"
grep -q 'Frame.RuntimeEpoch != CurrentRuntimeEpoch' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'navigation_controller_unavailable' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'navigation_data_unavailable' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'ClassifiesNavigationReadinessWithoutMaskingMissingDependencies' \
  "$stage_root/Source/GahyeonStage/Private/Tests/GahyeonWorldActionReadinessTest.cpp"
grep -q 'GetNavigationReadinessLabel' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'GetRegisteredPointCount' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'ResolvedInteractionPoint->GetRoomId() != FName(\*CurrentTargetRoom)' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
python3 "$repo_root/scripts/verify_gahyeon_home_world_contract.py"
grep -q 'FGahyeonWorldCoordinateAdapter::ToCoreMeters' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'FGahyeonWorldCoordinateAdapter::ToUnrealCentimeters' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
if grep -q 'UnrealCentimetersPerWorldMeter' \
    "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"; then
  echo "raw Core/Unreal world coordinate conversion is forbidden" >&2
  exit 1
fi
grep -q 'RuntimeEpoch != CurrentRuntimeEpoch' \
  "$stage_root/Source/GahyeonStage/Private/World/GahyeonWorldActionComponent.cpp"
grep -q 'TakeFailedGeneration' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
if grep -q 'Samples.assign' \
    "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonBatchSttAudioSink.cpp"; then
  echo "capture callback PCM allocation is forbidden" >&2
  exit 1
fi
grep -q '/gahyeon/unreal/speech/transcriptions' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'EnqueueFinalTranscriptForGenerationFromAnyThread' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'Generation != RuntimeCore->Character.Intents().CurrentGeneration' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"
grep -q 'GetStaleSttResultCount' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'MaximumConcurrentRequests = 2' \
  "$stage_root/Source/GahyeonStage/Private/Voice/GahyeonVoiceInputComponent.cpp"
grep -q 'PcmUtteranceBuffer' \
  "$runtime_module/Public/Gahyeon/PcmUtteranceBuffer.h"
grep -q 'VoiceEndToFinalTranscriptLatency' \
  "$stage_root/Source/GahyeonStage/Private/Debug/GahyeonRuntimeDebugComponent.cpp"
grep -q 'LatencyMetric::VoiceEndToFinalTranscript' \
  "$stage_root/Source/GahyeonStage/Private/Runtime/GahyeonRuntimeSubsystem.cpp"

while IFS= read -r durable_type; do
  if ! grep -Fq "Envelope.Type == TEXT(\"$durable_type\")" \
      "$stage_root/Source/GahyeonStage/Private/Protocol/GahyeonProtocolPayloadDecoder.cpp"; then
    echo "durable fixture has no UE typed decoder: $durable_type" >&2
    exit 1
  fi
done < <(ruby -rjson -e '
  ARGV.each do |path|
    value = JSON.parse(File.read(path))
    puts value.fetch("type") if value.fetch("delivery") == "durable"
  end
' "$repo_root"/docs/contracts/fixtures/*.json | sort -u)

echo "Gahyeon Stage scaffold validation passed (static UE 5.6 checks)"
