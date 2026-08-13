#pragma once

#include "Voice/GahyeonVoiceInputComponent.h"
#include "Gahyeon/PcmUtteranceBuffer.h"

#include <memory>
#include <optional>

/** Dedicated worker that assembles bounded VAD utterances without blocking capture/Game Thread. */
class GAHYEONSTAGE_API FGahyeonBatchSttAudioSink final
    : public IGahyeonStreamingSttAudioSink
{
public:
    FGahyeonBatchSttAudioSink();
    virtual ~FGahyeonBatchSttAudioSink() override;

    virtual bool TryEnqueuePcm(
        const float* InAudio,
        int32 NumFrames,
        int32 NumChannels,
        int32 SampleRate,
        int64 ObservedAtMs) override;
    virtual bool VoiceActivityStarted(int64 GenerationId, int64 ObservedAtMs) override;
    virtual bool VoiceActivityEnded(int64 GenerationId, int64 ObservedAtMs) override;
    virtual bool VoiceActivityCancelled(int64 GenerationId, int64 ObservedAtMs) override;

    std::optional<Gahyeon::EncodedPcmUtterance> TakeCompleted();
    std::optional<int64> TakeFailedGeneration();
    /** Discard all pre-runtime-replacement PCM, lifecycle, and completed work. */
    void Reset();
    int32 GetDroppedPcmChunkCount() const;
    int32 GetDroppedLifecycleEventCount() const;
    int32 GetDroppedCompletedUtteranceCount() const;

private:
    struct FImpl;
    std::unique_ptr<FImpl> Impl;
};
