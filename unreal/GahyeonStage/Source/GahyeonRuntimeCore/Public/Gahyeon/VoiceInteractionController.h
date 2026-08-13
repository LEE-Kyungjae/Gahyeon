#pragma once

#include "Gahyeon/SpeechPlaybackCoordinator.h"
#include "Gahyeon/VoiceActivityDetector.h"
#include "Gahyeon/LatencyTrace.h"

#include <optional>
#include <string>

namespace Gahyeon {

struct VoiceInteractionResult {
    VoiceActivityEvent Event = VoiceActivityEvent::None;
    Generation GenerationId = 0;
    std::optional<std::string> InterruptedUtteranceId;
};

struct CognitionTimeoutResult {
    Generation GenerationId = 0;
    std::optional<std::string> InterruptedUtteranceId;
};

/** Converts local VAD transitions into immediate character and playback actions. */
class GAHYEON_RUNTIME_CORE_API VoiceInteractionController {
public:
    VoiceInteractionController(
        RealtimeCharacterCoordinator& character,
        SpeechPlaybackCoordinator& playback,
        VoiceActivityConfig config = {},
        LatencyTrace* latency = nullptr);

    VoiceInteractionResult Observe(double level, Millis nowMs);
    /** Abort only an in-progress VAD capture; completed speech/cognition is left untouched. */
    std::optional<CognitionTimeoutResult> AbortActiveCapture(Millis nowMs);
    std::optional<CognitionTimeoutResult> FailRecognition(
        Generation generation,
        Millis nowMs);
    std::optional<CognitionTimeoutResult> Tick(Millis nowMs);
    LatencyTraceResult MarkListeningPresented(Generation generation, Millis nowMs);
    LatencyTraceResult MarkInterruptedAudioStopped(Generation generation, Millis nowMs);
    const VoiceActivityDetector& Detector() const;

private:
    RealtimeCharacterCoordinator& character_;
    SpeechPlaybackCoordinator& playback_;
    VoiceActivityDetector detector_;
    LatencyTrace* latency_ = nullptr;
};

} // namespace Gahyeon
