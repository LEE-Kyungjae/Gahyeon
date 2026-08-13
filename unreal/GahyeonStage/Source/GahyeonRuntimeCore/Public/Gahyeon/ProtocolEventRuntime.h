#pragma once

#include "Gahyeon/ProtocolMessageTranslator.h"
#include "Gahyeon/SpeechPlaybackCoordinator.h"
#include "Gahyeon/GestureRuntime.h"

#include <optional>
#include <string>

namespace Gahyeon {

enum class ProtocolApplyStatus {
    Applied,
    Ignored,
    Invalid,
    Stale,
    Backpressured,
};

struct ProtocolApplyResult {
    ProtocolApplyStatus Status = ProtocolApplyStatus::Ignored;
    std::optional<std::string> InterruptedUtteranceId;
    std::optional<WorldActionCompletion> ActionCompletion;
};

/** Applies normalized Backend messages atomically on the game thread. */
class GAHYEON_RUNTIME_CORE_API ProtocolEventRuntime {
public:
    ProtocolEventRuntime(
        RealtimeCharacterCoordinator& character,
        SpeechPlaybackCoordinator& playback,
        EmotionRuntime* emotion = nullptr,
        GestureRuntime* gestures = nullptr,
        WorldStateRuntime* world = nullptr,
        WorldActionRuntime* worldActions = nullptr);

    ProtocolApplyResult Apply(const ProtocolMessage& message, Millis nowMs);

private:
    static bool CarriesAuthoritativeGeneration(const std::string& type);

    RealtimeCharacterCoordinator& character_;
    SpeechPlaybackCoordinator& playback_;
    ProtocolMessageTranslator translator_;
    EmotionRuntime* emotion_ = nullptr;
    GestureRuntime* gestures_ = nullptr;
    WorldStateRuntime* world_ = nullptr;
    WorldActionRuntime* worldActions_ = nullptr;
};

} // namespace Gahyeon
