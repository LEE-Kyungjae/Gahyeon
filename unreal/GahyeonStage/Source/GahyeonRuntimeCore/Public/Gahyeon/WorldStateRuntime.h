#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <optional>
#include <string>

namespace Gahyeon {

struct WorldPosition {
    double X = 0.0;
    double Y = 0.0;
    double Z = 0.0;

    bool operator==(const WorldPosition&) const = default;
};

struct WorldStateSnapshot {
    std::string WorldId;
    Generation Revision = 0;
    std::string CurrentRoom;
    WorldPosition Position;
    std::string Activity;
    std::string Outfit;
    std::string Emotion;
    double EmotionIntensity = 0.0;
    std::optional<std::string> InteractionTarget;

    bool operator==(const WorldStateSnapshot&) const = default;
};

enum class WorldStateApplyResult {
    Applied,
    Duplicate,
    Stale,
    Conflict,
    Invalid,
};

/** Game-thread-owned, revision-aware presentation copy of authoritative World State. */
class GAHYEON_RUNTIME_CORE_API WorldStateRuntime {
public:
    WorldStateApplyResult ApplySnapshot(WorldStateSnapshot snapshot);
    const std::optional<WorldStateSnapshot>& Current() const;

private:
    static bool IsValid(const WorldStateSnapshot& snapshot);

    std::optional<WorldStateSnapshot> current_;
};

} // namespace Gahyeon
