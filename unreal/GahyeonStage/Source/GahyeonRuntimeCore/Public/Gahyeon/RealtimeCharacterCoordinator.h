#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <string>
#include <optional>

namespace Gahyeon {

class GAHYEON_RUNTIME_CORE_API RealtimeCharacterCoordinator {
public:
    explicit RealtimeCharacterCoordinator(
        Millis nowMs = 0,
        Millis cognitionTimeoutMs = 10'000);

    GenerationSyncResult SynchronizeGeneration(Generation generation);
    Generation VoiceStarted(Millis nowMs);
    PublishResult PartialTranscriptObserved(
        Generation generation,
        double stability,
        Millis nowMs);
    PublishResult VoiceEnded(Generation generation, Millis nowMs);
    bool SpeechStarted(
        Generation generation,
        Millis nowMs,
        std::string utteranceId);
    PublishResult SpeechEnded(Generation generation, Millis nowMs);
    std::optional<Generation> CancelInteraction(Generation generation, Millis nowMs);
    std::optional<Generation> Advance(Millis nowMs);

    IntentRuntime& Intents();
    const IntentRuntime& Intents() const;

private:
    IntentRuntime intents_;
    Millis cognitionTimeoutMs_;
    std::optional<Generation> thinkingGeneration_;
    std::optional<Millis> thinkingDeadlineMs_;
};

} // namespace Gahyeon
