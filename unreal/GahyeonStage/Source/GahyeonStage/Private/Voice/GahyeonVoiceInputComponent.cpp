#include "Voice/GahyeonVoiceInputComponent.h"

#include "AudioCaptureCore.h"
#include "Containers/Queue.h"
#include "Dom/JsonObject.h"
#include "Engine/GameInstance.h"
#include "Engine/World.h"
#include "HttpModule.h"
#include "HAL/PlatformTime.h"
#include "HAL/ThreadSafeBool.h"
#include "HAL/ThreadSafeCounter.h"
#include "Interfaces/IHttpResponse.h"
#include "Network/GahyeonTransportSubsystem.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Voice/GahyeonBatchSttAudioSink.h"
#include "Voice/GahyeonStreamingSttAudioSink.h"
#include "Voice/GahyeonStreamingSttWebSocketClient.h"

namespace
{
enum class EVoiceObservationType : uint8
{
    AudioLevel,
    PartialTranscript,
    FinalTranscript
};

struct FVoiceObservation
{
    EVoiceObservationType Type = EVoiceObservationType::AudioLevel;
    double LevelOrStability = 0.0;
    int64 ObservedAtMs = 0;
    int64 GenerationId = -1;
    FString Text;
    FString Language;
};
}

struct FGahyeonVoiceInputState
{
    static constexpr int32 MaximumPendingObservations = 512;

    bool Enqueue(FVoiceObservation Observation)
    {
        if (!bAccepting) return false;
        const int32 Depth = PendingCount.Increment();
        if (Depth > MaximumPendingObservations)
        {
            PendingCount.Decrement();
            DroppedCount.Increment();
            return false;
        }
        Observations.Enqueue(MoveTemp(Observation));
        return true;
    }

    bool Dequeue(FVoiceObservation& OutObservation)
    {
        if (!Observations.Dequeue(OutObservation)) return false;
        PendingCount.Decrement();
        return true;
    }

    void ResetQueue()
    {
        FVoiceObservation Discarded;
        while (Dequeue(Discarded))
        {
        }
    }

    /** Capture has stopped, so queued RMS frames must not open a new VAD generation later. */
    void DiscardAudioLevelObservations()
    {
        TArray<FVoiceObservation> Retained;
        FVoiceObservation Observation;
        while (Dequeue(Observation))
        {
            if (Observation.Type != EVoiceObservationType::AudioLevel)
            {
                Retained.Add(MoveTemp(Observation));
            }
        }
        for (FVoiceObservation& Item : Retained)
        {
            PendingCount.Increment();
            Observations.Enqueue(MoveTemp(Item));
        }
    }

    TQueue<FVoiceObservation, EQueueMode::Mpsc> Observations;
    FThreadSafeCounter PendingCount;
    FThreadSafeCounter DroppedCount;
    FThreadSafeCounter OverflowCount;
    FThreadSafeCounter SttBackpressureCount;
    FThreadSafeCounter SttLifecycleBackpressureCount;
    FThreadSafeBool bAccepting = true;
};

struct FGahyeonVoiceHttpState
{
    static constexpr int32 MaximumConcurrentRequests = 2;
    TArray<FHttpRequestPtr> ActiveRequests;
    int32 FailureCount = 0;
    int32 StaleResultCount = 0;
};

UGahyeonVoiceInputComponent::UGahyeonVoiceInputComponent()
{
    PrimaryComponentTick.bCanEverTick = true;
    PrimaryComponentTick.bStartWithTickEnabled = true;
    PrimaryComponentTick.TickGroup = TG_PrePhysics;
    InputState = MakeShared<FGahyeonVoiceInputState, ESPMode::ThreadSafe>();
    BatchSttAudioSink = MakeShared<FGahyeonBatchSttAudioSink, ESPMode::ThreadSafe>();
    StreamingSttAudioSink = BatchSttAudioSink;
    VoiceHttpState = MakeUnique<FGahyeonVoiceHttpState>();
}

UGahyeonVoiceInputComponent::~UGahyeonVoiceInputComponent() = default;

void UGahyeonVoiceInputComponent::BeginPlay()
{
    Super::BeginPlay();
    RefreshRuntime();
    ConfigureStreamingStt();
    if (bStartCaptureOnBeginPlay) StartMicrophoneCapture();
}

void UGahyeonVoiceInputComponent::EndPlay(const EEndPlayReason::Type EndPlayReason)
{
    StopMicrophoneCapture();
    if (StreamingSttClient.IsValid()) StreamingSttClient->Disconnect();
    StreamingSttClient.Reset();
    if (VoiceHttpState.IsValid())
    {
        TArray<FHttpRequestPtr> Requests = MoveTemp(VoiceHttpState->ActiveRequests);
        VoiceHttpState->ActiveRequests.Reset();
        for (const FHttpRequestPtr& Request : Requests)
        {
            if (Request.IsValid()) Request->CancelRequest();
        }
    }
    StreamingSttAudioSink.Reset();
    NetworkSttAudioSink.Reset();
    BatchSttAudioSink.Reset();
    Runtime = nullptr;
    if (InputState.IsValid()) InputState->ResetQueue();
    InputState.Reset();
    Super::EndPlay(EndPlayReason);
}

void UGahyeonVoiceInputComponent::TickComponent(
    float DeltaTime,
    ELevelTick TickType,
    FActorComponentTickFunction* ThisTickFunction)
{
    Super::TickComponent(DeltaTime, TickType, ThisTickFunction);
    if (Runtime == nullptr) RefreshRuntime();
    if (Runtime != nullptr && Runtime->GetRuntimeEpoch() != ObservedRuntimeEpoch)
    {
        InvalidateWorkFromPreviousRuntime();
        ObservedRuntimeEpoch = Runtime->GetRuntimeEpoch();
    }

    FVoiceObservation Observation;
    int32 Drained = 0;
    while (InputState.IsValid() && Drained < MaximumObservationsPerFrame
        && InputState->Dequeue(Observation))
    {
        ++Drained;
        if (Runtime == nullptr) continue;
        switch (Observation.Type)
        {
        case EVoiceObservationType::AudioLevel:
        {
            const FGahyeonVoiceActivityObservation Result =
                Runtime->ObserveMicrophoneLevelAtDetailed(
                Observation.LevelOrStability, Observation.ObservedAtMs);
            if (StreamingSttAudioSink.IsValid()
                && Result.Edge == EGahyeonVoiceActivityEdge::Started)
            {
                if (!StreamingSttAudioSink->VoiceActivityStarted(
                    Result.GenerationId, Observation.ObservedAtMs)
                    && InputState.IsValid())
                {
                    InputState->SttLifecycleBackpressureCount.Increment();
                    Runtime->NotifyBatchSttFailed(Result.GenerationId);
                }
            }
            else if (StreamingSttAudioSink.IsValid()
                && Result.Edge == EGahyeonVoiceActivityEdge::Ended)
            {
                if (!StreamingSttAudioSink->VoiceActivityEnded(
                    Result.GenerationId, Observation.ObservedAtMs)
                    && InputState.IsValid())
                {
                    InputState->SttLifecycleBackpressureCount.Increment();
                    Runtime->NotifyBatchSttFailed(Result.GenerationId);
                }
            }
            break;
        }
        case EVoiceObservationType::PartialTranscript:
            if (Observation.GenerationId >= 0)
            {
                Runtime->SubmitPartialTranscriptForGeneration(
                    Observation.Text, Observation.LevelOrStability, Observation.GenerationId);
            }
            else
            {
                Runtime->SubmitPartialTranscript(
                    Observation.Text, Observation.LevelOrStability);
            }
            break;
        case EVoiceObservationType::FinalTranscript:
            if (Observation.GenerationId >= 0)
            {
                if (Runtime->GetSnapshot().CurrentGeneration != Observation.GenerationId)
                {
                    if (VoiceHttpState.IsValid()) ++VoiceHttpState->StaleResultCount;
                }
                else if (!Runtime->SubmitFinalTranscriptForGeneration(
                    Observation.Text, Observation.Language, Observation.GenerationId)
                    && VoiceHttpState.IsValid())
                {
                    ++VoiceHttpState->FailureCount;
                }
            }
            else
            {
                Runtime->SubmitFinalTranscript(Observation.Text, Observation.Language);
            }
            break;
        }
    }
    if (NetworkSttAudioSink.IsValid()) NetworkSttAudioSink->TickGameThread();
    if (StreamingSttClient.IsValid()) StreamingSttClient->TickGameThread();
    DrainStreamingStt();
    DrainCompletedBatchStt();
}

bool UGahyeonVoiceInputComponent::StartMicrophoneCapture()
{
    check(IsInGameThread());
    ConfigureStreamingStt();
    LastCaptureError.Reset();
    const UWorld* World = GetWorld();
    if (World == nullptr || World->GetNetMode() == NM_DedicatedServer)
    {
        LastCaptureError = TEXT("capture_requires_client_world");
        return false;
    }
    if (!InputState.IsValid())
    {
        InputState = MakeShared<FGahyeonVoiceInputState, ESPMode::ThreadSafe>();
    }
    if (AudioCapture.IsValid() && AudioCapture->IsCapturing()) return true;
    StopMicrophoneCapture();
    InputState->bAccepting = true;
    AudioCapture = MakeUnique<Audio::FAudioCapture>();
    Audio::FAudioCaptureDeviceParams Params;
    const TSharedPtr<FGahyeonVoiceInputState, ESPMode::ThreadSafe> CallbackState = InputState;
    const TSharedPtr<IGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe> CallbackSttSink =
        StreamingSttAudioSink;
    const bool bOpened = AudioCapture->OpenCaptureStream(
        Params,
        [CallbackState, CallbackSttSink](
            const float* InAudio,
            int32 NumFrames,
            int32 NumChannels,
            int32 SampleRate,
            double StreamTime,
            bool bOverflow)
        {
            (void)StreamTime;
            if (!CallbackState.IsValid() || !CallbackState->bAccepting
                || InAudio == nullptr || NumFrames <= 0 || NumChannels <= 0)
            {
                return;
            }
            if (bOverflow) CallbackState->OverflowCount.Increment();
            const int64 ObservedAtMs =
                static_cast<int64>(FPlatformTime::Seconds() * 1000.0);
            if (CallbackSttSink.IsValid() && !CallbackSttSink->TryEnqueuePcm(
                InAudio, NumFrames, NumChannels, SampleRate, ObservedAtMs))
            {
                CallbackState->SttBackpressureCount.Increment();
            }
            const int64 SampleCount = static_cast<int64>(NumFrames) * NumChannels;
            double SumSquares = 0.0;
            for (int64 Index = 0; Index < SampleCount; ++Index)
            {
                const double Sample = FMath::IsFinite(InAudio[Index])
                    ? static_cast<double>(InAudio[Index])
                    : 0.0;
                SumSquares += Sample * Sample;
            }
            const double Rms = FMath::Sqrt(SumSquares / static_cast<double>(SampleCount));
            CallbackState->Enqueue({
                .Type = EVoiceObservationType::AudioLevel,
                .LevelOrStability = FMath::Clamp(Rms, 0.0, 1.0),
                .ObservedAtMs = ObservedAtMs});
        },
        static_cast<uint32>(FMath::Clamp(DesiredCaptureFrames, 64, 4096)));
    if (!bOpened || !AudioCapture->StartStream())
    {
        LastCaptureError = bOpened
            ? TEXT("capture_start_failed")
            : TEXT("capture_open_failed");
        if (AudioCapture->IsStreamOpen()) AudioCapture->CloseStream();
        AudioCapture.Reset();
        InputState->bAccepting = false;
        return false;
    }
    return true;
}

void UGahyeonVoiceInputComponent::StopMicrophoneCapture()
{
    check(IsInGameThread());
    const bool bWasCapturing = IsMicrophoneCapturing();
    if (InputState.IsValid()) InputState->bAccepting = false;
    if (AudioCapture.IsValid())
    {
        if (AudioCapture->IsCapturing()) AudioCapture->StopStream();
        if (AudioCapture->IsStreamOpen()) AudioCapture->CloseStream();
        AudioCapture.Reset();
    }
    if (!bWasCapturing || Runtime == nullptr) return;

    // Save the truncated utterance identity before the local cancellation advances it.
    int64 Generation = -1;
    Runtime->AbortMicrophoneCapture(Generation);
    if (InputState.IsValid()) InputState->DiscardAudioLevelObservations();
    if (StreamingSttAudioSink.IsValid())
    {
        StreamingSttAudioSink->VoiceActivityCancelled(
            Generation,
            static_cast<int64>(FPlatformTime::Seconds() * 1000.0));
    }
}

bool UGahyeonVoiceInputComponent::IsMicrophoneCapturing() const
{
    return AudioCapture.IsValid() && AudioCapture->IsCapturing();
}

bool UGahyeonVoiceInputComponent::SetStreamingSttAudioSink(
    TSharedPtr<IGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe> Sink)
{
    check(IsInGameThread());
    if (IsMicrophoneCapturing()) return false;
    StreamingSttAudioSink = MoveTemp(Sink);
    return true;
}

bool UGahyeonVoiceInputComponent::EnqueueAudioLevelFromAnyThread(
    double NormalizedLevel,
    int64 ObservedAtMs)
{
    if (!FMath::IsFinite(NormalizedLevel)) return false;
    const int64 Timestamp = ObservedAtMs >= 0
        ? ObservedAtMs
        : static_cast<int64>(FPlatformTime::Seconds() * 1000.0);
    return InputState.IsValid() && InputState->Enqueue({
        .Type = EVoiceObservationType::AudioLevel,
        .LevelOrStability = FMath::Clamp(NormalizedLevel, 0.0, 1.0),
        .ObservedAtMs = Timestamp});
}

bool UGahyeonVoiceInputComponent::EnqueuePartialTranscriptFromAnyThread(
    const FString& Text,
    double Stability)
{
    const FString CleanText = Text.TrimStartAndEnd();
    if (CleanText.IsEmpty() || !FMath::IsFinite(Stability)) return false;
    return InputState.IsValid() && InputState->Enqueue({
        .Type = EVoiceObservationType::PartialTranscript,
        .LevelOrStability = FMath::Clamp(Stability, 0.0, 1.0),
        .Text = CleanText});
}

bool UGahyeonVoiceInputComponent::EnqueuePartialTranscriptForGenerationFromAnyThread(
    const FString& Text,
    double Stability,
    int64 GenerationId)
{
    const FString CleanText = Text.TrimStartAndEnd();
    if (CleanText.IsEmpty() || !FMath::IsFinite(Stability) || GenerationId < 0) return false;
    return InputState.IsValid() && InputState->Enqueue({
        .Type = EVoiceObservationType::PartialTranscript,
        .LevelOrStability = FMath::Clamp(Stability, 0.0, 1.0),
        .GenerationId = GenerationId,
        .Text = CleanText});
}

bool UGahyeonVoiceInputComponent::EnqueueFinalTranscriptFromAnyThread(
    const FString& Text,
    const FString& Language)
{
    const FString CleanText = Text.TrimStartAndEnd();
    const FString CleanLanguage = Language.TrimStartAndEnd();
    if (CleanText.IsEmpty() || CleanLanguage.IsEmpty()) return false;
    return InputState.IsValid() && InputState->Enqueue({
        .Type = EVoiceObservationType::FinalTranscript,
        .Text = CleanText,
        .Language = CleanLanguage});
}

bool UGahyeonVoiceInputComponent::EnqueueFinalTranscriptForGenerationFromAnyThread(
    const FString& Text,
    const FString& Language,
    int64 GenerationId)
{
    const FString CleanText = Text.TrimStartAndEnd();
    const FString CleanLanguage = Language.TrimStartAndEnd();
    if (CleanText.IsEmpty() || CleanLanguage.IsEmpty() || GenerationId < 0) return false;
    return InputState.IsValid() && InputState->Enqueue({
        .Type = EVoiceObservationType::FinalTranscript,
        .GenerationId = GenerationId,
        .Text = CleanText,
        .Language = CleanLanguage});
}

int32 UGahyeonVoiceInputComponent::GetPendingObservationCount() const
{
    return InputState.IsValid() ? InputState->PendingCount.GetValue() : 0;
}

int32 UGahyeonVoiceInputComponent::GetDroppedObservationCount() const
{
    return InputState.IsValid() ? InputState->DroppedCount.GetValue() : 0;
}

int32 UGahyeonVoiceInputComponent::GetCaptureOverflowCount() const
{
    return InputState.IsValid() ? InputState->OverflowCount.GetValue() : 0;
}

int32 UGahyeonVoiceInputComponent::GetSttBackpressureCount() const
{
    return InputState.IsValid() ? InputState->SttBackpressureCount.GetValue() : 0;
}

int32 UGahyeonVoiceInputComponent::GetSttLifecycleBackpressureCount() const
{
    return InputState.IsValid()
        ? InputState->SttLifecycleBackpressureCount.GetValue() : 0;
}

int32 UGahyeonVoiceInputComponent::GetBatchSttFailureCount() const
{
    return VoiceHttpState.IsValid() ? VoiceHttpState->FailureCount : 0;
}

int32 UGahyeonVoiceInputComponent::GetStaleSttResultCount() const
{
    return VoiceHttpState.IsValid() ? VoiceHttpState->StaleResultCount : 0;
}

int32 UGahyeonVoiceInputComponent::GetBatchPcmDropCount() const
{
    return BatchSttAudioSink.IsValid() ? BatchSttAudioSink->GetDroppedPcmChunkCount() : 0;
}

int32 UGahyeonVoiceInputComponent::GetBatchLifecycleDropCount() const
{
    return BatchSttAudioSink.IsValid()
        ? BatchSttAudioSink->GetDroppedLifecycleEventCount() : 0;
}

int32 UGahyeonVoiceInputComponent::GetBatchUtteranceDropCount() const
{
    return BatchSttAudioSink.IsValid()
        ? BatchSttAudioSink->GetDroppedCompletedUtteranceCount() : 0;
}

void UGahyeonVoiceInputComponent::DrainCompletedBatchStt()
{
    check(IsInGameThread());
    if (!StreamingSttAudioSink.IsValid()) return;
    auto TakeFailed = [this]() -> std::optional<int64>
    {
        if (NetworkSttAudioSink.IsValid()
            && StreamingSttAudioSink.Get() == NetworkSttAudioSink.Get())
        {
            return NetworkSttAudioSink->TakeFailedBatchGeneration();
        }
        return BatchSttAudioSink.IsValid()
            ? BatchSttAudioSink->TakeFailedGeneration() : std::nullopt;
    };
    auto TakeCompleted = [this]() -> std::optional<Gahyeon::EncodedPcmUtterance>
    {
        if (NetworkSttAudioSink.IsValid()
            && StreamingSttAudioSink.Get() == NetworkSttAudioSink.Get())
        {
            return NetworkSttAudioSink->TakeCompletedBatch();
        }
        return BatchSttAudioSink.IsValid()
            ? BatchSttAudioSink->TakeCompleted() : std::nullopt;
    };
    while (const std::optional<int64> FailedGeneration = TakeFailed())
    {
        if (VoiceHttpState.IsValid()) ++VoiceHttpState->FailureCount;
        if (Runtime != nullptr) Runtime->NotifyBatchSttFailed(*FailedGeneration);
    }
    while (std::optional<Gahyeon::EncodedPcmUtterance> Utterance = TakeCompleted())
    {
        const int64 Generation = Utterance->GenerationId;
        if (!SubmitBatchStt(MoveTemp(*Utterance)) && VoiceHttpState.IsValid())
        {
            ++VoiceHttpState->FailureCount;
            if (Runtime != nullptr) Runtime->NotifyBatchSttFailed(Generation);
        }
    }
}

void UGahyeonVoiceInputComponent::ConfigureStreamingStt()
{
    check(IsInGameThread());
    if (!bEnableStreamingStt || StreamingSttClient.IsValid() || IsMicrophoneCapturing()) return;
    UGameInstance* GameInstance = GetWorld() != nullptr
        ? GetWorld()->GetGameInstance() : nullptr;
    UGahyeonTransportSubsystem* Transport = GameInstance != nullptr
        ? GameInstance->GetSubsystem<UGahyeonTransportSubsystem>() : nullptr;
    if (Transport == nullptr) return;
    const FString Endpoint = Transport->GetStreamingSttEndpoint();
    const FString SessionId = Transport->GetSessionId();
    if (Endpoint.IsEmpty() || SessionId.IsEmpty()) return;
    NetworkSttAudioSink =
        MakeShared<FGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe>();
    const TWeakObjectPtr<UGahyeonVoiceInputComponent> WeakThis(this);
    StreamingSttClient = MakeShared<FGahyeonStreamingSttWebSocketClient, ESPMode::ThreadSafe>(
        Endpoint,
        SessionId,
        Transport->GetBearerToken(),
        NetworkSttAudioSink,
        [WeakThis](int64 Generation, const FString& Text, double Stability)
        {
            if (WeakThis.IsValid())
            {
                WeakThis->EnqueuePartialTranscriptForGenerationFromAnyThread(
                    Text, Stability, Generation);
            }
        },
        [WeakThis](int64 Generation, const FString& Text, const FString& Language)
        {
            if (WeakThis.IsValid())
            {
                WeakThis->EnqueueFinalTranscriptForGenerationFromAnyThread(
                    Text, Language, Generation);
            }
        });
    StreamingSttAudioSink = NetworkSttAudioSink;
    if (!StreamingSttClient->Connect())
    {
        StreamingSttClient.Reset();
        NetworkSttAudioSink.Reset();
        StreamingSttAudioSink = BatchSttAudioSink;
    }
}

void UGahyeonVoiceInputComponent::DrainStreamingStt()
{
    check(IsInGameThread());
    if (!NetworkSttAudioSink.IsValid()
        || StreamingSttAudioSink.Get() != NetworkSttAudioSink.Get()) return;
    while (const auto Failure = NetworkSttAudioSink->TakeFailure())
    {
        if (VoiceHttpState.IsValid()) ++VoiceHttpState->FailureCount;
        if (Runtime != nullptr && Failure->Key >= 0)
        {
            Runtime->NotifyBatchSttFailed(Failure->Key);
        }
    }
}

bool UGahyeonVoiceInputComponent::SubmitBatchStt(
    Gahyeon::EncodedPcmUtterance Utterance)
{
    check(IsInGameThread());
    UGameInstance* GameInstance = GetWorld() != nullptr
        ? GetWorld()->GetGameInstance()
        : nullptr;
    UGahyeonTransportSubsystem* Transport = GameInstance != nullptr
        ? GameInstance->GetSubsystem<UGahyeonTransportSubsystem>()
        : nullptr;
    if (!VoiceHttpState.IsValid() || Transport == nullptr || !Transport->IsSocketConnected()
        || VoiceHttpState->ActiveRequests.Num() >= FGahyeonVoiceHttpState::MaximumConcurrentRequests
        || Utterance.Wav.empty() || Utterance.Wav.size() > 20 * 1024 * 1024)
    {
        return false;
    }
    const FString Origin = Transport->GetHttpOrigin();
    if (Origin.IsEmpty()) return false;

    FHttpRequestRef Request = FHttpModule::Get().CreateRequest();
    Request->SetVerb(TEXT("POST"));
    Request->SetURL(Origin + TEXT("/gahyeon/unreal/speech/transcriptions"));
    Request->SetHeader(TEXT("Content-Type"), TEXT("audio/wav"));
    Request->SetHeader(TEXT("Accept"), TEXT("application/json"));
    Request->SetTimeout(FMath::Clamp(BatchSttTimeoutSeconds, 1.0f, 30.0f));
    const FString& Token = Transport->GetBearerToken();
    if (!Token.IsEmpty()) Request->SetHeader(TEXT("Authorization"), TEXT("Bearer ") + Token);
    TArray<uint8> Content;
    Content.Append(Utterance.Wav.data(), static_cast<int32>(Utterance.Wav.size()));
    Request->SetContent(MoveTemp(Content));
    const int64 Generation = Utterance.GenerationId;
    const uint64 RuntimeEpoch = Runtime != nullptr ? Runtime->GetRuntimeEpoch() : 0;
    Request->OnProcessRequestComplete().BindLambda(
        [WeakThis = TWeakObjectPtr<UGahyeonVoiceInputComponent>(this), Generation, RuntimeEpoch](
            FHttpRequestPtr CompletedRequest,
            FHttpResponsePtr Response,
            bool bSucceeded)
        {
            if (!WeakThis.IsValid()) return;
            UGahyeonVoiceInputComponent* Self = WeakThis.Get();
            if (!Self->VoiceHttpState.IsValid()) return;
            Self->VoiceHttpState->ActiveRequests.Remove(CompletedRequest);
            if (Self->Runtime == nullptr
                || Self->Runtime->GetRuntimeEpoch() != RuntimeEpoch)
            {
                ++Self->VoiceHttpState->StaleResultCount;
                return;
            }
            if (!bSucceeded || !Response.IsValid()
                || Response->GetResponseCode() < 200 || Response->GetResponseCode() >= 300)
            {
                ++Self->VoiceHttpState->FailureCount;
                if (Self->Runtime != nullptr) Self->Runtime->NotifyBatchSttFailed(Generation);
                return;
            }
            TSharedPtr<FJsonObject> Root;
            const TSharedRef<TJsonReader<>> Reader =
                TJsonReaderFactory<>::Create(Response->GetContentAsString());
            FString Transcript;
            if (!FJsonSerializer::Deserialize(Reader, Root) || !Root.IsValid()
                || !Root->TryGetStringField(TEXT("transcript"), Transcript)
                || Transcript.TrimStartAndEnd().IsEmpty()
                || !Self->EnqueueFinalTranscriptForGenerationFromAnyThread(
                    Transcript, TEXT("ko-KR"), Generation))
            {
                ++Self->VoiceHttpState->FailureCount;
                if (Self->Runtime != nullptr) Self->Runtime->NotifyBatchSttFailed(Generation);
            }
        });
    VoiceHttpState->ActiveRequests.Add(Request);
    if (!Request->ProcessRequest())
    {
        VoiceHttpState->ActiveRequests.Remove(Request);
        return false;
    }
    return true;
}

void UGahyeonVoiceInputComponent::RefreshRuntime()
{
    UGameInstance* GameInstance = GetWorld() != nullptr
        ? GetWorld()->GetGameInstance()
        : nullptr;
    Runtime = GameInstance != nullptr
        ? GameInstance->GetSubsystem<UGahyeonRuntimeSubsystem>()
        : nullptr;
    ObservedRuntimeEpoch = Runtime != nullptr ? Runtime->GetRuntimeEpoch() : 0;
}

void UGahyeonVoiceInputComponent::InvalidateWorkFromPreviousRuntime()
{
    check(IsInGameThread());
    if (InputState.IsValid()) InputState->ResetQueue();
    if (BatchSttAudioSink.IsValid()) BatchSttAudioSink->Reset();
    if (NetworkSttAudioSink.IsValid())
    {
        NetworkSttAudioSink->Reset();
        NetworkSttAudioSink->SetTransportAvailable(
            StreamingSttClient.IsValid() && StreamingSttClient->IsConnected());
    }
    if (!VoiceHttpState.IsValid()) return;
    TArray<FHttpRequestPtr> Requests = MoveTemp(VoiceHttpState->ActiveRequests);
    VoiceHttpState->ActiveRequests.Reset();
    for (const FHttpRequestPtr& Request : Requests)
    {
        if (Request.IsValid()) Request->CancelRequest();
    }
}
