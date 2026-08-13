#pragma once

#include "Components/ActorComponent.h"
#include "Interfaces/IHttpRequest.h"
#include "Runtime/GahyeonRuntimeSubsystem.h"
#include "GahyeonSpeechAudioComponent.generated.h"

class UAudioComponent;
class USoundWaveProcedural;
class USoundWave;

/**
 * Presentation-only speech device boundary.
 *
 * RuntimeCore owns ordering/generation. This component downloads one reserved
 * PCM16 WAV segment at a time, reports the actual playback boundary, and stops
 * immediately when RuntimeCore revokes the active utterance.
 */
UCLASS(ClassGroup = (Gahyeon), meta = (BlueprintSpawnableComponent))
class GAHYEONSTAGE_API UGahyeonSpeechAudioComponent final : public UActorComponent
{
    GENERATED_BODY()

public:
    UGahyeonSpeechAudioComponent();

    virtual void BeginPlay() override;
    virtual void EndPlay(const EEndPlayReason::Type EndPlayReason) override;
    virtual void TickComponent(
        float DeltaTime,
        ELevelTick TickType,
        FActorComponentTickFunction* ThisTickFunction) override;

    /** Base such as https://host; relative speech URLs are resolved against it. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Audio")
    void Configure(const FString& InHttpBaseUrl, const FString& InBearerToken);

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Audio")
    bool IsSpeechActive() const;

private:
#if WITH_DEV_AUTOMATION_TESTS
    friend class FGahyeonSpeechWavParserTest;
#endif

    struct FWavPcmView
    {
        int32 SampleRate = 0;
        int32 Channels = 0;
        const uint8* PcmData = nullptr;
        int32 PcmBytes = 0;
    };

    void TryAcquireNext();
    void RefreshTransportConfiguration();
    void StartDownload(const FGahyeonPreparedSpeechSegment& Segment);
    void HandleDownload(
        FHttpRequestPtr Request,
        FHttpResponsePtr Response,
        bool bSucceeded,
        uint64 Serial);
    bool StartPcmPlayback(const TArray<uint8>& Bytes);
    void FailReservedSegment();
    void ClearDeviceState(bool bStopAudio);
    FString ResolveAudioUrl(const FString& AudioUrl) const;
    static bool ParsePcm16Wav(const TArray<uint8>& Bytes, FWavPcmView& Out);

    UFUNCTION()
    void HandleAudioFinished();

    UFUNCTION()
    void HandleEnvelopeValue(const USoundWave* PlayingSoundWave, float EnvelopeValue);

    UFUNCTION()
    void HandleInterruptRequested(const FString& UtteranceId);

    UPROPERTY(Transient)
    TObjectPtr<UGahyeonRuntimeSubsystem> Runtime;

    UPROPERTY(Transient)
    TObjectPtr<UAudioComponent> AudioComponent;

    UPROPERTY(Transient)
    TObjectPtr<USoundWaveProcedural> ActiveWave;

    FGahyeonPreparedSpeechSegment ReservedSegment;
    TSharedPtr<IHttpRequest, ESPMode::ThreadSafe> ActiveRequest;
    FString HttpBaseUrl;
    FString BearerToken;
    uint64 RequestSerial = 0;
    uint64 ObservedRuntimeEpoch = 0;
    double PlaybackDeadlineSeconds = 0.0;
    double PlaybackStartedSeconds = 0.0;
    double CurrentEnvelopeAmplitude = 0.0;
    bool bHasReservedSegment = false;
    bool bPlaybackReported = false;

    /** Bounds reservation ownership when the audio cache endpoint is half-open. */
    UPROPERTY(EditAnywhere, Category = "Gahyeon|Audio", meta = (ClampMin = "1.0", ClampMax = "30.0"))
    float AudioDownloadTimeoutSeconds = 8.0f;

    static constexpr int32 MaxAudioBytes = 32 * 1024 * 1024;
};
