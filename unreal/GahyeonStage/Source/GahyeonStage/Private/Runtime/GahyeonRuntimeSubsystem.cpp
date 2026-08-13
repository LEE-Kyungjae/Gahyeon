#include "Runtime/GahyeonRuntimeSubsystem.h"

#include "Dom/JsonObject.h"
#include "Engine/World.h"
#include "EngineUtils.h"
#include "Gahyeon/ClientRuntimeSaveState.h"
#include "Gahyeon/ConnectionConvergenceRuntime.h"
#include "Gahyeon/AmbientMotionRuntime.h"
#include "Gahyeon/AttentionRuntime.h"
#include "Gahyeon/EmotionRuntime.h"
#include "Gahyeon/GestureRuntime.h"
#include "Gahyeon/LipSyncRuntime.h"
#include "Gahyeon/LatencyTrace.h"
#include "Gahyeon/ProtocolEventRuntime.h"
#include "Gahyeon/ProtocolNetworkBridge.h"
#include "Gahyeon/ReplayCursorRuntime.h"
#include "Gahyeon/SpeechPlaybackCoordinator.h"
#include "Gahyeon/WorldActionCommandBridge.h"
#include "Gahyeon/WorldActionRuntime.h"
#include "Gahyeon/WorldStateRuntime.h"
#include "Gahyeon/VoiceInteractionController.h"
#include "HAL/PlatformTime.h"
#include "Misc/ScopeLock.h"
#include "Persistence/GahyeonRuntimePersistenceSubsystem.h"
#include "Persistence/GahyeonRuntimeSaveGame.h"
#include "Persistence/GahyeonRuntimeSaveMapper.h"
#include "Presentation/GahyeonPresentationHost.h"
#include "Presentation/GahyeonCharacterPresentationProfile.h"
#include "Protocol/GahyeonProtocolPayloadDecoder.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"
#include "World/GahyeonWorldCoordinateAdapter.h"

#include <cstdint>
#include <algorithm>

namespace
{
constexpr double MaximumSafeJsonInteger = 9007199254740991.0;

int64 MonotonicMillis()
{
    return static_cast<int64>(FPlatformTime::Seconds() * 1000.0);
}

TSharedPtr<FJsonObject> ParsePayload(const FString& Json)
{
    TSharedPtr<FJsonObject> Payload;
    const TSharedRef<TJsonReader<>> Reader = TJsonReaderFactory<>::Create(Json);
    return FJsonSerializer::Deserialize(Reader, Payload) ? Payload : nullptr;
}

FString FromUtf8(const std::string& Value)
{
    return FString(UTF8_TO_TCHAR(Value.c_str()));
}

FGahyeonLatencySummary ToUnrealLatency(const Gahyeon::LatencySummary& Summary)
{
    FGahyeonLatencySummary Result;
    Result.SampleCount = static_cast<int64>(Summary.TotalCount);
    Result.P50Ms = Summary.P50Ms;
    Result.P95Ms = Summary.P95Ms;
    Result.P99Ms = Summary.P99Ms;
    Result.WorstMs = Summary.WorstMs;
    Result.BudgetMs = Summary.BudgetMs;
    Result.BudgetViolations = static_cast<int64>(Summary.BudgetViolations);
    Result.bPassesP95 = Summary.PassesP95;
    return Result;
}

std::string ToUtf8(const FString& Value)
{
    return std::string(TCHAR_TO_UTF8(*Value));
}

FString SerializeOutbound(const Gahyeon::OutboundProtocolCommand& Command, const FString& SessionId)
{
    const FString MessageId = FGuid::NewGuid().ToString(EGuidFormats::DigitsWithHyphensLower);
    TSharedRef<FJsonObject> Payload = MakeShared<FJsonObject>();
    FString Type;
    FString CorrelationId;
    FString Delivery;
    if (Command.Type == Gahyeon::OutboundProtocolCommandType::ClientAck)
    {
        Type = TEXT("client.ack");
        CorrelationId = TEXT("ack:") + FString::Printf(TEXT("%lld"), Command.Sequence);
        Delivery = TEXT("ephemeral");
        Payload->SetNumberField(TEXT("sequence"), static_cast<double>(Command.Sequence));
    }
    else if (Command.Completion.has_value())
    {
        const Gahyeon::WorldActionCompletion& Completion = *Command.Completion;
        Type = TEXT("character.action.completed");
        CorrelationId = TEXT("world-action:") + FromUtf8(Completion.ActionId);
        Delivery = TEXT("command");
        Payload->SetStringField(TEXT("actionId"), FromUtf8(Completion.ActionId));
        Payload->SetNumberField(
            TEXT("expectedRevision"), static_cast<double>(Completion.ExpectedRevision));
        Payload->SetStringField(TEXT("outcome"), FromUtf8(Completion.Outcome));
        if (!Completion.Reason.empty())
        {
            Payload->SetStringField(TEXT("reason"), FromUtf8(Completion.Reason));
        }
        TSharedRef<FJsonObject> Position = MakeShared<FJsonObject>();
        Position->SetNumberField(TEXT("x"), Completion.FinalPosition.X);
        Position->SetNumberField(TEXT("y"), Completion.FinalPosition.Y);
        Position->SetNumberField(TEXT("z"), Completion.FinalPosition.Z);
        Payload->SetObjectField(TEXT("finalPosition"), Position);
    }
    else
    {
        return {};
    }

    TSharedRef<FJsonObject> Root = MakeShared<FJsonObject>();
    Root->SetStringField(TEXT("protocol"), TEXT("gahyeon.unreal.v1"));
    Root->SetNumberField(TEXT("schemaVersion"), 1);
    Root->SetStringField(TEXT("messageId"), MessageId);
    Root->SetStringField(TEXT("type"), Type);
    Root->SetStringField(TEXT("sentAt"), FDateTime::UtcNow().ToIso8601());
    Root->SetStringField(TEXT("sessionId"), SessionId);
    Root->SetStringField(TEXT("correlationId"), CorrelationId);
    Root->SetStringField(TEXT("delivery"), Delivery);
    Root->SetObjectField(TEXT("payload"), Payload);
    FString Json;
    const TSharedRef<TJsonWriter<>> Writer = TJsonWriterFactory<>::Create(&Json);
    return FJsonSerializer::Serialize(Root, Writer) ? Json : FString{};
}
}

struct UGahyeonRuntimeSubsystem::FRuntimeCoreState
{
    FRuntimeCoreState(int64 PersistedSequence, int64 NowMs)
        : Trace(), Convergence(2'000, &Trace), Cursor(PersistedSequence),
          Actions(), Egress(Cursor, Actions), Character(NowMs),
          LipSync({}, &Trace), Playback(Character, 16, &LipSync),
          Voice(Character, Playback, {}, &Trace), Emotion(), Gestures({}),
          Ambient(NowMs), Attention(NowMs), World(), WorldActions(), Events(
              Character, Playback, &Emotion, &Gestures, &World, &WorldActions)
    {
    }

    Gahyeon::LatencyTrace Trace;
    Gahyeon::ConnectionConvergenceRuntime Convergence;
    Gahyeon::ReplayCursorRuntime Cursor;
    Gahyeon::WorldActionCommandBridge Actions;
    Gahyeon::ProtocolNetworkEgressRuntime Egress;
    Gahyeon::RealtimeCharacterCoordinator Character;
    Gahyeon::LipSyncRuntime LipSync;
    Gahyeon::SpeechPlaybackCoordinator Playback;
    Gahyeon::VoiceInteractionController Voice;
    Gahyeon::EmotionRuntime Emotion;
    Gahyeon::GestureRuntime Gestures;
    Gahyeon::AmbientMotionRuntime Ambient;
    Gahyeon::AttentionRuntime Attention;
    Gahyeon::WorldStateRuntime World;
    Gahyeon::WorldActionRuntime WorldActions;
    Gahyeon::ProtocolEventRuntime Events;
    FString SessionId;
    int64 PlaybackPositionMs = 0;
    double PlaybackAmplitude = 0.0;
    std::optional<std::uint64_t> TranscriptThinkingSpan;
    std::optional<int64> TranscriptThinkingGeneration;
    std::optional<std::uint64_t> FirstAudioSpan;
    std::optional<int64> BatchSttStartedAtMs;
    std::optional<int64> BatchSttGeneration;
    std::uint64_t NextLocalSpan = (std::uint64_t{1} << 62);
};

UGahyeonRuntimeSubsystem::~UGahyeonRuntimeSubsystem() = default;

void UGahyeonRuntimeSubsystem::Initialize(FSubsystemCollectionBase& Collection)
{
    Super::Initialize(Collection);
    const double Now = FPlatformTime::Seconds();
    Snapshot = {};
    {
        FScopeLock Lock(&InboundResetMutex);
        FGahyeonProtocolEnvelope Discarded;
        while (InboundQueue.Dequeue(Discarded))
        {
        }
        InboundDepth.Reset();
        DeferredInbound.Reset();
    }
    DroppedInbound.Reset();
    Snapshot.MonotonicSeconds = Now;
    NextReflexAt = Now;
    NextBehaviorAt = Now;
    bInitialized = true;
    RuntimeCore = MakeUnique<FRuntimeCoreState>(0, MonotonicMillis());
    ++RuntimeEpoch;
}

void UGahyeonRuntimeSubsystem::Deinitialize()
{
    bInitialized = false;
    DestroyPresentationHost();
    ++PersistenceGeneration;
    ++RuntimeEpoch;
    RuntimeCore.Reset();
    OutboundSender = {};
    ReconnectRequester = {};
    DeferredInbound.Reset();
    Super::Deinitialize();
}

void UGahyeonRuntimeSubsystem::Tick(float DeltaTime)
{
    (void)DeltaTime;
    check(IsInGameThread());
    const double Now = FPlatformTime::Seconds();
    EnsurePresentationHost();
    Snapshot.MonotonicSeconds = Now;
    ++Snapshot.PresentationFrames;
    DrainInbound();

    // Catch up by one update only. A stalled frame must not create an update
    // storm that starves presentation or network mailbox draining.
    if (Now >= NextReflexAt)
    {
        AdvanceReflex(Now);
        NextReflexAt = Now + ReflexIntervalSeconds;
    }
    if (Now >= NextBehaviorAt)
    {
        AdvanceBehavior(Now);
        NextBehaviorAt = Now + BehaviorIntervalSeconds;
    }
    if (Snapshot.bBackendConnected)
    {
        SendNextPersistedEgress();
    }
    RefreshPresentationSnapshot(MonotonicMillis());
}

TStatId UGahyeonRuntimeSubsystem::GetStatId() const
{
    RETURN_QUICK_DECLARE_CYCLE_STAT(UGahyeonRuntimeSubsystem, STATGROUP_Tickables);
}

bool UGahyeonRuntimeSubsystem::IsTickable() const
{
    return bInitialized && !IsTemplate();
}

void UGahyeonRuntimeSubsystem::CopyLookingGlassAcceptanceLatencySamples(
    TArray<int64>& OutVadToListening,
    TArray<int64>& OutBargeInToAudioStop,
    TArray<int64>& OutAudioToViseme) const
{
    check(IsInGameThread());
    OutVadToListening.Reset();
    OutBargeInToAudioStop.Reset();
    OutAudioToViseme.Reset();
    if (!RuntimeCore.IsValid()) return;
    const auto Copy = [](const std::vector<Gahyeon::Millis>& Source, TArray<int64>& Target)
    {
        Target.Reserve(static_cast<int32>(Source.size()));
        for (const Gahyeon::Millis Value : Source) Target.Add(Value);
    };
    Copy(RuntimeCore->Trace.Samples(Gahyeon::LatencyMetric::VadToListening), OutVadToListening);
    Copy(RuntimeCore->Trace.Samples(Gahyeon::LatencyMetric::BargeInToAudioStop), OutBargeInToAudioStop);
    Copy(RuntimeCore->Trace.Samples(Gahyeon::LatencyMetric::VisemeOnsetOffset), OutAudioToViseme);
}

void UGahyeonRuntimeSubsystem::ResetLookingGlassAcceptanceLatencySamples()
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid()) return;
    RuntimeCore->Trace.ClearSamples(Gahyeon::LatencyMetric::VadToListening);
    RuntimeCore->Trace.ClearSamples(Gahyeon::LatencyMetric::BargeInToAudioStop);
    RuntimeCore->Trace.ClearSamples(Gahyeon::LatencyMetric::VisemeOnsetOffset);
}

void UGahyeonRuntimeSubsystem::EnsurePresentationHost()
{
    check(IsInGameThread());
    if (IsValid(PresentationHost)) return;
    UWorld* World = GetWorld();
    if (World == nullptr || (World->WorldType != EWorldType::Game
        && World->WorldType != EWorldType::PIE) || World->GetNetMode() == NM_DedicatedServer)
    {
        return;
    }
    for (TActorIterator<AGahyeonPresentationHost> Existing(World); Existing; ++Existing)
    {
        PresentationHost = *Existing;
        bOwnsPresentationHost = false;
        return;
    }
    FActorSpawnParameters Parameters;
    Parameters.Name = MakeUniqueObjectName(
        World, AGahyeonPresentationHost::StaticClass(), TEXT("GahyeonPresentationHost"));
    Parameters.ObjectFlags |= RF_Transient;
    Parameters.SpawnCollisionHandlingOverride =
        ESpawnActorCollisionHandlingMethod::AlwaysSpawn;
    PresentationHost = World->SpawnActor<AGahyeonPresentationHost>(
        AGahyeonPresentationHost::StaticClass(), FTransform::Identity, Parameters);
    bOwnsPresentationHost = IsValid(PresentationHost);
}

void UGahyeonRuntimeSubsystem::DestroyPresentationHost()
{
    check(IsInGameThread());
    if (bOwnsPresentationHost && IsValid(PresentationHost)) PresentationHost->Destroy();
    PresentationHost = nullptr;
    bOwnsPresentationHost = false;
}

bool UGahyeonRuntimeSubsystem::AcquireNextSpeechSegment(
    FGahyeonPreparedSpeechSegment& OutSegment)
{
    check(IsInGameThread());
    OutSegment = {};
    if (!RuntimeCore.IsValid()) return false;
    std::optional<Gahyeon::PreparedSpeech> Speech = RuntimeCore->Playback.AcquireNext();
    if (!Speech.has_value()) return false;
    OutSegment.Generation = Speech->GenerationId;
    OutSegment.UtteranceId = FromUtf8(Speech->UtteranceId);
    OutSegment.UtteranceIndex = Speech->UtteranceIndex;
    OutSegment.SegmentIndex = Speech->SegmentIndex;
    OutSegment.bFinalSegment = Speech->FinalSegment;
    OutSegment.AudioUrl = FromUtf8(Speech->AudioUrl);
    OutSegment.MimeType = FromUtf8(Speech->MimeType);
    OutSegment.Visemes.Reserve(static_cast<int32>(Speech->Visemes.size()));
    for (const Gahyeon::VisemeCue& Cue : Speech->Visemes)
    {
        FGahyeonVisemeCue& OutCue = OutSegment.Visemes.AddDefaulted_GetRef();
        OutCue.Semantic = FromUtf8(Cue.Semantic);
        OutCue.AtMs = Cue.AtMs;
        OutCue.DurationMs = Cue.DurationMs;
        OutCue.Weight = Cue.Weight;
    }
    return true;
}

bool UGahyeonRuntimeSubsystem::NotifySpeechPlaybackStarted(const FString& UtteranceId)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || !RuntimeCore->Playback.PlaybackStarted(
        ToUtf8(UtteranceId), MonotonicMillis())) return false;
    RuntimeCore->PlaybackPositionMs = 0;
    RuntimeCore->PlaybackAmplitude = 0.0;
    if (RuntimeCore->FirstAudioSpan.has_value())
    {
        RuntimeCore->Trace.End(*RuntimeCore->FirstAudioSpan, MonotonicMillis());
        RuntimeCore->FirstAudioSpan.reset();
    }
    return true;
}

bool UGahyeonRuntimeSubsystem::NotifySpeechPlaybackFinished(const FString& UtteranceId)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || !RuntimeCore->Playback.PlaybackFinished(
        ToUtf8(UtteranceId), MonotonicMillis())) return false;
    RuntimeCore->PlaybackPositionMs = 0;
    RuntimeCore->PlaybackAmplitude = 0.0;
    return true;
}

bool UGahyeonRuntimeSubsystem::NotifySpeechPlaybackFailed(const FString& UtteranceId)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || !RuntimeCore->Playback.PlaybackFailed(
        ToUtf8(UtteranceId), MonotonicMillis())) return false;
    RuntimeCore->PlaybackPositionMs = 0;
    RuntimeCore->PlaybackAmplitude = 0.0;
    return true;
}

void UGahyeonRuntimeSubsystem::UpdateSpeechPlaybackSample(
    int64 PlaybackPositionMs,
    double Amplitude)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || PlaybackPositionMs < 0
        || !FMath::IsFinite(Amplitude))
    {
        return;
    }
    RuntimeCore->PlaybackPositionMs = PlaybackPositionMs;
    RuntimeCore->PlaybackAmplitude = FMath::Clamp(Amplitude, 0.0, 1.0);
}

bool UGahyeonRuntimeSubsystem::SetLocalAttentionTarget(
    FVector LocalTarget,
    double Confidence)
{
    check(IsInGameThread());
    return RuntimeCore.IsValid() && RuntimeCore->Attention.SetUserTarget({
        .Forward = LocalTarget.X,
        .Right = LocalTarget.Y,
        .Up = LocalTarget.Z}, Confidence, MonotonicMillis());
}

void UGahyeonRuntimeSubsystem::ClearAttentionTarget()
{
    check(IsInGameThread());
    if (RuntimeCore.IsValid()) RuntimeCore->Attention.ClearTarget(MonotonicMillis());
}

bool UGahyeonRuntimeSubsystem::ObserveMicrophoneLevel(double NormalizedLevel)
{
    return ObserveMicrophoneLevelAt(NormalizedLevel, MonotonicMillis());
}

bool UGahyeonRuntimeSubsystem::ObserveMicrophoneLevelAt(
    double NormalizedLevel,
    int64 ObservedAtMs)
{
    return ObserveMicrophoneLevelAtDetailed(NormalizedLevel, ObservedAtMs).Edge
        != EGahyeonVoiceActivityEdge::None;
}

FGahyeonVoiceActivityObservation
UGahyeonRuntimeSubsystem::ObserveMicrophoneLevelAtDetailed(
    double NormalizedLevel,
    int64 ObservedAtMs)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || !FMath::IsFinite(NormalizedLevel) || ObservedAtMs < 0)
    {
        return {};
    }
    const Gahyeon::VoiceInteractionResult Result = RuntimeCore->Voice.Observe(
        FMath::Clamp(NormalizedLevel, 0.0, 1.0), ObservedAtMs);
    if (Result.Event == Gahyeon::VoiceActivityEvent::Started)
    {
        RuntimeCore->BatchSttStartedAtMs.reset();
        RuntimeCore->BatchSttGeneration.reset();
        RuntimeCore->Gestures.SetGeneration(Result.GenerationId);
        const FVector Position = Snapshot.WorldPosition;
        const std::optional<Gahyeon::WorldActionCompletion> Completion =
            RuntimeCore->WorldActions.SetGeneration(
                Result.GenerationId,
                ObservedAtMs,
                FGahyeonWorldCoordinateAdapter::ToCoreMeters(Position));
        if (Completion.has_value()) QueueRuntimeActionCompletion(*Completion);
        PersistRuntimeState(0);
        if (Result.InterruptedUtteranceId.has_value())
        {
            OnAudioInterruptRequested.Broadcast(FromUtf8(*Result.InterruptedUtteranceId));
        }
        TSharedRef<FJsonObject> Payload = MakeShared<FJsonObject>();
        SendPerceptionMessage(
            TEXT("perception.voice.started"), TEXT("ephemeral"),
            Result.GenerationId, Payload);
    }
    else if (Result.Event == Gahyeon::VoiceActivityEvent::Ended)
    {
        RuntimeCore->BatchSttStartedAtMs = ObservedAtMs;
        RuntimeCore->BatchSttGeneration = Result.GenerationId;
        TSharedRef<FJsonObject> Payload = MakeShared<FJsonObject>();
        SendPerceptionMessage(
            TEXT("perception.voice.ended"), TEXT("ephemeral"),
            Result.GenerationId, Payload);
    }
    return {
        .Edge = Result.Event == Gahyeon::VoiceActivityEvent::Started
            ? EGahyeonVoiceActivityEdge::Started
            : Result.Event == Gahyeon::VoiceActivityEvent::Ended
                ? EGahyeonVoiceActivityEdge::Ended
                : EGahyeonVoiceActivityEdge::None,
        .GenerationId = Result.GenerationId};
}

bool UGahyeonRuntimeSubsystem::AbortMicrophoneCapture(int64& OutAbortedGeneration)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid()) return false;
    const int64 NowMs = MonotonicMillis();
    OutAbortedGeneration = static_cast<int64>(
        RuntimeCore->Character.Intents().CurrentGeneration());
    const std::optional<Gahyeon::CognitionTimeoutResult> Cancelled =
        RuntimeCore->Voice.AbortActiveCapture(NowMs);
    if (!Cancelled.has_value()) return false;

    RuntimeCore->BatchSttStartedAtMs.reset();
    RuntimeCore->BatchSttGeneration.reset();
    if (RuntimeCore->TranscriptThinkingSpan.has_value())
    {
        RuntimeCore->Trace.Cancel(*RuntimeCore->TranscriptThinkingSpan);
        RuntimeCore->TranscriptThinkingSpan.reset();
        RuntimeCore->TranscriptThinkingGeneration.reset();
    }
    if (RuntimeCore->FirstAudioSpan.has_value())
    {
        RuntimeCore->Trace.Cancel(*RuntimeCore->FirstAudioSpan);
        RuntimeCore->FirstAudioSpan.reset();
    }
    RuntimeCore->Gestures.SetGeneration(Cancelled->GenerationId);
    const FVector Position = Snapshot.bHasWorldState
        ? Snapshot.WorldPosition
        : FVector::ZeroVector;
    const std::optional<Gahyeon::WorldActionCompletion> CancelledAction =
        RuntimeCore->WorldActions.SetGeneration(
            Cancelled->GenerationId,
            NowMs,
            FGahyeonWorldCoordinateAdapter::ToCoreMeters(Position));
    if (CancelledAction.has_value()) QueueRuntimeActionCompletion(*CancelledAction);
    if (Cancelled->InterruptedUtteranceId.has_value())
    {
        OnAudioInterruptRequested.Broadcast(FromUtf8(*Cancelled->InterruptedUtteranceId));
    }
    PersistRuntimeState(0);
    SendGenerationAdvance(
        Cancelled->GenerationId,
        TEXT("microphone_capture_aborted"));
    return true;
}

bool UGahyeonRuntimeSubsystem::SubmitPartialTranscript(
    const FString& Text,
    double Stability)
{
    return RuntimeCore.IsValid() && SubmitPartialTranscriptForGeneration(
        Text, Stability, RuntimeCore->Character.Intents().CurrentGeneration());
}

bool UGahyeonRuntimeSubsystem::SubmitPartialTranscriptForGeneration(
    const FString& Text,
    double Stability,
    int64 Generation)
{
    check(IsInGameThread());
    const FString CleanText = Text.TrimStartAndEnd();
    if (!RuntimeCore.IsValid() || CleanText.IsEmpty() || !FMath::IsFinite(Stability)
        || Generation != RuntimeCore->Character.Intents().CurrentGeneration())
    {
        return false;
    }
    if (RuntimeCore->Character.PartialTranscriptObserved(
        static_cast<Gahyeon::Generation>(Generation),
        FMath::Clamp(Stability, 0.0, 1.0),
        MonotonicMillis()) != Gahyeon::PublishResult::Accepted)
    {
        return false;
    }
    TSharedRef<FJsonObject> Payload = MakeShared<FJsonObject>();
    Payload->SetStringField(TEXT("text"), CleanText);
    Payload->SetNumberField(TEXT("stability"), FMath::Clamp(Stability, 0.0, 1.0));
    return SendPerceptionMessage(
        TEXT("perception.transcript.partial"), TEXT("ephemeral"),
        Generation, Payload);
}

bool UGahyeonRuntimeSubsystem::SubmitFinalTranscript(
    const FString& Text,
    const FString& Language)
{
    return RuntimeCore.IsValid() && SubmitFinalTranscriptForGeneration(
        Text, Language, RuntimeCore->Character.Intents().CurrentGeneration());
}

bool UGahyeonRuntimeSubsystem::SubmitFinalTranscriptForGeneration(
    const FString& Text,
    const FString& Language,
    int64 Generation)
{
    check(IsInGameThread());
    const FString CleanText = Text.TrimStartAndEnd();
    const FString CleanLanguage = Language.TrimStartAndEnd();
    if (!RuntimeCore.IsValid() || CleanText.IsEmpty() || CleanLanguage.IsEmpty()
        || Generation != RuntimeCore->Character.Intents().CurrentGeneration())
    {
        return false;
    }
    if (RuntimeCore->BatchSttStartedAtMs.has_value()
        && RuntimeCore->BatchSttGeneration == Generation)
    {
        RuntimeCore->Trace.Record(
            Gahyeon::LatencyMetric::VoiceEndToFinalTranscript,
            FMath::Max<int64>(0, MonotonicMillis() - *RuntimeCore->BatchSttStartedAtMs));
        RuntimeCore->BatchSttStartedAtMs.reset();
        RuntimeCore->BatchSttGeneration.reset();
    }
    TSharedRef<FJsonObject> Payload = MakeShared<FJsonObject>();
    Payload->SetStringField(TEXT("text"), CleanText);
    Payload->SetStringField(TEXT("language"), CleanLanguage);
    const bool bSent = SendPerceptionMessage(
        TEXT("perception.transcript.final"), TEXT("command"),
        Generation, Payload);
    if (bSent) NotifyFinalTranscriptSubmitted();
    return bSent;
}

void UGahyeonRuntimeSubsystem::NotifyBatchSttFailed(int64 Generation)
{
    check(IsInGameThread());
    if (RuntimeCore.IsValid() && RuntimeCore->BatchSttStartedAtMs.has_value()
        && RuntimeCore->BatchSttGeneration == Generation)
    {
        RuntimeCore->BatchSttStartedAtMs.reset();
        RuntimeCore->BatchSttGeneration.reset();
        const std::optional<Gahyeon::CognitionTimeoutResult> Cancelled =
            RuntimeCore->Voice.FailRecognition(Generation, MonotonicMillis());
        if (!Cancelled.has_value()) return;
        if (RuntimeCore->TranscriptThinkingSpan.has_value())
        {
            RuntimeCore->Trace.Cancel(*RuntimeCore->TranscriptThinkingSpan);
            RuntimeCore->TranscriptThinkingSpan.reset();
            RuntimeCore->TranscriptThinkingGeneration.reset();
        }
        if (RuntimeCore->FirstAudioSpan.has_value())
        {
            RuntimeCore->Trace.Cancel(*RuntimeCore->FirstAudioSpan);
            RuntimeCore->FirstAudioSpan.reset();
        }
        RuntimeCore->Gestures.SetGeneration(Cancelled->GenerationId);
        const FVector Position = Snapshot.bHasWorldState
            ? Snapshot.WorldPosition
            : FVector::ZeroVector;
        const std::optional<Gahyeon::WorldActionCompletion> CancelledAction =
            RuntimeCore->WorldActions.SetGeneration(
                Cancelled->GenerationId,
                MonotonicMillis(),
                FGahyeonWorldCoordinateAdapter::ToCoreMeters(Position));
        if (CancelledAction.has_value()) QueueRuntimeActionCompletion(*CancelledAction);
        if (Cancelled->InterruptedUtteranceId.has_value())
        {
            OnAudioInterruptRequested.Broadcast(
                FromUtf8(*Cancelled->InterruptedUtteranceId));
        }
        PersistRuntimeState(0);
        SendGenerationAdvance(Cancelled->GenerationId, TEXT("stt_failed"));
    }
}

bool UGahyeonRuntimeSubsystem::NotifyListeningPresented(
    int64 Generation,
    uint64 PresentationRuntimeEpoch)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || PresentationRuntimeEpoch != RuntimeEpoch
        || Generation != RuntimeCore->Character.Intents().CurrentGeneration()
        || Snapshot.ConversationPhase != TEXT("listening"))
    {
        return false;
    }
    return RuntimeCore->Voice.MarkListeningPresented(Generation, MonotonicMillis())
        == Gahyeon::LatencyTraceResult::Recorded;
}

void UGahyeonRuntimeSubsystem::NotifyInterruptedAudioStopped()
{
    check(IsInGameThread());
    if (RuntimeCore.IsValid())
    {
        RuntimeCore->Voice.MarkInterruptedAudioStopped(
            RuntimeCore->Character.Intents().CurrentGeneration(), MonotonicMillis());
    }
}

void UGahyeonRuntimeSubsystem::NotifyFinalTranscriptSubmitted()
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid()) return;
    if (RuntimeCore->TranscriptThinkingSpan.has_value())
    {
        RuntimeCore->Trace.Cancel(*RuntimeCore->TranscriptThinkingSpan);
    }
    if (RuntimeCore->FirstAudioSpan.has_value())
    {
        RuntimeCore->Trace.Cancel(*RuntimeCore->FirstAudioSpan);
    }
    const std::uint64_t ThinkingSpan = ++RuntimeCore->NextLocalSpan;
    const std::uint64_t AudioSpan = ++RuntimeCore->NextLocalSpan;
    const int64 NowMs = MonotonicMillis();
    RuntimeCore->Trace.Begin(
        Gahyeon::LatencyMetric::TranscriptToThinking, ThinkingSpan, NowMs);
    RuntimeCore->Trace.Begin(
        Gahyeon::LatencyMetric::FirstAudioPlayable, AudioSpan, NowMs);
    RuntimeCore->TranscriptThinkingSpan = ThinkingSpan;
    RuntimeCore->TranscriptThinkingGeneration =
        RuntimeCore->Character.Intents().CurrentGeneration();
    RuntimeCore->FirstAudioSpan = AudioSpan;
}

bool UGahyeonRuntimeSubsystem::NotifyThinkingPresented(
    int64 Generation,
    uint64 PresentationRuntimeEpoch)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || PresentationRuntimeEpoch != RuntimeEpoch
        || Generation != RuntimeCore->Character.Intents().CurrentGeneration()
        || Snapshot.ConversationPhase != TEXT("thinking")
        || !RuntimeCore->TranscriptThinkingSpan.has_value()
        || RuntimeCore->TranscriptThinkingGeneration != Generation)
    {
        return false;
    }
    const Gahyeon::LatencyTraceResult Result = RuntimeCore->Trace.End(
        *RuntimeCore->TranscriptThinkingSpan, MonotonicMillis());
    if (Result == Gahyeon::LatencyTraceResult::Recorded)
    {
        RuntimeCore->TranscriptThinkingSpan.reset();
        RuntimeCore->TranscriptThinkingGeneration.reset();
        return true;
    }
    return false;
}

bool UGahyeonRuntimeSubsystem::NotifyVisemePresented(
    const FString& Semantic,
    int64 Generation,
    uint64 PresentationRuntimeEpoch)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || Semantic.IsEmpty()
        || PresentationRuntimeEpoch != RuntimeEpoch
        || Generation != RuntimeCore->Character.Intents().CurrentGeneration()
        || Generation != RuntimeCore->LipSync.CurrentGeneration()
        || !Snapshot.bLipSyncActive
        || (Snapshot.PrimaryViseme != Semantic && Snapshot.SecondaryViseme != Semantic))
    {
        return false;
    }
    return RuntimeCore->LipSync.ConfirmVisemePresented(
        ToUtf8(Semantic), RuntimeCore->PlaybackPositionMs);
}

bool UGahyeonRuntimeSubsystem::ConfigurePresentationProfile(
    const UGahyeonCharacterPresentationProfile& Profile)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid()) return false;
    std::vector<Gahyeon::GestureDefinition> Definitions;
    Definitions.reserve(static_cast<std::size_t>(Profile.Gestures.Num()));
    for (const FGahyeonGesturePresentationDefinition& Entry : Profile.Gestures)
    {
        Definitions.push_back({
            .Semantic = ToUtf8(Entry.Semantic.ToString()),
            .VariantId = ToUtf8(Entry.VariantId.ToString()),
            .RequiredPosture = Entry.RequiredPosture.IsNone()
                ? std::nullopt
                : std::optional<std::string>(ToUtf8(Entry.RequiredPosture.ToString())),
            .MinIntensity = Entry.MinIntensity,
            .MaxIntensity = Entry.MaxIntensity,
            .DurationMs = Entry.DurationMs,
            .CooldownMs = Entry.CooldownMs,
            .Interruptible = Entry.bInterruptible,
            .SelectionWeight = Entry.SelectionWeight});
    }
    return RuntimeCore->Gestures.ConfigureDefinitions(MoveTemp(Definitions));
}

void UGahyeonRuntimeSubsystem::SetBackendConnected(bool bConnected)
{
    check(IsInGameThread());
    Snapshot.bBackendConnected = bConnected;
    if (!bConnected)
    {
        ResetInboundForReconnect();
        if (RuntimeCore.IsValid()) RuntimeCore->Convergence.Disconnected();
    }
}

bool UGahyeonRuntimeSubsystem::EnqueueInbound(FGahyeonProtocolEnvelope Envelope)
{
    FScopeLock Lock(&InboundResetMutex);
    const bool bDroppableLatestState = Envelope.Delivery == TEXT("ephemeral")
        && (Envelope.Type == TEXT("attention.target")
            || Envelope.Type == TEXT("gesture.intent")
            || Envelope.Type == TEXT("cognition.request.started")
            || Envelope.Type == TEXT("cognition.response.completed")
            || Envelope.Type == TEXT("cognition.request.cancelled"));
    const int32 Depth = InboundDepth.Increment();
    const int32 Limit = bDroppableLatestState
        ? MaxLatestStateInboundQueueDepth
        : MaxInboundQueueDepth;
    if (Depth > Limit)
    {
        InboundDepth.Decrement();
        DroppedInbound.Increment();
        // Only replaceable semantic latest-state may be dropped without corrupting speech,
        // connection control, commands, or the durable cursor.
        return bDroppableLatestState;
    }
    InboundQueue.Enqueue(MoveTemp(Envelope));
    return true;
}

bool UGahyeonRuntimeSubsystem::RestorePersistentState(
    const UGahyeonRuntimeSaveGame& State)
{
    check(IsInGameThread());
    const Gahyeon::Generation InMemoryGeneration = RuntimeCore.IsValid()
        ? RuntimeCore->Character.Intents().CurrentGeneration()
        : 0;
    Gahyeon::ClientRuntimeSaveState RuntimeState;
    FString Error;
    if (!FGahyeonRuntimeSaveMapper::ToRuntime(State, RuntimeState, Error))
    {
        return false;
    }

    TUniquePtr<FRuntimeCoreState> Restored =
        MakeUnique<FRuntimeCoreState>(RuntimeState.DurableSequence, MonotonicMillis());
    const Gahyeon::ClientRuntimeRestoreResult Result =
        Gahyeon::ClientRuntimeSaveStateCodec::Restore(
            MoveTemp(RuntimeState), Restored->Actions, MonotonicMillis());
    if (Result.Result != Gahyeon::ClientSaveStateResult::Restored)
    {
        return false;
    }
    const Gahyeon::Generation RestoredGeneration = std::max(
        InMemoryGeneration,
        Result.InteractionGeneration.value_or(0));
    if (RestoredGeneration > 0)
    {
        Restored->Character.SynchronizeGeneration(RestoredGeneration);
        Restored->Playback.SetGeneration(RestoredGeneration);
        Restored->Gestures.SetGeneration(RestoredGeneration);
        Restored->WorldActions.SetGeneration(
            RestoredGeneration, MonotonicMillis(), {0.0, 0.0, 0.0});
    }
    for (const FGahyeonSavedActionCompletion& Pending : State.PendingActions)
    {
        if (!Restored->Egress.ActionPersistenceConfirmed(ToUtf8(Pending.ActionId)))
        {
            return false;
        }
    }
    ++PersistenceGeneration;
    RuntimeCore = MoveTemp(Restored);
    ++RuntimeEpoch;
    {
        FScopeLock Lock(&InboundResetMutex);
        FGahyeonProtocolEnvelope Discarded;
        while (InboundQueue.Dequeue(Discarded))
        {
        }
        InboundDepth.Reset();
        DeferredInbound.Reset();
    }
    Snapshot.InboundQueueDepth = 0;
    RefreshPresentationSnapshot(MonotonicMillis());
    if (RestoredGeneration > Result.InteractionGeneration.value_or(0))
    {
        PersistRuntimeState(0);
    }
    return true;
}

void UGahyeonRuntimeSubsystem::BeginBackendConnection()
{
    check(IsInGameThread());
    if (RuntimeCore.IsValid())
    {
        RuntimeCore->Cursor.BeginConnection();
        RuntimeCore->Convergence.BeginConnection(MonotonicMillis());
        Snapshot.bBackendConnected = false;
    }
}

void UGahyeonRuntimeSubsystem::SetOutboundSender(FOutboundSender Sender)
{
    check(IsInGameThread());
    OutboundSender = MoveTemp(Sender);
}

void UGahyeonRuntimeSubsystem::SetReconnectRequester(FReconnectRequester Requester)
{
    check(IsInGameThread());
    ReconnectRequester = MoveTemp(Requester);
}

bool UGahyeonRuntimeSubsystem::SendPerceptionMessage(
    const FString& Type,
    const FString& Delivery,
    int64 Generation,
    const TSharedRef<FJsonObject>& Payload)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || !Snapshot.bBackendConnected || !OutboundSender
        || RuntimeCore->SessionId.IsEmpty() || Type.IsEmpty() || Generation < 0)
    {
        return false;
    }
    Payload->SetNumberField(TEXT("generation"), static_cast<double>(Generation));
    TSharedRef<FJsonObject> Root = MakeShared<FJsonObject>();
    Root->SetStringField(TEXT("protocol"), TEXT("gahyeon.unreal.v1"));
    Root->SetNumberField(TEXT("schemaVersion"), 1);
    Root->SetStringField(
        TEXT("messageId"), FGuid::NewGuid().ToString(EGuidFormats::DigitsWithHyphensLower));
    Root->SetStringField(TEXT("type"), Type);
    Root->SetStringField(TEXT("sentAt"), FDateTime::UtcNow().ToIso8601());
    Root->SetStringField(TEXT("sessionId"), RuntimeCore->SessionId);
    Root->SetStringField(
        TEXT("correlationId"), FString::Printf(TEXT("voice:%lld"), Generation));
    Root->SetStringField(TEXT("delivery"), Delivery);
    Root->SetObjectField(TEXT("payload"), Payload);
    FString Json;
    const TSharedRef<TJsonWriter<>> Writer = TJsonWriterFactory<>::Create(&Json);
    return FJsonSerializer::Serialize(Root, Writer) && OutboundSender(Json);
}

bool UGahyeonRuntimeSubsystem::SendGenerationAdvance(
    int64 Generation,
    const FString& Reason)
{
    check(IsInGameThread());
    TSharedRef<FJsonObject> Payload = MakeShared<FJsonObject>();
    Payload->SetStringField(TEXT("reason"), Reason);
    if (SendPerceptionMessage(
        TEXT("interaction.generation.advanced"),
        TEXT("ephemeral"),
        Generation,
        Payload))
    {
        return true;
    }
    // Keeping a half-open session would allow stale Cognition/TTS to consume resources.
    // Reconnect closes/releases the old Backend session and converges the generation watermark.
    Snapshot.bBackendConnected = false;
    if (ReconnectRequester) ReconnectRequester();
    return false;
}

bool UGahyeonRuntimeSubsystem::QueueActionCompletion(
    const FString& ActionId,
    int64 ExpectedRevision,
    const FString& Outcome,
    const FString& Reason,
    FVector FinalPosition)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || ActionId.IsEmpty() || ExpectedRevision < 0
        || static_cast<double>(ExpectedRevision) > MaximumSafeJsonInteger
        || FinalPosition.ContainsNaN())
    {
        return false;
    }
    const Gahyeon::CompletionOutboxResult Result = RuntimeCore->Actions.Queue({
        .ActionId = ToUtf8(ActionId),
        .ExpectedRevision = ExpectedRevision,
        .Outcome = ToUtf8(Outcome),
        .Reason = ToUtf8(Reason),
        .FinalPosition = FGahyeonWorldCoordinateAdapter::ToCoreMeters(FinalPosition)},
        MonotonicMillis());
    if (Result != Gahyeon::CompletionOutboxResult::Accepted)
    {
        return Result == Gahyeon::CompletionOutboxResult::Duplicate;
    }
    PersistRuntimeState(0, ActionId);
    return true;
}

bool UGahyeonRuntimeSubsystem::QueueRuntimeActionCompletion(
    const Gahyeon::WorldActionCompletion& Completion)
{
    if (!RuntimeCore.IsValid()) return false;
    const Gahyeon::CompletionOutboxResult Result = RuntimeCore->Actions.Queue(
        Completion, MonotonicMillis());
    if (Result != Gahyeon::CompletionOutboxResult::Accepted)
    {
        return Result == Gahyeon::CompletionOutboxResult::Duplicate;
    }
    PersistRuntimeState(0, FromUtf8(Completion.ActionId));
    return true;
}

bool UGahyeonRuntimeSubsystem::NotifyWorldNavigationArrived(const FString& ActionId)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || ActionId.IsEmpty()) return false;
    const Gahyeon::WorldActionResult Result =
        RuntimeCore->WorldActions.NavigationArrived(ToUtf8(ActionId), MonotonicMillis());
    return Result == Gahyeon::WorldActionResult::Accepted
        || Result == Gahyeon::WorldActionResult::Duplicate;
}

bool UGahyeonRuntimeSubsystem::NotifyWorldActionFinished(
    const FString& ActionId,
    const FString& Outcome,
    const FString& Reason,
    FVector FinalPosition)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || ActionId.IsEmpty() || FinalPosition.ContainsNaN())
    {
        return false;
    }
    std::optional<Gahyeon::WorldActionCompletion> Completion =
        RuntimeCore->WorldActions.Complete(
            ToUtf8(ActionId),
            ToUtf8(Outcome),
            ToUtf8(Reason),
            FGahyeonWorldCoordinateAdapter::ToCoreMeters(FinalPosition),
            MonotonicMillis());
    return Completion.has_value() && QueueRuntimeActionCompletion(*Completion);
}

void UGahyeonRuntimeSubsystem::DrainInbound()
{
    check(IsInGameThread());
    if (DeferredInbound.IsSet())
    {
        const EInboundApplyResult Result = ApplyInbound(DeferredInbound.GetValue());
        if (Result == EInboundApplyResult::Backpressured)
        {
            Snapshot.InboundQueueDepth = InboundDepth.GetValue() + 1;
            Snapshot.DroppedInboundEvents = DroppedInbound.GetValue();
            return;
        }
        DeferredInbound.Reset();
        if (Result == EInboundApplyResult::ReconnectRequired)
        {
            ResetInboundForReconnect();
            return;
        }
    }

    FGahyeonProtocolEnvelope Envelope;
    int32 Drained = 0;
    while (Drained < MaxInboundEventsPerFrame && InboundQueue.Dequeue(Envelope))
    {
        InboundDepth.Decrement();
        const EInboundApplyResult Result = ApplyInbound(Envelope);
        if (Result == EInboundApplyResult::Backpressured)
        {
            DeferredInbound = MoveTemp(Envelope);
            break;
        }
        if (Result == EInboundApplyResult::ReconnectRequired)
        {
            ResetInboundForReconnect();
            break;
        }
        ++Drained;
    }
    Snapshot.InboundQueueDepth = InboundDepth.GetValue() + (DeferredInbound.IsSet() ? 1 : 0);
    Snapshot.DroppedInboundEvents = DroppedInbound.GetValue();
}

void UGahyeonRuntimeSubsystem::ResetInboundForReconnect()
{
    check(IsInGameThread());
    FScopeLock Lock(&InboundResetMutex);
    int64 Discarded = DeferredInbound.IsSet() ? 1 : 0;
    DeferredInbound.Reset();
    FGahyeonProtocolEnvelope Envelope;
    while (InboundQueue.Dequeue(Envelope)) ++Discarded;
    InboundDepth.Reset();
    Snapshot.InboundQueueDepth = 0;
    Snapshot.ReconnectDiscardedInboundEvents += Discarded;
}

UGahyeonRuntimeSubsystem::EInboundApplyResult
UGahyeonRuntimeSubsystem::ApplyInbound(const FGahyeonProtocolEnvelope& Envelope)
{
    if (Envelope.Type == TEXT("server.welcome"))
    {
        const TSharedPtr<FJsonObject> Payload = ParsePayload(Envelope.PayloadJson);
        double ResumeAfter = -1.0;
        if (RuntimeCore.IsValid() && Payload.IsValid()
            && Payload->TryGetNumberField(TEXT("resumeAfter"), ResumeAfter)
            && ResumeAfter >= 0.0
            && ResumeAfter <= MaximumSafeJsonInteger
            && ResumeAfter == FMath::FloorToDouble(ResumeAfter)
            && RuntimeCore->Cursor.Welcome(static_cast<int64>(ResumeAfter))
                == Gahyeon::ReplayCursorResult::Advanced
            && RuntimeCore->Convergence.Welcome(MonotonicMillis())
                == Gahyeon::ConnectionConvergenceResult::Accepted)
        {
            RuntimeCore->SessionId = Envelope.SessionId;
            Snapshot.bBackendConnected = true;
            SendNextPersistedEgress();
            ++Snapshot.AppliedInboundEvents;
            return EInboundApplyResult::Consumed;
        }
        if (ReconnectRequester)
        {
            ReconnectRequester();
        }
        return EInboundApplyResult::ReconnectRequired;
    }
    if (Envelope.Type == TEXT("character.action.acknowledged")
        && RuntimeCore.IsValid())
    {
        const TSharedPtr<FJsonObject> Payload = ParsePayload(Envelope.PayloadJson);
        FString ActionId;
        FString ResultValue;
        bool bTerminal = false;
        bool bAccepted = false;
        bool bDuplicate = false;
        if (Payload.IsValid()
            && Payload->TryGetStringField(TEXT("actionId"), ActionId)
            && Payload->TryGetStringField(TEXT("result"), ResultValue)
            && Payload->TryGetBoolField(TEXT("terminal"), bTerminal)
            && Payload->TryGetBoolField(TEXT("accepted"), bAccepted)
            && Payload->TryGetBoolField(TEXT("duplicate"), bDuplicate))
        {
            const Gahyeon::WorldActionAckResult AckResult =
                RuntimeCore->Egress.ActionAcknowledged({
                    .ActionId = ToUtf8(ActionId),
                    .Result = ToUtf8(ResultValue),
                    .Terminal = bTerminal,
                    .Accepted = bAccepted,
                    .Duplicate = bDuplicate});
            if (AckResult == Gahyeon::WorldActionAckResult::Acknowledged
                || AckResult == Gahyeon::WorldActionAckResult::Rejected)
            {
                PersistRuntimeState(0);
            }
        }
        ++Snapshot.AppliedInboundEvents;
        return EInboundApplyResult::Consumed;
    }
    if (Envelope.Type == TEXT("stream.cursor")
        && Envelope.Delivery == TEXT("durable")
        && RuntimeCore.IsValid())
    {
        Gahyeon::ReplayCursorResult Result = Gahyeon::ReplayCursorResult::Invalid;
        const TSharedPtr<FJsonObject> Payload = ParsePayload(Envelope.PayloadJson);
        double ScannedThrough = -1.0;
        if (Payload.IsValid()
            && Payload->TryGetNumberField(TEXT("scannedThrough"), ScannedThrough)
            && ScannedThrough >= 0.0
            && ScannedThrough <= MaximumSafeJsonInteger
            && ScannedThrough == FMath::FloorToDouble(ScannedThrough))
        {
            Result = RuntimeCore->Cursor.ObserveScanCursor(
                static_cast<int64>(ScannedThrough));
        }
        if (Result == Gahyeon::ReplayCursorResult::Advanced)
        {
            PersistRuntimeState(RuntimeCore->Cursor.SafeAcknowledgement());
            ++Snapshot.AppliedInboundEvents;
            return EInboundApplyResult::Consumed;
        }
        if (Result == Gahyeon::ReplayCursorResult::Duplicate)
        {
            ++Snapshot.AppliedInboundEvents;
            return EInboundApplyResult::Consumed;
        }
        if (ReconnectRequester)
        {
            ReconnectRequester();
        }
        return EInboundApplyResult::ReconnectRequired;
    }

    Gahyeon::ProtocolMessage Message;
    FString DecodeError;
    const EGahyeonPayloadDecodeStatus DecodeStatus =
        FGahyeonProtocolPayloadDecoder::Decode(Envelope, Message, DecodeError);
    if (DecodeStatus == EGahyeonPayloadDecodeStatus::Unsupported)
    {
        if (Envelope.Delivery == TEXT("durable")
            && !CompleteDurableEvent(Envelope.Sequence))
        {
            if (ReconnectRequester)
            {
                ReconnectRequester();
            }
            return EInboundApplyResult::ReconnectRequired;
        }
        ++Snapshot.AppliedInboundEvents;
        return EInboundApplyResult::Consumed;
    }
    if (DecodeStatus == EGahyeonPayloadDecodeStatus::Invalid || !RuntimeCore.IsValid())
    {
        if (Envelope.Delivery == TEXT("durable"))
        {
            if (ReconnectRequester)
            {
                ReconnectRequester();
            }
            return EInboundApplyResult::ReconnectRequired;
        }
        ++Snapshot.AppliedInboundEvents;
        return EInboundApplyResult::Consumed;
    }

    const int64 ApplyNowMs = MonotonicMillis();
    if (Message.Type == "world.snapshot")
    {
        const Gahyeon::ConnectionConvergenceState State =
            RuntimeCore->Convergence.State();
        if (State == Gahyeon::ConnectionConvergenceState::AwaitingSnapshot)
        {
            if (RuntimeCore->Convergence.SnapshotApplied(ApplyNowMs)
                != Gahyeon::ConnectionConvergenceResult::Accepted)
            {
                ++Snapshot.ReconnectConvergenceTimeoutCount;
                Snapshot.bBackendConnected = false;
                if (ReconnectRequester) ReconnectRequester();
                return EInboundApplyResult::ReconnectRequired;
            }
        }
        else if (State != Gahyeon::ConnectionConvergenceState::Converged)
        {
            Snapshot.bBackendConnected = false;
            if (ReconnectRequester) ReconnectRequester();
            return EInboundApplyResult::ReconnectRequired;
        }
    }
    const Gahyeon::Generation GenerationBefore =
        RuntimeCore->Character.Intents().CurrentGeneration();
    const Gahyeon::ProtocolApplyResult Applied =
        RuntimeCore->Events.Apply(Message, ApplyNowMs);
    if (Applied.InterruptedUtteranceId.has_value())
    {
        OnAudioInterruptRequested.Broadcast(
            UTF8_TO_TCHAR(Applied.InterruptedUtteranceId->c_str()));
    }
    if (Applied.ActionCompletion.has_value()
        && !QueueRuntimeActionCompletion(*Applied.ActionCompletion))
    {
        return EInboundApplyResult::Backpressured;
    }
    if (Envelope.Delivery != TEXT("durable")
        && RuntimeCore->Character.Intents().CurrentGeneration() > GenerationBefore)
    {
        PersistRuntimeState(0);
    }
    if (Applied.Status == Gahyeon::ProtocolApplyStatus::Backpressured)
    {
        return EInboundApplyResult::Backpressured;
    }
    if (Applied.Status == Gahyeon::ProtocolApplyStatus::Invalid)
    {
        if (Envelope.Delivery == TEXT("durable"))
        {
            if (ReconnectRequester)
            {
                ReconnectRequester();
            }
            return EInboundApplyResult::ReconnectRequired;
        }
        ++Snapshot.AppliedInboundEvents;
        return EInboundApplyResult::Consumed;
    }
    if (Envelope.Delivery == TEXT("durable")
        && !CompleteDurableEvent(Envelope.Sequence))
    {
        if (ReconnectRequester)
        {
            ReconnectRequester();
        }
        return EInboundApplyResult::ReconnectRequired;
    }
    ++Snapshot.AppliedInboundEvents;
    return EInboundApplyResult::Consumed;
}

bool UGahyeonRuntimeSubsystem::CompleteDurableEvent(int64 Sequence)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid())
    {
        return false;
    }
    const Gahyeon::ReplayCursorResult Result =
        RuntimeCore->Cursor.CompleteDurable(Sequence);
    if (Result == Gahyeon::ReplayCursorResult::Advanced)
    {
        PersistRuntimeState(RuntimeCore->Cursor.SafeAcknowledgement());
        return true;
    }
    return Result == Gahyeon::ReplayCursorResult::Duplicate;
}

void UGahyeonRuntimeSubsystem::PersistRuntimeState(
    int64 SequenceToConfirm,
    const FString& ActionToConfirm)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || SequenceToConfirm < 0)
    {
        return;
    }
    UGahyeonRuntimePersistenceSubsystem* Persistence =
        GetGameInstance()->GetSubsystem<UGahyeonRuntimePersistenceSubsystem>();
    if (Persistence == nullptr)
    {
        return;
    }

    const Gahyeon::ClientRuntimeSaveState Captured =
        Gahyeon::ClientRuntimeSaveStateCodec::Capture(
            RuntimeCore->Cursor,
            RuntimeCore->Actions,
            RuntimeCore->Character.Intents().CurrentGeneration(),
            MonotonicMillis());
    UGahyeonRuntimeSaveGame* Save = NewObject<UGahyeonRuntimeSaveGame>(this);
    FGahyeonRuntimeSaveMapper::ToSaveGame(Captured, *Save);
    const uint64 Generation = PersistenceGeneration;
    Persistence->SaveAsync(Save,
        [WeakThis = TWeakObjectPtr<UGahyeonRuntimeSubsystem>(this),
         Generation,
         SequenceToConfirm,
         ActionToConfirm](bool bSuccess)
        {
            if (!bSuccess || !WeakThis.IsValid()
                || WeakThis->PersistenceGeneration != Generation
                || !WeakThis->RuntimeCore.IsValid())
            {
                return;
            }
            bool bConfirmed = SequenceToConfirm == 0;
            if (SequenceToConfirm > 0)
            {
                bConfirmed = WeakThis->RuntimeCore->Egress.PersistenceConfirmed(
                    SequenceToConfirm);
            }
            if (!ActionToConfirm.IsEmpty())
            {
                bConfirmed = WeakThis->RuntimeCore->Egress.ActionPersistenceConfirmed(
                    ToUtf8(ActionToConfirm)) && bConfirmed;
            }
            if (bConfirmed)
            {
                WeakThis->SendNextPersistedEgress();
            }
        });
}

void UGahyeonRuntimeSubsystem::SendNextPersistedEgress()
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid() || !RuntimeCore->Cursor.IsWelcomed() || !OutboundSender)
    {
        return;
    }
    const int64 NowMs = MonotonicMillis();
    const std::optional<Gahyeon::OutboundProtocolCommand> Command =
        RuntimeCore->Egress.Next(NowMs);
    if (!Command.has_value())
    {
        return;
    }
    const FString Json = SerializeOutbound(*Command, RuntimeCore->SessionId);
    if (!Json.IsEmpty() && OutboundSender(Json))
    {
        RuntimeCore->Egress.MarkSent(*Command, NowMs);
    }
}

void UGahyeonRuntimeSubsystem::RefreshPresentationSnapshot(int64 NowMs)
{
    check(IsInGameThread());
    if (!RuntimeCore.IsValid())
    {
        return;
    }

    Snapshot.CurrentGeneration = static_cast<int64>(
        RuntimeCore->Character.Intents().CurrentGeneration());
    Snapshot.RuntimeEpoch = static_cast<int64>(RuntimeEpoch);
    Snapshot.VadToListeningLatency = ToUnrealLatency(
        RuntimeCore->Trace.Summary(Gahyeon::LatencyMetric::VadToListening));
    Snapshot.BargeInToAudioStopLatency = ToUnrealLatency(
        RuntimeCore->Trace.Summary(Gahyeon::LatencyMetric::BargeInToAudioStop));
    Snapshot.ReconnectToSnapshotLatency = ToUnrealLatency(
        RuntimeCore->Trace.Summary(Gahyeon::LatencyMetric::ReconnectToSnapshot));
    Snapshot.VisemeOnsetLatency = ToUnrealLatency(
        RuntimeCore->Trace.Summary(Gahyeon::LatencyMetric::VisemeOnsetOffset));
    Snapshot.TranscriptToThinkingLatency = ToUnrealLatency(
        RuntimeCore->Trace.Summary(Gahyeon::LatencyMetric::TranscriptToThinking));
    Snapshot.FirstAudioPlayableLatency = ToUnrealLatency(
        RuntimeCore->Trace.Summary(Gahyeon::LatencyMetric::FirstAudioPlayable));
    Snapshot.VoiceEndToFinalTranscriptLatency = ToUnrealLatency(
        RuntimeCore->Trace.Summary(
            Gahyeon::LatencyMetric::VoiceEndToFinalTranscript));

    const std::optional<Gahyeon::WorldStateSnapshot>& World = RuntimeCore->World.Current();
    Snapshot.bHasWorldState = World.has_value();
    if (World.has_value())
    {
        Snapshot.WorldRevision = static_cast<int64>(World->Revision);
        Snapshot.CurrentRoom = FromUtf8(World->CurrentRoom);
        Snapshot.WorldPosition =
            FGahyeonWorldCoordinateAdapter::ToUnrealCentimeters(World->Position);
        Snapshot.Activity = FromUtf8(World->Activity);
    }
    else
    {
        Snapshot.WorldRevision = 0;
        Snapshot.CurrentRoom.Reset();
        Snapshot.WorldPosition = FVector::ZeroVector;
        Snapshot.Activity.Reset();
    }

    const std::optional<Gahyeon::ActiveWorldAction>& Action =
        RuntimeCore->WorldActions.Active();
    Snapshot.bWorldActionActive = Action.has_value();
    Snapshot.ActiveWorldActionId = Action.has_value()
        ? FromUtf8(Action->Target.ActionId)
        : FString{};
    Snapshot.WorldActionPhase = Action.has_value()
        ? (Action->Phase == Gahyeon::WorldActionPhase::Navigating
            ? TEXT("navigating") : TEXT("interacting"))
        : FString{};
    Snapshot.WorldActionExpectedRevision = Action.has_value()
        ? static_cast<int64>(Action->Target.ExpectedRevision)
        : 0;
    Snapshot.WorldActionTargetRoom = Action.has_value()
        ? FromUtf8(Action->Target.Room)
        : FString{};
    Snapshot.WorldActionTargetPosition = Action.has_value()
        ? FGahyeonWorldCoordinateAdapter::ToUnrealCentimeters(Action->Target.Position)
        : FVector::ZeroVector;
    Snapshot.WorldActionTargetActivity = Action.has_value()
        ? FromUtf8(Action->Target.Activity)
        : FString{};
    Snapshot.WorldActionInteractionTarget = Action.has_value()
        && Action->Target.InteractionTarget.has_value()
        ? FromUtf8(*Action->Target.InteractionTarget)
        : FString{};

    const Gahyeon::ResolvedIntents Intents = RuntimeCore->Character.Intents().Resolve(NowMs);
    Snapshot.ConversationPhase = TEXT("idle");
    if (const Gahyeon::CharacterIntent* Phase =
        Intents.Find(Gahyeon::IntentChannel::Phase))
    {
        Snapshot.ConversationPhase = FromUtf8(Phase->Value);
    }
    Snapshot.Posture = TEXT("ambient_alive");
    if (const Gahyeon::CharacterIntent* Posture =
        Intents.Find(Gahyeon::IntentChannel::Posture))
    {
        Snapshot.Posture = FromUtf8(Posture->Value);
    }
    Snapshot.AttentionTargetSemantic.Reset();
    if (const Gahyeon::CharacterIntent* AttentionIntent =
        Intents.Find(Gahyeon::IntentChannel::Attention))
    {
        Snapshot.AttentionTargetSemantic = FromUtf8(AttentionIntent->Value);
    }
    Snapshot.ExpressionSemantic.Reset();
    if (const Gahyeon::CharacterIntent* Expression =
        Intents.Find(Gahyeon::IntentChannel::Expression))
    {
        Snapshot.ExpressionSemantic = FromUtf8(Expression->Value);
    }

    const Gahyeon::EmotionSample Emotion = RuntimeCore->Emotion.Sample(NowMs);
    Snapshot.DominantEmotion.Reset();
    Snapshot.DominantEmotionIntensity = 0.0;
    Snapshot.EmotionDimensions.Reset();
    Snapshot.EmotionValence = Emotion.Valence;
    Snapshot.EmotionArousal = Emotion.Arousal;
    Snapshot.EmotionDominance = Emotion.Dominance;
    Snapshot.bEmotionReleasing = Emotion.Releasing;
    for (const auto& [Name, Intensity] : Emotion.Dimensions)
    {
        Snapshot.EmotionDimensions.Add(FName(UTF8_TO_TCHAR(Name.c_str())), Intensity);
        if (Intensity > Snapshot.DominantEmotionIntensity)
        {
            Snapshot.DominantEmotion = FromUtf8(Name);
            Snapshot.DominantEmotionIntensity = Intensity;
        }
    }
    if (Snapshot.DominantEmotion.IsEmpty() && World.has_value())
    {
        Snapshot.DominantEmotion = FromUtf8(World->Emotion);
        Snapshot.DominantEmotionIntensity = World->EmotionIntensity;
        Snapshot.EmotionDimensions.Add(
            FName(*Snapshot.DominantEmotion), Snapshot.DominantEmotionIntensity);
    }

    const Gahyeon::AmbientMotionSample Ambient = RuntimeCore->Ambient.Sample(NowMs);
    Snapshot.Breath = Ambient.Breath;
    Snapshot.Blink = Ambient.Blink;
    Snapshot.AmbientEyeYaw = Ambient.EyeYaw;
    Snapshot.AmbientEyePitch = Ambient.EyePitch;
    Snapshot.MicroHeadYaw = Ambient.MicroHeadYaw;
    Snapshot.MicroHeadPitch = Ambient.MicroHeadPitch;
    Snapshot.WeightShift = Ambient.WeightShift;

    const Gahyeon::AttentionSample Attention = RuntimeCore->Attention.Sample(NowMs);
    Snapshot.AttentionEyeYaw = Attention.EyeYaw;
    Snapshot.AttentionEyePitch = Attention.EyePitch;
    Snapshot.AttentionHeadYaw = Attention.HeadYaw;
    Snapshot.AttentionHeadPitch = Attention.HeadPitch;
    Snapshot.AttentionTrackingWeight = Attention.TrackingWeight;

    RuntimeCore->Gestures.Advance(NowMs);
    const std::optional<Gahyeon::ActiveGesture>& Gesture = RuntimeCore->Gestures.Active();
    Snapshot.bGestureActive = Gesture.has_value();
    Snapshot.GestureSemantic = Gesture.has_value() ? FromUtf8(Gesture->Semantic) : FString{};
    Snapshot.GestureVariantId = Gesture.has_value() ? FromUtf8(Gesture->VariantId) : FString{};
    Snapshot.GestureIntensity = Gesture.has_value() ? Gesture->Intensity : 0.0;

    const Gahyeon::LipSyncSample LipSync = RuntimeCore->LipSync.Sample(
        RuntimeCore->PlaybackPositionMs, RuntimeCore->PlaybackAmplitude);
    Snapshot.bLipSyncActive = LipSync.Active;
    Snapshot.bLipSyncUsingTimeline = LipSync.UsingTimeline;
    Snapshot.JawOpen = LipSync.JawOpen;
    Snapshot.PrimaryViseme = FromUtf8(LipSync.PrimaryViseme);
    Snapshot.PrimaryVisemeWeight = LipSync.PrimaryWeight;
    Snapshot.SecondaryViseme = FromUtf8(LipSync.SecondaryViseme);
    Snapshot.SecondaryVisemeWeight = LipSync.SecondaryWeight;
}

void UGahyeonRuntimeSubsystem::AdvanceReflex(double NowSeconds)
{
    (void)NowSeconds;
    ++Snapshot.ReflexUpdates;
}

void UGahyeonRuntimeSubsystem::AdvanceBehavior(double NowSeconds)
{
    (void)NowSeconds;
    ++Snapshot.BehaviorUpdates;
    if (!RuntimeCore.IsValid()) return;
    const int64 NowMs = MonotonicMillis();
    if (RuntimeCore->Convergence.Advance(NowMs))
    {
        ++Snapshot.ReconnectConvergenceTimeoutCount;
        SetBackendConnected(false);
        if (ReconnectRequester) ReconnectRequester();
    }
    const std::optional<Gahyeon::CognitionTimeoutResult> Timeout =
        RuntimeCore->Voice.Tick(NowMs);
    if (Timeout.has_value())
    {
        ++Snapshot.CognitionTimeoutCount;
        Snapshot.LastCognitionTimeoutGeneration = Timeout->GenerationId;
        RuntimeCore->BatchSttStartedAtMs.reset();
        RuntimeCore->BatchSttGeneration.reset();
        if (RuntimeCore->TranscriptThinkingSpan.has_value())
        {
            RuntimeCore->Trace.Cancel(*RuntimeCore->TranscriptThinkingSpan);
            RuntimeCore->TranscriptThinkingSpan.reset();
            RuntimeCore->TranscriptThinkingGeneration.reset();
        }
        if (RuntimeCore->FirstAudioSpan.has_value())
        {
            RuntimeCore->Trace.Cancel(*RuntimeCore->FirstAudioSpan);
            RuntimeCore->FirstAudioSpan.reset();
        }
        RuntimeCore->Gestures.SetGeneration(Timeout->GenerationId);
        const FVector Position = Snapshot.WorldPosition;
        const std::optional<Gahyeon::WorldActionCompletion> CancelledAction =
            RuntimeCore->WorldActions.SetGeneration(
                Timeout->GenerationId,
                NowMs,
                FGahyeonWorldCoordinateAdapter::ToCoreMeters(Position));
        if (CancelledAction.has_value()) QueueRuntimeActionCompletion(*CancelledAction);
        if (Timeout->InterruptedUtteranceId.has_value())
        {
            OnAudioInterruptRequested.Broadcast(
                FromUtf8(*Timeout->InterruptedUtteranceId));
        }
        PersistRuntimeState(0);
        SendGenerationAdvance(Timeout->GenerationId, TEXT("cognition_timeout"));
    }
    const FVector CurrentPosition = Snapshot.bHasWorldState
        ? Snapshot.WorldPosition
        : FVector::ZeroVector;
    const std::optional<Gahyeon::WorldActionCompletion> Completion =
        RuntimeCore->WorldActions.Advance(
            NowMs,
            FGahyeonWorldCoordinateAdapter::ToCoreMeters(CurrentPosition));
    if (Completion.has_value()) QueueRuntimeActionCompletion(*Completion);
}
