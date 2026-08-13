#include "Gahyeon/VoiceInteractionController.h"

namespace Gahyeon {

VoiceInteractionController::VoiceInteractionController(
    RealtimeCharacterCoordinator& character,
    SpeechPlaybackCoordinator& playback,
    VoiceActivityConfig config,
    LatencyTrace* latency)
    : character_(character), playback_(playback), detector_(config), latency_(latency) {}

VoiceInteractionResult VoiceInteractionController::Observe(
    double level,
    Millis nowMs) {
    const VoiceActivityEvent event = detector_.Observe(level, nowMs);
    VoiceInteractionResult result{
        .Event = event,
        .GenerationId = character_.Intents().CurrentGeneration(),
        .InterruptedUtteranceId = std::nullopt,
    };
    if (event == VoiceActivityEvent::Started) {
        result.GenerationId = character_.VoiceStarted(nowMs);
        result.InterruptedUtteranceId = playback_.SetGeneration(result.GenerationId);
        if (latency_ != nullptr) {
            latency_->Begin(
                LatencyMetric::VadToListening,
                result.GenerationId * 8 + 1,
                nowMs);
            if (result.InterruptedUtteranceId.has_value()) {
                latency_->Begin(
                    LatencyMetric::BargeInToAudioStop,
                    result.GenerationId * 8 + 2,
                    nowMs);
            }
        }
    } else if (event == VoiceActivityEvent::Ended) {
        result.GenerationId = character_.Intents().CurrentGeneration();
        character_.VoiceEnded(result.GenerationId, nowMs);
    }
    return result;
}

std::optional<CognitionTimeoutResult> VoiceInteractionController::AbortActiveCapture(
    Millis nowMs) {
    if (!detector_.Active()) return std::nullopt;
    const Generation generation = character_.Intents().CurrentGeneration();
    detector_.Reset();
    return FailRecognition(generation, nowMs);
}

std::optional<CognitionTimeoutResult> VoiceInteractionController::FailRecognition(
    Generation generation,
    Millis nowMs) {
    const std::optional<Generation> next = character_.CancelInteraction(generation, nowMs);
    if (!next.has_value()) return std::nullopt;
    return CognitionTimeoutResult{
        .GenerationId = *next,
        .InterruptedUtteranceId = playback_.SetGeneration(*next),
    };
}

LatencyTraceResult VoiceInteractionController::MarkListeningPresented(
    Generation generation,
    Millis nowMs) {
    if (latency_ == nullptr) return LatencyTraceResult::Missing;
    return latency_->End(generation * 8 + 1, nowMs);
}

LatencyTraceResult VoiceInteractionController::MarkInterruptedAudioStopped(
    Generation generation,
    Millis nowMs) {
    if (latency_ == nullptr) return LatencyTraceResult::Missing;
    return latency_->End(generation * 8 + 2, nowMs);
}

std::optional<CognitionTimeoutResult> VoiceInteractionController::Tick(Millis nowMs) {
    const auto generation = character_.Advance(nowMs);
    if (!generation.has_value()) return std::nullopt;
    return CognitionTimeoutResult{
        .GenerationId = generation.value(),
        .InterruptedUtteranceId = playback_.SetGeneration(generation.value()),
    };
}

const VoiceActivityDetector& VoiceInteractionController::Detector() const {
    return detector_;
}

} // namespace Gahyeon
