#pragma once

#include "Gahyeon/IntentRuntime.h"
#include "Gahyeon/SpeechQueue.h"
#include "Gahyeon/EmotionRuntime.h"
#include "Gahyeon/WorldActionRuntime.h"

#include <optional>
#include <map>
#include <string>
#include <vector>

namespace Gahyeon {

/** Engine JSON adapters normalize wire payloads into this semantic message. */
struct ProtocolMessage {
    std::string Type;
    std::optional<Generation> GenerationId;
    std::string Semantic;
    std::string UtteranceId;
    std::string AudioUrl;
    std::string MimeType;
    double Intensity = 0.0;
    int Priority = 0;
    std::optional<Millis> ExpiresAfterMs;
    int SegmentIndex = 0;
    int UtteranceIndex = 0;
    int UtteranceCount = 0;
    bool FinalSegment = false;
    std::string Outcome;
    std::string Result;
    std::vector<VisemeCue> Visemes;
    std::map<std::string, double> EmotionDimensions;
    std::optional<double> Valence;
    std::optional<double> Arousal;
    std::optional<double> Dominance;
    Millis BlendMs = 250;
    std::optional<Millis> HoldMs;
    std::string CurrentPosture;
    std::string ActionId;
    std::string WorldId;
    Generation ExpectedRevision = 0;
    std::string Room;
    WorldPosition TargetPosition;
    std::string Activity;
    std::optional<std::string> InteractionTarget;
    Millis ActionTimeoutMs = 30'000;
    std::optional<WorldStateSnapshot> Snapshot;
};

enum class TranslationStatus {
    Translated,
    Ignored,
    Invalid,
};

struct TranslationResult {
    TranslationStatus Status = TranslationStatus::Ignored;
    std::vector<CharacterIntent> Intents;
    std::optional<PreparedSpeech> Speech;
    std::optional<SpeechSequenceEnd> SpeechEnd;
    std::optional<EmotionTarget> Emotion;
};

class GAHYEON_RUNTIME_CORE_API ProtocolMessageTranslator {
public:
    TranslationResult Translate(const ProtocolMessage& message, Millis nowMs) const;

private:
    static bool ValidGeneration(const ProtocolMessage& message);
    static bool ValidVisemes(const std::vector<VisemeCue>& visemes);
};

} // namespace Gahyeon
