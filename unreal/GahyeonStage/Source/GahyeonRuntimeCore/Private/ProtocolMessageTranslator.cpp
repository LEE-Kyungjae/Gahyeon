#include "Gahyeon/ProtocolMessageTranslator.h"

#include <cmath>

namespace Gahyeon {

TranslationResult ProtocolMessageTranslator::Translate(
    const ProtocolMessage& message,
    Millis nowMs) const {
    if (message.Type == "world.snapshot") {
        if (!message.Snapshot.has_value()) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        WorldStateRuntime validator;
        if (validator.ApplySnapshot(message.Snapshot.value())
                != WorldStateApplyResult::Applied) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {TranslationStatus::Translated, {}, std::nullopt, std::nullopt, std::nullopt};
    }
    if (message.Type == "generation.advanced") {
        if (!ValidGeneration(message)) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {TranslationStatus::Ignored, {}, std::nullopt, std::nullopt, std::nullopt};
    }
    if (message.Type == "cognition.response.completed"
        || message.Type == "cognition.request.started"
        || message.Type == "cognition.request.cancelled") {
        // Cognition events do not imply playable speech or a frame-level transition.
        return {TranslationStatus::Ignored, {}, std::nullopt, std::nullopt, std::nullopt};
    }

    if (message.Type == "speech.prepared") {
        if (!ValidGeneration(message) || message.UtteranceId.empty()
            || !ValidVisemes(message.Visemes)) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {
            TranslationStatus::Translated,
            {},
            PreparedSpeech{
                .GenerationId = message.GenerationId.value(),
                .UtteranceId = message.UtteranceId,
                .UtteranceIndex = message.UtteranceIndex,
                .SegmentIndex = message.SegmentIndex,
                .FinalSegment = message.FinalSegment,
                .AudioUrl = message.AudioUrl,
                .MimeType = message.MimeType,
                .Visemes = message.Visemes,
            },
            std::nullopt,
            std::nullopt,
        };
    }

    if (message.Type == "speech.sequence.ended") {
        if (!ValidGeneration(message) || message.UtteranceCount < 0
            || (message.Outcome != "completed" && message.Outcome != "failed")) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {
            TranslationStatus::Translated,
            {},
            std::nullopt,
            SpeechSequenceEnd{
                .GenerationId = message.GenerationId.value(),
                .UtteranceCount = message.UtteranceCount,
                .Outcome = message.Outcome,
            },
            std::nullopt,
        };
    }

    if (message.Type == "speech.stopped") {
        if (!ValidGeneration(message)) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        std::vector<CharacterIntent> intents;
        intents.push_back(CharacterIntent{
            .Id = "conversation.phase",
            .Layer = IntentLayer::Behavior,
            .Channel = IntentChannel::Phase,
            .GenerationId = message.GenerationId,
            .Priority = 10,
            .CreatedAtMs = nowMs,
            .ExpiresAfterMs = std::nullopt,
            .Value = "idle",
        });
        intents.push_back(CharacterIntent{
            .Id = "conversation.speech",
            .Layer = IntentLayer::Behavior,
            .Channel = IntentChannel::Speech,
            .GenerationId = message.GenerationId,
            .Priority = 10,
            .CreatedAtMs = nowMs,
            .ExpiresAfterMs = std::nullopt,
            .Value = "",
        });
        return {TranslationStatus::Translated, std::move(intents), std::nullopt, std::nullopt, std::nullopt};
    }

    if (message.Type == "emotion.target") {
        std::map<std::string, double> dimensions = message.EmotionDimensions;
        if (dimensions.empty() && !message.Semantic.empty()) {
            dimensions.emplace(message.Semantic, message.Intensity);
        }
        EmotionTarget emotion{
            .Dimensions = dimensions,
            .Valence = message.Valence,
            .Arousal = message.Arousal,
            .Dominance = message.Dominance,
            .BlendMs = message.BlendMs,
            .HoldMs = message.HoldMs,
        };
        EmotionRuntime validator;
        if (validator.ApplyTarget(emotion, nowMs) != EmotionApplyResult::Applied) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {TranslationStatus::Translated, {CharacterIntent{
            .Id = "emotion.target",
            .Layer = IntentLayer::Behavior,
            .Channel = IntentChannel::Expression,
            .GenerationId = message.GenerationId,
            .Priority = message.Priority,
            .CreatedAtMs = nowMs,
            .ExpiresAfterMs = message.ExpiresAfterMs,
            .Value = dimensions.begin()->first,
        }}, std::nullopt, std::nullopt, std::move(emotion)};
    }

    if (message.Type == "attention.target") {
        if (message.Semantic.empty()) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {TranslationStatus::Translated, {CharacterIntent{
            .Id = "attention.target",
            .Layer = IntentLayer::Behavior,
            .Channel = IntentChannel::Attention,
            .GenerationId = message.GenerationId,
            .Priority = message.Priority,
            .CreatedAtMs = nowMs,
            .ExpiresAfterMs = message.ExpiresAfterMs,
            .Value = message.Semantic,
        }}, std::nullopt, std::nullopt, std::nullopt};
    }

    if (message.Type == "gesture.intent") {
        if (message.Semantic.empty() || !std::isfinite(message.Intensity)
            || message.Intensity < 0.0 || message.Intensity > 1.0) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {TranslationStatus::Translated, {CharacterIntent{
            .Id = "gesture.intent",
            .Layer = IntentLayer::Behavior,
            .Channel = IntentChannel::Gesture,
            .GenerationId = message.GenerationId,
            .Priority = message.Priority,
            .CreatedAtMs = nowMs,
            .ExpiresAfterMs = message.ExpiresAfterMs,
            .Value = message.Semantic,
        }}, std::nullopt, std::nullopt, std::nullopt};
    }

    if (message.Type == "world.transition.target") {
        if (message.ActionId.empty() || message.WorldId.empty() || message.Room.empty()
            || message.Activity.empty() || !std::isfinite(message.TargetPosition.X)
            || !std::isfinite(message.TargetPosition.Y)
            || !std::isfinite(message.TargetPosition.Z)
            || message.ActionTimeoutMs <= 0 || message.ActionTimeoutMs > 600'000) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {TranslationStatus::Translated, {CharacterIntent{
            .Id = "world.action.phase",
            .Layer = IntentLayer::Behavior,
            .Channel = IntentChannel::Phase,
            .GenerationId = message.GenerationId,
            .Priority = message.Priority,
            .CreatedAtMs = nowMs,
            .ExpiresAfterMs = message.ExpiresAfterMs,
            .Value = "executing_action",
        }}, std::nullopt, std::nullopt, std::nullopt};
    }

    if (message.Type == "character.action.result") {
        if (message.ActionId.empty()
            || (message.Result != "committed" && message.Result != "recorded_failure"
                && message.Result != "duplicate" && message.Result != "conflict")) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {TranslationStatus::Translated, {CharacterIntent{
            .Id = "world.action.phase",
            .Layer = IntentLayer::Behavior,
            .Channel = IntentChannel::Phase,
            .GenerationId = message.GenerationId,
            .Priority = message.Priority,
            .CreatedAtMs = nowMs,
            .ExpiresAfterMs = message.ExpiresAfterMs,
            .Value = "idle",
        }}, std::nullopt, std::nullopt, std::nullopt};
    }

    if (message.Type == "character.state.target") {
        if (!ValidGeneration(message) || message.Semantic.empty()) {
            return {TranslationStatus::Invalid, {}, std::nullopt, std::nullopt, std::nullopt};
        }
        return {TranslationStatus::Translated, {CharacterIntent{
            .Id = "conversation.phase",
            .Layer = IntentLayer::Cognition,
            .Channel = IntentChannel::Phase,
            .GenerationId = message.GenerationId,
            .Priority = message.Priority,
            .CreatedAtMs = nowMs,
            .ExpiresAfterMs = message.ExpiresAfterMs,
            .Value = message.Semantic,
        }}, std::nullopt, std::nullopt, std::nullopt};
    }

    return {TranslationStatus::Ignored, {}, std::nullopt, std::nullopt, std::nullopt};
}

bool ProtocolMessageTranslator::ValidGeneration(const ProtocolMessage& message) {
    return message.GenerationId.has_value();
}

bool ProtocolMessageTranslator::ValidVisemes(const std::vector<VisemeCue>& visemes) {
    if (visemes.size() > 256) return false;
    Millis previousAt = -1;
    for (const VisemeCue& cue : visemes) {
        if (cue.Semantic.empty() || cue.AtMs < 0 || cue.AtMs < previousAt
            || cue.DurationMs <= 0 || !std::isfinite(cue.Weight)
            || cue.Weight <= 0.0 || cue.Weight > 1.0) {
            return false;
        }
        previousAt = cue.AtMs;
    }
    return true;
}

} // namespace Gahyeon
