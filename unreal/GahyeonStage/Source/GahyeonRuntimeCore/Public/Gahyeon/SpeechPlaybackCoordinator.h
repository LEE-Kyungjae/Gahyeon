#pragma once

#include "Gahyeon/RealtimeCharacterCoordinator.h"
#include "Gahyeon/SpeechQueue.h"
#include "Gahyeon/LipSyncRuntime.h"

#include <optional>
#include <string>

namespace Gahyeon {

/** Game-thread-owned bridge between prepared audio and actual audio-device callbacks. */
class GAHYEON_RUNTIME_CORE_API SpeechPlaybackCoordinator {
public:
    explicit SpeechPlaybackCoordinator(
        RealtimeCharacterCoordinator& character,
        std::size_t queueCapacity = 16,
        LipSyncRuntime* lipSync = nullptr);

    std::optional<std::string> SetGeneration(Generation generation);
    SpeechEnqueueResult Prepared(PreparedSpeech speech);
    SpeechEnqueueResult SequenceEnded(SpeechSequenceEnd end, Millis nowMs);

    std::optional<PreparedSpeech> AcquireNext();
    bool PlaybackStarted(const std::string& utteranceId, Millis nowMs);
    bool PlaybackFinished(const std::string& utteranceId, Millis nowMs);
    bool PlaybackFailed(const std::string& utteranceId, Millis nowMs);

    bool HasActiveAudio() const;
    bool IsPlaying() const;
    const SpeechQueue& Queue() const;

private:
    bool CompleteActive(const std::string& utteranceId, Millis nowMs);
    void MaybeEndSpeech(Millis nowMs);

    RealtimeCharacterCoordinator& character_;
    SpeechQueue queue_;
    std::optional<PreparedSpeech> active_;
    bool playing_ = false;
    LipSyncRuntime* lipSync_ = nullptr;
};

} // namespace Gahyeon
