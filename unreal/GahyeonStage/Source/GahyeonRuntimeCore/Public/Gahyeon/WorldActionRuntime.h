#pragma once

#include "Gahyeon/WorldStateRuntime.h"

#include <deque>
#include <optional>
#include <string>
#include <unordered_set>

namespace Gahyeon {

enum class WorldActionPhase {
    Navigating,
    Interacting,
};

struct WorldActionTarget {
    std::string ActionId;
    std::string WorldId;
    Generation ExpectedRevision = 0;
    std::string Room;
    WorldPosition Position;
    std::string Activity;
    std::optional<std::string> InteractionTarget;
    std::optional<Generation> GenerationId;
    Millis TimeoutMs = 30'000;

    bool operator==(const WorldActionTarget&) const = default;
};

struct ActiveWorldAction {
    WorldActionTarget Target;
    WorldActionPhase Phase = WorldActionPhase::Navigating;
    Millis StartedAtMs = 0;
    Millis DeadlineAtMs = 0;
};

struct WorldActionCompletion {
    std::string ActionId;
    Generation ExpectedRevision = 0;
    std::string Outcome;
    std::string Reason;
    WorldPosition FinalPosition;
};

enum class WorldActionResult {
    Accepted,
    Duplicate,
    Busy,
    Stale,
    Invalid,
    NonMonotonic,
};

/** Local navigation/interaction execution; Backend World State commits only on completion. */
class GAHYEON_RUNTIME_CORE_API WorldActionRuntime {
public:
    explicit WorldActionRuntime(
        std::size_t completedHistoryCapacity = 256);

    WorldActionResult Start(
        WorldActionTarget target,
        const WorldStateRuntime& world,
        Millis nowMs);
    WorldActionResult NavigationArrived(
        const std::string& actionId,
        Millis nowMs);
    std::optional<WorldActionCompletion> Complete(
        const std::string& actionId,
        std::string outcome,
        std::string reason,
        WorldPosition finalPosition,
        Millis nowMs);
    std::optional<WorldActionCompletion> Advance(
        Millis nowMs,
        WorldPosition currentPosition);
    std::optional<WorldActionCompletion> SetGeneration(
        Generation generation,
        Millis nowMs,
        WorldPosition currentPosition);
    WorldActionResult ResolveAuthoritatively(
        const std::string& actionId,
        const std::string& result,
        Millis nowMs);

    Generation CurrentGeneration() const;
    const std::optional<ActiveWorldAction>& Active() const;

private:
    static bool ValidTarget(const WorldActionTarget& target);
    static bool ValidPosition(const WorldPosition& position);
    void RememberCompleted(const std::string& actionId);

    std::size_t completedHistoryCapacity_;
    Generation generation_ = 0;
    std::optional<Millis> lastObservedAtMs_;
    std::optional<ActiveWorldAction> active_;
    std::deque<std::string> completedOrder_;
    std::unordered_set<std::string> completedIds_;
};

} // namespace Gahyeon
