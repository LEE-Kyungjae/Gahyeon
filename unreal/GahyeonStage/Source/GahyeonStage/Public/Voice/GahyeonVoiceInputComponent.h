#pragma once

#include "Components/ActorComponent.h"
#include "Gahyeon/PcmUtteranceBuffer.h"
#include "GahyeonVoiceInputComponent.generated.h"

class UGahyeonRuntimeSubsystem;
struct FGahyeonVoiceInputState;
struct FGahyeonVoiceHttpState;
class FGahyeonBatchSttAudioSink;
class FGahyeonStreamingSttAudioSink;
class FGahyeonStreamingSttWebSocketClient;
namespace Audio { class FAudioCapture; }

/** Thread-safe, non-blocking PCM ingress implemented by a replaceable STT provider. */
class GAHYEONSTAGE_API IGahyeonStreamingSttAudioSink
{
public:
    virtual ~IGahyeonStreamingSttAudioSink() = default;

    /** Must copy or consume before returning; InAudio is valid only during the callback. */
    virtual bool TryEnqueuePcm(
        const float* InAudio,
        int32 NumFrames,
        int32 NumChannels,
        int32 SampleRate,
        int64 ObservedAtMs) = 0;

    virtual bool VoiceActivityStarted(int64 GenerationId, int64 ObservedAtMs) = 0;
    virtual bool VoiceActivityEnded(int64 GenerationId, int64 ObservedAtMs) = 0;
    /** Discard a truncated utterance without asking the provider to transcribe it. */
    virtual bool VoiceActivityCancelled(int64 GenerationId, int64 ObservedAtMs) = 0;
};

/**
 * Bounded thread boundary for microphone/STT providers.
 *
 * Provider callbacks may enqueue only immutable observations. Runtime state and
 * WebSocket operations are drained on the Game Thread in capture order.
 */
UCLASS(ClassGroup = (Gahyeon), meta = (BlueprintSpawnableComponent))
class GAHYEONSTAGE_API UGahyeonVoiceInputComponent final : public UActorComponent
{
    GENERATED_BODY()

public:
    UGahyeonVoiceInputComponent();
    virtual ~UGahyeonVoiceInputComponent() override;

    virtual void BeginPlay() override;
    virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;
    virtual void TickComponent(
        float DeltaTime,
        ELevelTick TickType,
        FActorComponentTickFunction* ThisTickFunction) override;

    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Voice")
    bool StartMicrophoneCapture();

    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Voice")
    void StopMicrophoneCapture();

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    bool IsMicrophoneCapturing() const;

    /** Install only while capture is stopped; the callback retains a thread-safe shared ref. */
    bool SetStreamingSttAudioSink(
        TSharedPtr<IGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe> Sink);

    /** Call from a capture worker while a weak owner/lifetime guard is valid. */
    bool EnqueueAudioLevelFromAnyThread(double NormalizedLevel, int64 ObservedAtMs);

    /** Call from a streaming STT worker while a weak owner/lifetime guard is valid. */
    bool EnqueuePartialTranscriptFromAnyThread(const FString& Text, double Stability);
    bool EnqueuePartialTranscriptForGenerationFromAnyThread(
        const FString& Text,
        double Stability,
        int64 GenerationId);

    /** Call once for the accepted final STT result. */
    bool EnqueueFinalTranscriptFromAnyThread(const FString& Text, const FString& Language);
    bool EnqueueFinalTranscriptForGenerationFromAnyThread(
        const FString& Text,
        const FString& Language,
        int64 GenerationId);

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetPendingObservationCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetDroppedObservationCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetCaptureOverflowCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetSttBackpressureCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetSttLifecycleBackpressureCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetBatchSttFailureCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetStaleSttResultCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetBatchPcmDropCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetBatchLifecycleDropCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    int32 GetBatchUtteranceDropCount() const;

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Voice")
    FString GetLastCaptureError() const { return LastCaptureError; }

private:
    void RefreshRuntime();
    void InvalidateWorkFromPreviousRuntime();
    void DrainCompletedBatchStt();
    void ConfigureStreamingStt();
    void DrainStreamingStt();
    bool SubmitBatchStt(Gahyeon::EncodedPcmUtterance Utterance);

    static constexpr int32 MaximumObservationsPerFrame = 128;

    TSharedPtr<FGahyeonVoiceInputState, ESPMode::ThreadSafe> InputState;
    TSharedPtr<IGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe> StreamingSttAudioSink;
    TSharedPtr<FGahyeonBatchSttAudioSink, ESPMode::ThreadSafe> BatchSttAudioSink;
    TSharedPtr<FGahyeonStreamingSttAudioSink, ESPMode::ThreadSafe> NetworkSttAudioSink;
    TSharedPtr<FGahyeonStreamingSttWebSocketClient, ESPMode::ThreadSafe> StreamingSttClient;
    TUniquePtr<Audio::FAudioCapture> AudioCapture;
    TUniquePtr<FGahyeonVoiceHttpState> VoiceHttpState;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Voice|Capture")
    bool bStartCaptureOnBeginPlay = false;

    UPROPERTY(EditAnywhere, Category = "Gahyeon|Voice|Capture", meta = (ClampMin = "64", ClampMax = "4096"))
    int32 DesiredCaptureFrames = 480;

    /** Opt-in until the Backend provider is configured and latency-evaluated. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|Voice|STT")
    bool bEnableStreamingStt = false;

    /** Hard provider boundary; a half-open request must not occupy an STT slot forever. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|Voice|STT", meta = (ClampMin = "1.0", ClampMax = "30.0"))
    float BatchSttTimeoutSeconds = 8.0f;

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonRuntimeSubsystem> Runtime;

    UPROPERTY(Transient)
    FString LastCaptureError;

    uint64 ObservedRuntimeEpoch = 0;
};
