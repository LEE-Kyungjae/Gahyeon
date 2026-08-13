#include "Gahyeon/WorldStateRuntime.h"

#include <cmath>
#include <utility>

namespace Gahyeon {

WorldStateApplyResult WorldStateRuntime::ApplySnapshot(WorldStateSnapshot snapshot) {
    if (!IsValid(snapshot)) {
        return WorldStateApplyResult::Invalid;
    }
    if (!current_.has_value()) {
        current_ = std::move(snapshot);
        return WorldStateApplyResult::Applied;
    }
    if (snapshot.WorldId != current_->WorldId) {
        return WorldStateApplyResult::Conflict;
    }
    if (snapshot.Revision < current_->Revision) {
        return WorldStateApplyResult::Stale;
    }
    if (snapshot.Revision == current_->Revision) {
        return snapshot == current_.value()
            ? WorldStateApplyResult::Duplicate
            : WorldStateApplyResult::Conflict;
    }
    current_ = std::move(snapshot);
    return WorldStateApplyResult::Applied;
}

const std::optional<WorldStateSnapshot>& WorldStateRuntime::Current() const {
    return current_;
}

bool WorldStateRuntime::IsValid(const WorldStateSnapshot& snapshot) {
    if (snapshot.WorldId.empty() || snapshot.CurrentRoom.empty()
        || snapshot.Activity.empty() || snapshot.Outfit.empty()
        || snapshot.Emotion.empty()) {
        return false;
    }
    if (!std::isfinite(snapshot.Position.X)
        || !std::isfinite(snapshot.Position.Y)
        || !std::isfinite(snapshot.Position.Z)
        || !std::isfinite(snapshot.EmotionIntensity)
        || snapshot.EmotionIntensity < 0.0
        || snapshot.EmotionIntensity > 1.0) {
        return false;
    }
    return !snapshot.InteractionTarget.has_value()
        || !snapshot.InteractionTarget->empty();
}

} // namespace Gahyeon
