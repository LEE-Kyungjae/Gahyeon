#include "Debug/GahyeonRuntimeDebugComponent.h"

#include "Engine/Engine.h"
#include "Engine/GameInstance.h"
#include "Engine/World.h"
#include "GameFramework/Actor.h"
#include "HAL/PlatformTime.h"
#include "Network/GahyeonTransportSubsystem.h"
#include "Presentation/GahyeonCharacterPresentationComponent.h"
#include "Voice/GahyeonVoiceInputComponent.h"
#include "World/GahyeonInteractionRegistrySubsystem.h"
#include "World/GahyeonWorldActionComponent.h"

UGahyeonRuntimeDebugComponent::UGahyeonRuntimeDebugComponent()
{
    PrimaryComponentTick.bCanEverTick = true;
    PrimaryComponentTick.bStartWithTickEnabled = true;
    PrimaryComponentTick.TickGroup = TG_PostUpdateWork;
}

void UGahyeonRuntimeDebugComponent::BeginPlay()
{
    Super::BeginPlay();
    const double Now = FPlatformTime::Seconds();
    NextUpdateAtSeconds = Now;
    LastFrameProgressAtSeconds = Now;
    RefreshPresentation();
}

void UGahyeonRuntimeDebugComponent::EndPlay(
    const EEndPlayReason::Type EndPlayReason)
{
    if (GEngine != nullptr)
    {
        GEngine->RemoveOnScreenDebugMessage(static_cast<uint64>(GetUniqueID()));
    }
    Presentation = nullptr;
    VoiceInput = nullptr;
    Transport = nullptr;
    WorldActions = nullptr;
    Super::EndPlay(EndPlayReason);
}

void UGahyeonRuntimeDebugComponent::TickComponent(
    float DeltaTime,
    ELevelTick TickType,
    FActorComponentTickFunction* ThisTickFunction)
{
    Super::TickComponent(DeltaTime, TickType, ThisTickFunction);
    const double Now = FPlatformTime::Seconds();
    if (Now < NextUpdateAtSeconds) return;
    NextUpdateAtSeconds = Now + UpdateIntervalSeconds;
    if (Presentation == nullptr || Transport == nullptr) RefreshPresentation();
    if (Presentation == nullptr)
    {
        bRuntimeStalled = true;
        return;
    }

    ObservedFrame = Presentation->GetFrame();
    if (ObservedFrame.PresentationFrames != LastPresentationFrame)
    {
        LastPresentationFrame = ObservedFrame.PresentationFrames;
        LastFrameProgressAtSeconds = Now;
    }
    bRuntimeStalled = Now - LastFrameProgressAtSeconds > StallThresholdSeconds;
    if (bDrawOnScreen && GEngine != nullptr)
    {
        GEngine->AddOnScreenDebugMessage(
            static_cast<uint64>(GetUniqueID()),
            static_cast<float>(UpdateIntervalSeconds * 1.5),
            bRuntimeStalled ? FColor::Red : FColor::Green,
            BuildStatusText(),
            false);
    }
}

FString UGahyeonRuntimeDebugComponent::BuildStatusText() const
{
    const auto Latency = [](const TCHAR* Name, const FGahyeonLatencySummary& Value)
    {
        return FString::Printf(
            TEXT("%s=%lld/%lld/%lld/%lld b%lld n%lld v%lld %s"),
            Name,
            Value.P50Ms,
            Value.P95Ms,
            Value.P99Ms,
            Value.WorstMs,
            Value.BudgetMs,
            Value.SampleCount,
            Value.BudgetViolations,
            Value.bPassesP95 ? TEXT("pass") : TEXT("fail"));
    };
    const FString VadLatency = Latency(TEXT("vad"), ObservedFrame.VadToListeningLatency);
    const FString BargeLatency = Latency(TEXT("barge"), ObservedFrame.BargeInToAudioStopLatency);
    const FString ReconnectLatency = Latency(
        TEXT("reconnect"), ObservedFrame.ReconnectToSnapshotLatency);
    const FString SttLatency = Latency(
        TEXT("stt"), ObservedFrame.VoiceEndToFinalTranscriptLatency);
    const FString AudioLatency = Latency(
        TEXT("audio"), ObservedFrame.FirstAudioPlayableLatency);
    const FString ThinkingLatency = Latency(
        TEXT("thinking"), ObservedFrame.TranscriptToThinkingLatency);
    const FString VisemeLatency = Latency(TEXT("viseme"), ObservedFrame.VisemeOnsetLatency);
    const double HeartbeatRtt = Transport != nullptr
        ? Transport->GetLastHeartbeatRttMillis() : -1.0;
    const FString HeartbeatRttText = HeartbeatRtt >= 0.0
        ? FString::Printf(TEXT("%.1fms"), HeartbeatRtt)
        : FString(TEXT("unknown"));
    const FString NavigationReadiness = WorldActions != nullptr
        ? WorldActions->GetNavigationReadinessLabel()
        : FString(TEXT("missing"));
    UWorld* World = GetWorld();
    const UGahyeonInteractionRegistrySubsystem* Registry = World != nullptr
        ? World->GetSubsystem<UGahyeonInteractionRegistrySubsystem>()
        : nullptr;
    return FString::Printf(
        TEXT("Gahyeon %s | backend=%s | ws=%s | heartbeat=%s rtt=%s ")
        TEXT("ok=%lld timeout=%lld invalid=%lld stale=%lld\n")
        TEXT("phase=%s | room=%s | activity=%s | nav=%s points=%d\n")
        TEXT("frames=%lld reflex=%lld behavior=%lld queue=%d dropped=%lld ")
        TEXT("timeout=%lld(g%lld) convergenceTimeout=%lld reconnectDiscard=%lld\n")
        TEXT("emotion=%s %.2f | action=%s/%s | viseme=%s %.2f\n")
        TEXT("lat p50/95/99/worst budget samples violations: %s | %s | %s\n")
        TEXT("lat %s | %s | %s | %s\n")
        TEXT("mic=%s pending=%d drop=%d overflow=%d sttBackpressure=%d lifeBackpressure=%d ")
        TEXT("sttFail=%d stale=%d ")
        TEXT("pcmDrop=%d lifeDrop=%d utteranceDrop=%d"),
        bRuntimeStalled ? TEXT("STALLED") : TEXT("alive"),
        ObservedFrame.bBackendConnected ? TEXT("connected") : TEXT("offline"),
        Transport != nullptr && Transport->IsSocketConnected()
            ? TEXT("connected") : TEXT("offline"),
        Transport != nullptr && Transport->IsHeartbeatAwaitingPong()
            ? TEXT("awaiting") : TEXT("idle"),
        *HeartbeatRttText,
        Transport != nullptr ? Transport->GetAcceptedHeartbeatPongCount() : 0,
        Transport != nullptr ? Transport->GetHeartbeatTimeoutCount() : 0,
        Transport != nullptr ? Transport->GetInvalidHeartbeatPongCount() : 0,
        Transport != nullptr ? Transport->GetStaleHeartbeatPongCount() : 0,
        *ObservedFrame.ConversationPhase,
        *ObservedFrame.CurrentRoom,
        *ObservedFrame.Activity,
        *NavigationReadiness,
        Registry != nullptr ? Registry->GetRegisteredPointCount() : 0,
        ObservedFrame.PresentationFrames,
        ObservedFrame.ReflexUpdates,
        ObservedFrame.BehaviorUpdates,
        ObservedFrame.InboundQueueDepth,
        ObservedFrame.DroppedInboundEvents,
        ObservedFrame.CognitionTimeoutCount,
        ObservedFrame.LastCognitionTimeoutGeneration,
        ObservedFrame.ReconnectConvergenceTimeoutCount,
        ObservedFrame.ReconnectDiscardedInboundEvents,
        *ObservedFrame.DominantEmotion,
        ObservedFrame.DominantEmotionIntensity,
        *ObservedFrame.ActiveWorldActionId,
        *ObservedFrame.WorldActionPhase,
        *ObservedFrame.PrimaryViseme,
        ObservedFrame.PrimaryVisemeWeight,
        *VadLatency,
        *BargeLatency,
        *ReconnectLatency,
        *SttLatency,
        *AudioLatency,
        *ThinkingLatency,
        *VisemeLatency,
        VoiceInput != nullptr && VoiceInput->IsMicrophoneCapturing()
            ? TEXT("capturing") : TEXT("stopped"),
        VoiceInput != nullptr ? VoiceInput->GetPendingObservationCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetDroppedObservationCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetCaptureOverflowCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetSttBackpressureCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetSttLifecycleBackpressureCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetBatchSttFailureCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetStaleSttResultCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetBatchPcmDropCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetBatchLifecycleDropCount() : 0,
        VoiceInput != nullptr ? VoiceInput->GetBatchUtteranceDropCount() : 0);
}

void UGahyeonRuntimeDebugComponent::RefreshPresentation()
{
    AActor* Owner = GetOwner();
    Presentation = Owner != nullptr
        ? Owner->FindComponentByClass<UGahyeonCharacterPresentationComponent>()
        : nullptr;
    VoiceInput = Owner != nullptr
        ? Owner->FindComponentByClass<UGahyeonVoiceInputComponent>()
        : nullptr;
    WorldActions = Owner != nullptr
        ? Owner->FindComponentByClass<UGahyeonWorldActionComponent>()
        : nullptr;
    UWorld* World = GetWorld();
    UGameInstance* GameInstance = World != nullptr ? World->GetGameInstance() : nullptr;
    Transport = GameInstance != nullptr
        ? GameInstance->GetSubsystem<UGahyeonTransportSubsystem>()
        : nullptr;
    if (Presentation != nullptr) AddTickPrerequisiteComponent(Presentation);
}
