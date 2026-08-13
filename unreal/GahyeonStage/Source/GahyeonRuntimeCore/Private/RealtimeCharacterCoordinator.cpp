#include "Gahyeon/RealtimeCharacterCoordinator.h"

#include <utility>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace Gahyeon {

RealtimeCharacterCoordinator::RealtimeCharacterCoordinator(
    Millis nowMs,
    Millis cognitionTimeoutMs)
    : cognitionTimeoutMs_(cognitionTimeoutMs) {
    if (cognitionTimeoutMs_ <= 0) {
        throw std::invalid_argument("cognition timeout must be positive");
    }
    intents_.Publish(CharacterIntent{
        .Id = "ambient.posture",
        .Layer = IntentLayer::Behavior,
        .Channel = IntentChannel::Posture,
        .GenerationId = std::nullopt,
        .Priority = 1,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = std::nullopt,
        .Value = "ambient_alive",
    });
}

GenerationSyncResult RealtimeCharacterCoordinator::SynchronizeGeneration(Generation generation) {
    const GenerationSyncResult result = intents_.SynchronizeGeneration(generation);
    if (result == GenerationSyncResult::Advanced) {
        thinkingGeneration_.reset();
        thinkingDeadlineMs_.reset();
    }
    return result;
}

Generation RealtimeCharacterCoordinator::VoiceStarted(Millis nowMs) {
    thinkingGeneration_.reset();
    thinkingDeadlineMs_.reset();
    const Generation generation = intents_.BeginGeneration();
    intents_.Publish(CharacterIntent{
        .Id = "conversation.phase",
        .Layer = IntentLayer::Reflex,
        .Channel = IntentChannel::Phase,
        .GenerationId = generation,
        .Priority = 100,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = std::nullopt,
        .Value = "listening",
    });
    intents_.Publish(CharacterIntent{
        .Id = "attention.user." + std::to_string(generation),
        .Layer = IntentLayer::Reflex,
        .Channel = IntentChannel::Attention,
        .GenerationId = generation,
        .Priority = 100,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = 750,
        .Value = "user",
    });
    return generation;
}

PublishResult RealtimeCharacterCoordinator::PartialTranscriptObserved(
    Generation generation,
    double stability,
    Millis nowMs) {
    if (!std::isfinite(stability) || stability < 0.0 || stability > 1.0
        || generation != intents_.CurrentGeneration()) {
        return generation < intents_.CurrentGeneration()
            ? PublishResult::Stale
            : PublishResult::Invalid;
    }
    const ResolvedIntents current = intents_.Resolve(nowMs);
    const CharacterIntent* phase = current.Find(IntentChannel::Phase);
    if (phase == nullptr || phase->Value != "listening") return PublishResult::Invalid;
    return intents_.Publish(CharacterIntent{
        .Id = "attention.user." + std::to_string(generation),
        .Layer = IntentLayer::Reflex,
        .Channel = IntentChannel::Attention,
        .GenerationId = generation,
        .Priority = 100,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = 750,
        .Value = "user",
    });
}

PublishResult RealtimeCharacterCoordinator::VoiceEnded(
    Generation generation,
    Millis nowMs) {
    const PublishResult result = intents_.Publish(CharacterIntent{
        .Id = "conversation.phase",
        .Layer = IntentLayer::Behavior,
        .Channel = IntentChannel::Phase,
        .GenerationId = generation,
        .Priority = 60,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = std::nullopt,
        .Value = "thinking",
    });
    if (result == PublishResult::Accepted) {
        thinkingGeneration_ = generation;
        thinkingDeadlineMs_ = nowMs > std::numeric_limits<Millis>::max() - cognitionTimeoutMs_
            ? std::numeric_limits<Millis>::max()
            : nowMs + cognitionTimeoutMs_;
    }
    return result;
}

bool RealtimeCharacterCoordinator::SpeechStarted(
    Generation generation,
    Millis nowMs,
    std::string utteranceId) {
    const PublishResult phase = intents_.Publish(CharacterIntent{
        .Id = "conversation.phase",
        .Layer = IntentLayer::Cognition,
        .Channel = IntentChannel::Phase,
        .GenerationId = generation,
        .Priority = 70,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = std::nullopt,
        .Value = "speaking",
    });
    if (phase != PublishResult::Accepted) {
        return false;
    }
    thinkingGeneration_.reset();
    thinkingDeadlineMs_.reset();
    return intents_.Publish(CharacterIntent{
        .Id = "conversation.speech",
        .Layer = IntentLayer::Cognition,
        .Channel = IntentChannel::Speech,
        .GenerationId = generation,
        .Priority = 70,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = std::nullopt,
        .Value = std::move(utteranceId),
    }) == PublishResult::Accepted;
}

PublishResult RealtimeCharacterCoordinator::SpeechEnded(
    Generation generation,
    Millis nowMs) {
    const PublishResult speech = intents_.Publish(CharacterIntent{
        .Id = "conversation.speech",
        .Layer = IntentLayer::Behavior,
        .Channel = IntentChannel::Speech,
        .GenerationId = generation,
        .Priority = 10,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = std::nullopt,
        .Value = "",
    });
    if (speech != PublishResult::Accepted) return speech;
    const PublishResult phase = intents_.Publish(CharacterIntent{
        .Id = "conversation.phase",
        .Layer = IntentLayer::Behavior,
        .Channel = IntentChannel::Phase,
        .GenerationId = generation,
        .Priority = 10,
        .CreatedAtMs = nowMs,
        .ExpiresAfterMs = std::nullopt,
        .Value = "idle",
    });
    if (phase == PublishResult::Accepted) {
        thinkingGeneration_.reset();
        thinkingDeadlineMs_.reset();
    }
    return phase;
}

std::optional<Generation> RealtimeCharacterCoordinator::CancelInteraction(
    Generation generation,
    Millis nowMs) {
    if (generation != intents_.CurrentGeneration()) return std::nullopt;
    const Generation next = intents_.BeginGeneration();
    thinkingGeneration_.reset();
    thinkingDeadlineMs_.reset();
    SpeechEnded(next, nowMs);
    return next;
}

std::optional<Generation> RealtimeCharacterCoordinator::Advance(Millis nowMs) {
    if (!thinkingGeneration_.has_value() || !thinkingDeadlineMs_.has_value()
        || nowMs < thinkingDeadlineMs_.value()) {
        return std::nullopt;
    }
    if (thinkingGeneration_.value() != intents_.CurrentGeneration()) {
        thinkingGeneration_.reset();
        thinkingDeadlineMs_.reset();
        return std::nullopt;
    }
    return CancelInteraction(thinkingGeneration_.value(), nowMs);
}

IntentRuntime& RealtimeCharacterCoordinator::Intents() {
    return intents_;
}

const IntentRuntime& RealtimeCharacterCoordinator::Intents() const {
    return intents_;
}

} // namespace Gahyeon
