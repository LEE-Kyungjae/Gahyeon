#include "Gahyeon/ProtocolEventRuntime.h"

#include <utility>

namespace Gahyeon {

ProtocolEventRuntime::ProtocolEventRuntime(
    RealtimeCharacterCoordinator& character,
    SpeechPlaybackCoordinator& playback,
    EmotionRuntime* emotion,
    GestureRuntime* gestures,
    WorldStateRuntime* world,
    WorldActionRuntime* worldActions)
    : character_(character), playback_(playback), emotion_(emotion), gestures_(gestures),
      world_(world), worldActions_(worldActions) {}

ProtocolApplyResult ProtocolEventRuntime::Apply(
    const ProtocolMessage& message,
    Millis nowMs) {
    const TranslationResult translated = translator_.Translate(message, nowMs);
    if (translated.Status == TranslationStatus::Invalid) {
        return {ProtocolApplyStatus::Invalid, std::nullopt, std::nullopt};
    }

    std::optional<std::string> interrupted;
    std::optional<WorldActionCompletion> actionCompletion;
    if (message.GenerationId.has_value() && CarriesAuthoritativeGeneration(message.Type)) {
        const Generation generation = message.GenerationId.value();
        if (generation < character_.Intents().CurrentGeneration()) {
            return {ProtocolApplyStatus::Stale, std::nullopt, std::nullopt};
        }
        if (generation > character_.Intents().CurrentGeneration()) {
            character_.SynchronizeGeneration(generation);
            interrupted = playback_.SetGeneration(generation);
            if (gestures_ != nullptr) gestures_->SetGeneration(generation);
            if (worldActions_ != nullptr && world_ != nullptr
                && world_->Current().has_value()) {
                actionCompletion = worldActions_->SetGeneration(
                    generation, nowMs, world_->Current()->Position);
            }
        }
    }

    if (translated.Status == TranslationStatus::Ignored) {
        return {ProtocolApplyStatus::Ignored, std::move(interrupted), std::move(actionCompletion)};
    }

    if (message.Type == "world.snapshot" && world_ != nullptr) {
        const WorldStateApplyResult result = world_->ApplySnapshot(message.Snapshot.value());
        if (result == WorldStateApplyResult::Stale) {
            return {ProtocolApplyStatus::Stale, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == WorldStateApplyResult::Conflict
            || result == WorldStateApplyResult::Invalid) {
            return {ProtocolApplyStatus::Invalid, std::move(interrupted), std::move(actionCompletion)};
        }
    }

    if (translated.Emotion.has_value() && emotion_ != nullptr
        && emotion_->ApplyTarget(translated.Emotion.value(), nowMs)
            != EmotionApplyResult::Applied) {
        return {ProtocolApplyStatus::Invalid, std::move(interrupted), std::move(actionCompletion)};
    }
    if (message.Type == "gesture.intent" && gestures_ != nullptr) {
        const GestureRequestResult result = gestures_->Request(GestureIntent{
            .Semantic = message.Semantic,
            .Intensity = message.Intensity,
            .CurrentPosture = message.CurrentPosture,
            .Priority = message.Priority,
            .GenerationId = message.GenerationId,
        }, nowMs);
        if (result == GestureRequestResult::Stale) {
            return {ProtocolApplyStatus::Stale, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == GestureRequestResult::Invalid
            || result == GestureRequestResult::NonMonotonic) {
            return {ProtocolApplyStatus::Invalid, std::move(interrupted), std::move(actionCompletion)};
        }
    }
    if (message.Type == "world.transition.target"
        && world_ != nullptr && worldActions_ != nullptr) {
        const WorldActionResult result = worldActions_->Start(WorldActionTarget{
            .ActionId = message.ActionId,
            .WorldId = message.WorldId,
            .ExpectedRevision = message.ExpectedRevision,
            .Room = message.Room,
            .Position = message.TargetPosition,
            .Activity = message.Activity,
            .InteractionTarget = message.InteractionTarget,
            .GenerationId = message.GenerationId,
            .TimeoutMs = message.ActionTimeoutMs,
        }, *world_, nowMs);
        if (result == WorldActionResult::Stale) {
            return {ProtocolApplyStatus::Stale, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == WorldActionResult::Invalid
            || result == WorldActionResult::NonMonotonic) {
            return {ProtocolApplyStatus::Invalid, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == WorldActionResult::Busy) {
            return {ProtocolApplyStatus::Backpressured, std::move(interrupted), std::move(actionCompletion)};
        }
    }
    if (message.Type == "character.action.result" && worldActions_ != nullptr) {
        const WorldActionResult result = worldActions_->ResolveAuthoritatively(
            message.ActionId, message.Result, nowMs);
        if (result == WorldActionResult::Stale) {
            return {ProtocolApplyStatus::Stale, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == WorldActionResult::Invalid
            || result == WorldActionResult::NonMonotonic) {
            return {ProtocolApplyStatus::Invalid, std::move(interrupted), std::move(actionCompletion)};
        }
    }

    for (const CharacterIntent& intent : translated.Intents) {
        const PublishResult result = character_.Intents().Publish(intent);
        if (result == PublishResult::Stale) {
            return {ProtocolApplyStatus::Stale, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == PublishResult::Invalid) {
            return {ProtocolApplyStatus::Invalid, std::move(interrupted), std::move(actionCompletion)};
        }
    }
    if (translated.Speech.has_value()) {
        const SpeechEnqueueResult result = playback_.Prepared(translated.Speech.value());
        if (result == SpeechEnqueueResult::Stale) {
            return {ProtocolApplyStatus::Stale, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == SpeechEnqueueResult::Invalid) {
            return {ProtocolApplyStatus::Invalid, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == SpeechEnqueueResult::Full) {
            return {ProtocolApplyStatus::Backpressured, std::move(interrupted), std::move(actionCompletion)};
        }
    }
    if (translated.SpeechEnd.has_value()) {
        const SpeechEnqueueResult result = playback_.SequenceEnded(
            translated.SpeechEnd.value(), nowMs);
        if (result == SpeechEnqueueResult::Stale) {
            return {ProtocolApplyStatus::Stale, std::move(interrupted), std::move(actionCompletion)};
        }
        if (result == SpeechEnqueueResult::Invalid) {
            return {ProtocolApplyStatus::Invalid, std::move(interrupted), std::move(actionCompletion)};
        }
    }
    return {ProtocolApplyStatus::Applied, std::move(interrupted), std::move(actionCompletion)};
}

bool ProtocolEventRuntime::CarriesAuthoritativeGeneration(const std::string& type) {
    return type == "cognition.request.started"
        || type == "generation.advanced"
        || type == "cognition.request.cancelled"
        || type == "cognition.response.completed"
        || type == "cognition.response.failed"
        || type == "character.state.target"
        || type == "emotion.target"
        || type == "attention.target"
        || type == "gesture.intent"
        || type == "world.transition.target"
        || type == "speech.prepared"
        || type == "speech.sequence.ended"
        || type == "speech.stopped";
}

} // namespace Gahyeon
