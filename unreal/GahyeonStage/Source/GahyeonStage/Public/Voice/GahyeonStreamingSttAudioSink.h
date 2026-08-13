#pragma once

#include "Voice/GahyeonVoiceInputComponent.h"
#include "Gahyeon/StreamingSttClientRuntime.h"
#include "Gahyeon/PcmUtteranceBuffer.h"

#include <deque>
#include <array>
#include <atomic>
#include <memory>
#include <mutex>
#include <optional>
#include <vector>

class FGahyeonBatchSttAudioSink;

/**
 * Capture-safe adaptive sink. It never touches IWebSocket from the audio callback.
 * Commands are copied into a bounded RuntimeCore queue and drained on the Game Thread.
 */
class GAHYEONSTAGE_API FGahyeonStreamingSttAudioSink final
    : public IGahyeonStreamingSttAudioSink
{
public:
    FGahyeonStreamingSttAudioSink();
    virtual ~FGahyeonStreamingSttAudioSink() override;

    virtual bool TryEnqueuePcm(
        const float* InAudio,
        int32 NumFrames,
        int32 NumChannels,
        int32 SampleRate,
        int64 ObservedAtMs) override;
    virtual bool VoiceActivityStarted(int64 GenerationId, int64 ObservedAtMs) override;
    virtual bool VoiceActivityEnded(int64 GenerationId, int64 ObservedAtMs) override;
    virtual bool VoiceActivityCancelled(int64 GenerationId, int64 ObservedAtMs) override;

    void SetTransportAvailable(bool bAvailable);
    /** Game-thread drain; capture callback only publishes to a fixed preallocated SPSC ring. */
    void TickGameThread();
    void TransportFailed();
    bool ResultIngressBackpressured();
    bool ProviderFailed(int64 GenerationId, const FString& StreamId);
    std::optional<Gahyeon::StreamingSttCommand> TakeCommand();
    std::optional<TPair<int64, Gahyeon::StreamingSttFailure>> TakeFailure();
    Gahyeon::StreamingSttResult AcceptPartial(
        int64 GenerationId,
        const FString& StreamId,
        uint64 ResultSequence,
        const FString& Text);
    Gahyeon::StreamingSttResult AcceptFinal(
        int64 GenerationId,
        const FString& StreamId,
        uint64 ResultSequence,
        const FString& Text);
    std::optional<Gahyeon::EncodedPcmUtterance> TakeCompletedBatch();
    std::optional<int64> TakeFailedBatchGeneration();
    void Reset();

private:
    struct FPcmChunk
    {
        int64 ObservedAtMs = 0;
        int32 NumFrames = 0;
        Gahyeon::StreamingSttFormat Format;
        std::vector<std::uint8_t> Bytes;
    };

    struct FPcmSlot
    {
        int64 ObservedAtMs = 0;
        int32 NumFrames = 0;
        Gahyeon::StreamingSttFormat Format;
        int32 SampleCount = 0;
    };

    static constexpr std::size_t MaximumPreRollChunks = 64;
    static constexpr int64 MaximumPreRollMillis = 750;
    static constexpr std::size_t MaximumPendingPcmChunks = 256;
    static constexpr std::size_t MaximumSamplesPerChunk = 32'768;

    void RetainPreRoll(FPcmChunk Chunk);
    void DrainCaptureLocked(std::optional<int64> ThroughMs = std::nullopt);
    bool RoutePcmLocked(const FPcmSlot& Slot, const float* Samples);
    bool ReplayPreRollToStreaming(const Gahyeon::StreamingSttFormat& Format);
    bool ReplayPreRollToBatch(int64 GenerationId, int64 ObservedAtMs);

    mutable std::mutex Mutex;
    Gahyeon::StreamingSttClientRuntime Runtime;
    TSharedPtr<FGahyeonBatchSttAudioSink, ESPMode::ThreadSafe> BatchFallback;
    std::deque<FPcmChunk> PreRoll;
    std::deque<TPair<int64, Gahyeon::StreamingSttFailure>> Failures;
    std::optional<Gahyeon::StreamingSttMode> ActiveMode;
    std::optional<Gahyeon::StreamingSttFormat> ActiveFormat;
    int64 ActiveGeneration = -1;
    std::array<FPcmSlot, MaximumPendingPcmChunks> PcmSlots;
    std::vector<float> PcmSamples =
        std::vector<float>(MaximumPendingPcmChunks * MaximumSamplesPerChunk);
    std::atomic<std::uint64_t> PcmWrite{0};
    std::atomic<std::uint64_t> PcmRead{0};
};
