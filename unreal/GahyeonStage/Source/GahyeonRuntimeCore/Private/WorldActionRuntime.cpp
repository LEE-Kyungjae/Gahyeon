#include "Gahyeon/WorldActionRuntime.h"

#include <cmath>
#include <limits>
#include <stdexcept>
#include <utility>

namespace Gahyeon {

WorldActionRuntime::WorldActionRuntime(std::size_t completedHistoryCapacity)
    : completedHistoryCapacity_(completedHistoryCapacity) {
    if (completedHistoryCapacity_ == 0) {
        throw std::invalid_argument("completed action history capacity must be positive");
    }
}

WorldActionResult WorldActionRuntime::Start(
    WorldActionTarget target,
    const WorldStateRuntime& world,
    Millis nowMs) {
    if (nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value())) {
        return WorldActionResult::NonMonotonic;
    }
    lastObservedAtMs_ = nowMs;
    if (!ValidTarget(target) || !world.Current().has_value()
        || world.Current()->WorldId != target.WorldId) {
        return WorldActionResult::Invalid;
    }
    if (target.ExpectedRevision < world.Current()->Revision
        || (target.GenerationId.has_value()
            && target.GenerationId.value() < generation_)) {
        return WorldActionResult::Stale;
    }
    if (target.ExpectedRevision != world.Current()->Revision
        || (target.GenerationId.has_value()
            && target.GenerationId.value() > generation_)) {
        return WorldActionResult::Invalid;
    }
    if (completedIds_.contains(target.ActionId)) return WorldActionResult::Duplicate;
    if (active_.has_value()) {
        if (active_->Target.ActionId == target.ActionId) {
            return active_->Target == target
                ? WorldActionResult::Duplicate
                : WorldActionResult::Invalid;
        }
        return WorldActionResult::Busy;
    }
    const Millis deadline = nowMs > std::numeric_limits<Millis>::max() - target.TimeoutMs
        ? std::numeric_limits<Millis>::max()
        : nowMs + target.TimeoutMs;
    active_ = ActiveWorldAction{
        .Target = std::move(target),
        .Phase = WorldActionPhase::Navigating,
        .StartedAtMs = nowMs,
        .DeadlineAtMs = deadline,
    };
    return WorldActionResult::Accepted;
}

WorldActionResult WorldActionRuntime::NavigationArrived(
    const std::string& actionId,
    Millis nowMs) {
    if (nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value())) {
        return WorldActionResult::NonMonotonic;
    }
    lastObservedAtMs_ = nowMs;
    if (completedIds_.contains(actionId)) return WorldActionResult::Duplicate;
    if (!active_.has_value() || actionId != active_->Target.ActionId) {
        return WorldActionResult::Stale;
    }
    if (active_->Phase == WorldActionPhase::Interacting) {
        return WorldActionResult::Duplicate;
    }
    active_->Phase = WorldActionPhase::Interacting;
    return WorldActionResult::Accepted;
}

std::optional<WorldActionCompletion> WorldActionRuntime::Complete(
    const std::string& actionId,
    std::string outcome,
    std::string reason,
    WorldPosition finalPosition,
    Millis nowMs) {
    if (nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value()) || !ValidPosition(finalPosition)
        || (outcome != "completed" && outcome != "failed" && outcome != "cancelled")) {
        return std::nullopt;
    }
    lastObservedAtMs_ = nowMs;
    if (!active_.has_value() || actionId != active_->Target.ActionId
        || completedIds_.contains(actionId)) {
        return std::nullopt;
    }
    if (outcome == "completed" && active_->Phase != WorldActionPhase::Interacting) {
        return std::nullopt;
    }
    WorldActionCompletion completion{
        .ActionId = actionId,
        .ExpectedRevision = active_->Target.ExpectedRevision,
        .Outcome = std::move(outcome),
        .Reason = std::move(reason),
        .FinalPosition = finalPosition,
    };
    RememberCompleted(actionId);
    active_.reset();
    return completion;
}

std::optional<WorldActionCompletion> WorldActionRuntime::Advance(
    Millis nowMs,
    WorldPosition currentPosition) {
    if (!active_.has_value() || nowMs <= active_->DeadlineAtMs) return std::nullopt;
    const std::string actionId = active_->Target.ActionId;
    return Complete(
        actionId,
        "failed",
        "timeout",
        currentPosition,
        nowMs);
}

std::optional<WorldActionCompletion> WorldActionRuntime::SetGeneration(
    Generation generation,
    Millis nowMs,
    WorldPosition currentPosition) {
    if (generation <= generation_) return std::nullopt;
    if (nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value())) {
        return std::nullopt;
    }
    if (!active_.has_value() || !active_->Target.GenerationId.has_value()
        || active_->Target.GenerationId.value() >= generation) {
        generation_ = generation;
        return std::nullopt;
    }
    const std::string actionId = active_->Target.ActionId;
    auto completion = Complete(
        actionId,
        "cancelled",
        "superseded_generation",
        currentPosition,
        nowMs);
    generation_ = generation;
    return completion;
}

WorldActionResult WorldActionRuntime::ResolveAuthoritatively(
    const std::string& actionId,
    const std::string& result,
    Millis nowMs) {
    if (actionId.empty() || nowMs < 0 || (lastObservedAtMs_.has_value()
            && nowMs < lastObservedAtMs_.value())
        || (result != "committed" && result != "recorded_failure"
            && result != "duplicate" && result != "conflict")) {
        return WorldActionResult::Invalid;
    }
    lastObservedAtMs_ = nowMs;
    if (completedIds_.contains(actionId)) return WorldActionResult::Duplicate;
    if (!active_.has_value() || active_->Target.ActionId != actionId) {
        return WorldActionResult::Stale;
    }
    RememberCompleted(actionId);
    active_.reset();
    return WorldActionResult::Accepted;
}

Generation WorldActionRuntime::CurrentGeneration() const { return generation_; }

const std::optional<ActiveWorldAction>& WorldActionRuntime::Active() const { return active_; }

bool WorldActionRuntime::ValidTarget(const WorldActionTarget& target) {
    return !target.ActionId.empty() && !target.WorldId.empty() && !target.Room.empty()
        && !target.Activity.empty() && ValidPosition(target.Position)
        && target.TimeoutMs > 0 && target.TimeoutMs <= 600'000
        && (!target.InteractionTarget.has_value()
            || !target.InteractionTarget->empty());
}

bool WorldActionRuntime::ValidPosition(const WorldPosition& position) {
    return std::isfinite(position.X)
        && std::isfinite(position.Y)
        && std::isfinite(position.Z);
}

void WorldActionRuntime::RememberCompleted(const std::string& actionId) {
    completedIds_.insert(actionId);
    completedOrder_.push_back(actionId);
    while (completedOrder_.size() > completedHistoryCapacity_) {
        completedIds_.erase(completedOrder_.front());
        completedOrder_.pop_front();
    }
}

} // namespace Gahyeon
