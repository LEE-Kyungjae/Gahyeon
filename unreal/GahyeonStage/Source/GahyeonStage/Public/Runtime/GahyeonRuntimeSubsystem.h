#pragma once

#include "CoreMinimal.h"
#include "Containers/Queue.h"
#include "HAL/ThreadSafeCounter.h"
#include "HAL/CriticalSection.h"
#include "Protocol/GahyeonProtocolEnvelope.h"
#include "Subsystems/GameInstanceSubsystem.h"
#include "Tickable.h"
#include "GahyeonRuntimeSubsystem.generated.h"

class UGahyeonRuntimeSaveGame;
class AGahyeonPresentationHost;
class UGahyeonCharacterPresentationProfile;
class FJsonObject;
namespace Gahyeon { struct WorldActionCompletion; }

DECLARE_DYNAMIC_MULTICAST_DELEGATE_OneParam(
    FGahyeonAudioInterruptRequested,
    const FString&,
    UtteranceId);

UENUM(BlueprintType)
enum class EGahyeonVoiceActivityEdge : uint8
{
    None,
    Started,
    Ended
};

struct FGahyeonVoiceActivityObservation
{
    EGahyeonVoiceActivityEdge Edge = EGahyeonVoiceActivityEdge::None;
    int64 GenerationId = 0;
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonVisemeCue
{
    GENERATED_BODY()

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    FString Semantic;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    int64 AtMs = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    int64 DurationMs = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    double Weight = 1.0;
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonPreparedSpeechSegment
{
    GENERATED_BODY()

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    int64 Generation = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    FString UtteranceId;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    int32 UtteranceIndex = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    int32 SegmentIndex = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    bool bFinalSegment = false;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    FString AudioUrl;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    FString MimeType;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Audio")
    TArray<FGahyeonVisemeCue> Visemes;
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonLatencySummary
{
    GENERATED_BODY()

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    int64 SampleCount = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    int64 P50Ms = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    int64 P95Ms = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    int64 P99Ms = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    int64 WorstMs = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    int64 BudgetMs = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    int64 BudgetViolations = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    bool bPassesP95 = false;
};

USTRUCT(BlueprintType)
struct GAHYEONSTAGE_API FGahyeonRuntimeFrameSnapshot
{
    GENERATED_BODY()

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    double MonotonicSeconds = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 PresentationFrames = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 ReflexUpdates = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 BehaviorUpdates = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    bool bBackendConnected = false;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int32 InboundQueueDepth = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 AppliedInboundEvents = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 DroppedInboundEvents = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 CurrentGeneration = 0;

    /** Process-local RuntimeCore ownership token for async Presentation work. */
    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 RuntimeEpoch = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 CognitionTimeoutCount = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 LastCognitionTimeoutGeneration = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 ReconnectConvergenceTimeoutCount = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Runtime")
    int64 ReconnectDiscardedInboundEvents = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    FGahyeonLatencySummary VadToListeningLatency;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    FGahyeonLatencySummary BargeInToAudioStopLatency;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    FGahyeonLatencySummary ReconnectToSnapshotLatency;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    FGahyeonLatencySummary VisemeOnsetLatency;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    FGahyeonLatencySummary TranscriptToThinkingLatency;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    FGahyeonLatencySummary FirstAudioPlayableLatency;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Latency")
    FGahyeonLatencySummary VoiceEndToFinalTranscriptLatency;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    bool bHasWorldState = false;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    int64 WorldRevision = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FString CurrentRoom;

    /** Authoritative Core meters converted to Unreal centimeters. */
    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FVector WorldPosition = FVector::ZeroVector;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FString Activity;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    bool bWorldActionActive = false;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FString ActiveWorldActionId;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FString WorldActionPhase;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    int64 WorldActionExpectedRevision = 0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FString WorldActionTargetRoom;

    /** Target converted from Core meters to Unreal centimeters. */
    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FVector WorldActionTargetPosition = FVector::ZeroVector;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FString WorldActionTargetActivity;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|World")
    FString WorldActionInteractionTarget;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    FString ConversationPhase = TEXT("idle");

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    FString Posture = TEXT("ambient_alive");

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Attention")
    FString AttentionTargetSemantic;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    FString ExpressionSemantic;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    FString DominantEmotion;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    double DominantEmotionIntensity = 0.0;

    /** Full semantic blend; Anim Blueprint maps these keys through the local profile. */
    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    TMap<FName, double> EmotionDimensions;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    double EmotionValence = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    double EmotionArousal = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    double EmotionDominance = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Behavior")
    bool bEmotionReleasing = false;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Ambient")
    double Breath = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Ambient")
    double Blink = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Ambient")
    double AmbientEyeYaw = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Ambient")
    double AmbientEyePitch = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Ambient")
    double MicroHeadYaw = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Ambient")
    double MicroHeadPitch = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Ambient")
    double WeightShift = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Attention")
    double AttentionEyeYaw = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Attention")
    double AttentionEyePitch = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Attention")
    double AttentionHeadYaw = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Attention")
    double AttentionHeadPitch = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Attention")
    double AttentionTrackingWeight = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Gesture")
    bool bGestureActive = false;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Gesture")
    FString GestureSemantic;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Gesture")
    FString GestureVariantId;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|Gesture")
    double GestureIntensity = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|LipSync")
    bool bLipSyncActive = false;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|LipSync")
    bool bLipSyncUsingTimeline = false;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|LipSync")
    double JawOpen = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|LipSync")
    FString PrimaryViseme;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|LipSync")
    double PrimaryVisemeWeight = 0.0;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|LipSync")
    FString SecondaryViseme;

    UPROPERTY(BlueprintReadOnly, Category = "Gahyeon|LipSync")
    double SecondaryVisemeWeight = 0.0;
};

/**
 * Game-thread owner for the local presentation cadence.
 *
 * Network and Cognition callbacks may publish immutable work later, but they
 * must never block or directly mutate this subsystem. Ambient presentation,
 * Reflex, and Behavior advance even when no Backend exists.
 */
UCLASS()
class GAHYEONSTAGE_API UGahyeonRuntimeSubsystem final
    : public UGameInstanceSubsystem
    , public FTickableGameObject
{
    GENERATED_BODY()

public:
    using FOutboundSender = TFunction<bool(const FString&)>;
    using FReconnectRequester = TFunction<void()>;

    virtual ~UGahyeonRuntimeSubsystem() override;
    virtual void Initialize(FSubsystemCollectionBase& Collection) override;
    virtual void Deinitialize() override;

    virtual void Tick(float DeltaTime) override;
    virtual TStatId GetStatId() const override;
    virtual bool IsTickable() const override;
    virtual bool IsTickableWhenPaused() const override { return true; }

    UFUNCTION(BlueprintPure, Category = "Gahyeon|Runtime")
    FGahyeonRuntimeFrameSnapshot GetSnapshot() const { return Snapshot; }

    /** Benchmark-only raw samples; never used by frame presentation decisions. */
    void CopyLookingGlassAcceptanceLatencySamples(
        TArray<int64>& OutVadToListening,
        TArray<int64>& OutBargeInToAudioStop,
        TArray<int64>& OutAudioToViseme) const;
    void ResetLookingGlassAcceptanceLatencySamples();

    /**
     * Process-local identity of the currently installed RuntimeCore instance.
     * Async adapters must reject work captured under an older epoch even when
     * the restored interaction generation happens to have the same value.
     */
    uint64 GetRuntimeEpoch() const { return RuntimeEpoch; }

    /** Stop this utterance immediately; generation arbitration already discarded its queue. */
    UPROPERTY(BlueprintAssignable, Category = "Gahyeon|Audio")
    FGahyeonAudioInterruptRequested OnAudioInterruptRequested;

    /** Reserves the next ordered segment for the audio device. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Audio")
    bool AcquireNextSpeechSegment(FGahyeonPreparedSpeechSegment& OutSegment);

    /** Call only when the audio device actually begins playback. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Audio")
    bool NotifySpeechPlaybackStarted(const FString& UtteranceId);

    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Audio")
    bool NotifySpeechPlaybackFinished(const FString& UtteranceId);

    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Audio")
    bool NotifySpeechPlaybackFailed(const FString& UtteranceId);

    /** Audio-device-relative cursor; amplitude is normalized 0..1 when available. */
    void UpdateSpeechPlaybackSample(int64 PlaybackPositionMs, double Amplitude);

    /** Reflex input in character-local Unreal coordinates: X forward, Y right, Z up. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Attention")
    bool SetLocalAttentionTarget(FVector LocalTarget, double Confidence);

    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Attention")
    void ClearAttentionTarget();

    /** Local normalized microphone level; VAD/Reflex never waits for Backend. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Voice")
    bool ObserveMicrophoneLevel(double NormalizedLevel);

    /** Game-thread drain path preserving the capture callback's monotonic timestamp. */
    bool ObserveMicrophoneLevelAt(double NormalizedLevel, int64 ObservedAtMs);
    FGahyeonVoiceActivityObservation ObserveMicrophoneLevelAtDetailed(
        double NormalizedLevel,
        int64 ObservedAtMs);

    /** Abort an active local VAD capture and invalidate all results for its generation. */
    bool AbortMicrophoneCapture(int64& OutAbortedGeneration);

    /** Sends a non-authoritative streaming transcript for the active local generation. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Voice")
    bool SubmitPartialTranscript(const FString& Text, double Stability);
    bool SubmitPartialTranscriptForGeneration(
        const FString& Text,
        double Stability,
        int64 Generation);

    /** Sends the authoritative transcript command and starts real presentation latency spans. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Voice")
    bool SubmitFinalTranscript(const FString& Text, const FString& Language);
    bool SubmitFinalTranscriptForGeneration(
        const FString& Text,
        const FString& Language,
        int64 Generation);

    void NotifyBatchSttFailed(int64 Generation);

    /** Called by the visible presentation after Listening is physically applied. */
    bool NotifyListeningPresented(int64 Generation, uint64 PresentationRuntimeEpoch);

    /** Called by the audio adapter after the owning old audio device has stopped. */
    void NotifyInterruptedAudioStopped();

    /** Start transcript→Thinking and transcript→first-playable spans at actual submission. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|Voice")
    void NotifyFinalTranscriptSubmitted();

    /** Called by the visible presentation after Thinking is physically applied. */
    bool NotifyThinkingPresented(int64 Generation, uint64 PresentationRuntimeEpoch);

    /** Records audio-to-mouth latency only after a renderer applies the viseme control. */
    bool NotifyVisemePresented(
        const FString& Semantic,
        int64 Generation,
        uint64 PresentationRuntimeEpoch);

    /** Loads local semantic→variant rules; animation objects remain in Presentation. */
    bool ConfigurePresentationProfile(const UGahyeonCharacterPresentationProfile& Profile);

    /** Game-thread transport callback; socket threads must enqueue first. */
    void SetBackendConnected(bool bConnected);

    /** Thread-safe ingress. Returns false rather than growing without bound. */
    bool EnqueueInbound(FGahyeonProtocolEnvelope Envelope);

    bool RestorePersistentState(const UGahyeonRuntimeSaveGame& State);
    void BeginBackendConnection();
    void SetOutboundSender(FOutboundSender Sender);
    void SetReconnectRequester(FReconnectRequester Requester);
    bool CompleteDurableEvent(int64 Sequence);

    /** FinalPosition is an Unreal World position in centimeters. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|World")
    bool QueueActionCompletion(
        const FString& ActionId,
        int64 ExpectedRevision,
        const FString& Outcome,
        const FString& Reason,
        FVector FinalPosition);

    /** Call when local navigation physically reaches the requested target. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|World")
    bool NotifyWorldNavigationArrived(const FString& ActionId);

    /** Completes local interaction and durably reports the physical result to Core. */
    UFUNCTION(BlueprintCallable, Category = "Gahyeon|World")
    bool NotifyWorldActionFinished(
        const FString& ActionId,
        const FString& Outcome,
        const FString& Reason,
        FVector FinalPosition);

private:
    enum class EInboundApplyResult : uint8
    {
        Consumed,
        Backpressured,
        ReconnectRequired
    };

    struct FRuntimeCoreState;

    void DrainInbound();
    void ResetInboundForReconnect();
    EInboundApplyResult ApplyInbound(const FGahyeonProtocolEnvelope& Envelope);
    void PersistRuntimeState(int64 SequenceToConfirm, const FString& ActionToConfirm = {});
    void SendNextPersistedEgress();
    void EnsurePresentationHost();
    void DestroyPresentationHost();
    void RefreshPresentationSnapshot(int64 NowMs);
    void AdvanceReflex(double NowSeconds);
    void AdvanceBehavior(double NowSeconds);
    bool QueueRuntimeActionCompletion(const Gahyeon::WorldActionCompletion& Completion);
    bool SendPerceptionMessage(
        const FString& Type,
        const FString& Delivery,
        int64 Generation,
        const TSharedRef<FJsonObject>& Payload);
    /** Critical cancellation cannot be silently dropped; failure forces session convergence. */
    bool SendGenerationAdvance(int64 Generation, const FString& Reason);

    static constexpr double ReflexIntervalSeconds = 0.05;
    static constexpr double BehaviorIntervalSeconds = 0.20;
    static constexpr int32 MaxInboundQueueDepth = 1024;
    static constexpr int32 MaxLatestStateInboundQueueDepth = 768;
    static constexpr int32 MaxInboundEventsPerFrame = 64;

    FGahyeonRuntimeFrameSnapshot Snapshot;
    TQueue<FGahyeonProtocolEnvelope, EQueueMode::Mpsc> InboundQueue;
    FCriticalSection InboundResetMutex;
    TOptional<FGahyeonProtocolEnvelope> DeferredInbound;
    FThreadSafeCounter InboundDepth;
    FThreadSafeCounter DroppedInbound;
    TUniquePtr<FRuntimeCoreState> RuntimeCore;
    FOutboundSender OutboundSender;
    FReconnectRequester ReconnectRequester;
    UPROPERTY(Transient)
    TObjectPtr<AGahyeonPresentationHost> PresentationHost;
    uint64 PersistenceGeneration = 0;
    uint64 RuntimeEpoch = 0;
    double NextReflexAt = 0.0;
    double NextBehaviorAt = 0.0;
    bool bOwnsPresentationHost = false;
    bool bInitialized = false;
};
