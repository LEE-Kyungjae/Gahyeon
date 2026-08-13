#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <unordered_map>

namespace Gahyeon {

using Millis = std::int64_t;
using Generation = std::uint64_t;

enum class IntentLayer {
    Reflex,
    Behavior,
    Cognition,
};

enum class IntentChannel {
    Phase,
    Attention,
    Gesture,
    Posture,
    Expression,
    Speech,
};

struct CharacterIntent {
    std::string Id;
    IntentLayer Layer = IntentLayer::Behavior;
    IntentChannel Channel = IntentChannel::Posture;
    std::optional<Generation> GenerationId;
    int Priority = 0;
    Millis CreatedAtMs = 0;
    std::optional<Millis> ExpiresAfterMs;
    std::string Value;
};

enum class PublishResult {
    Accepted,
    Stale,
    Invalid,
};

enum class GenerationSyncResult {
    Advanced,
    Unchanged,
    Stale,
};

struct ResolvedIntents {
    Generation CurrentGeneration = 0;
    std::map<IntentChannel, CharacterIntent> Channels;

    const CharacterIntent* Find(IntentChannel channel) const;
};

class GAHYEON_RUNTIME_CORE_API IntentRuntime {
public:
    Generation CurrentGeneration() const;
    Generation BeginGeneration();
    GenerationSyncResult SynchronizeGeneration(Generation generation);
    PublishResult Publish(CharacterIntent intent);
    ResolvedIntents Resolve(Millis nowMs) const;
    void Compact(Millis nowMs);

private:
    static bool IsExpired(const CharacterIntent& intent, Millis nowMs);
    static bool Wins(const CharacterIntent& candidate, const CharacterIntent& selected);

    Generation generation_ = 0;
    std::unordered_map<std::string, CharacterIntent> intents_;
};

} // namespace Gahyeon
