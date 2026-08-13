#pragma once

#include "Gahyeon/WorldActionRuntime.h"

#include <deque>
#include <optional>
#include <string>
#include <unordered_set>
#include <vector>

namespace Gahyeon {

struct PendingWorldActionCompletion {
    WorldActionCompletion Completion;
    int Attempts = 0;
    Millis NextAttemptAtMs = 0;
};

struct WorldActionOutboxSnapshotEntry {
    WorldActionCompletion Completion;
    int Attempts = 0;
    Millis RetryAfterMs = 0;
};

enum class CompletionOutboxResult { Accepted, Duplicate, Full, Invalid, NonMonotonic };

/** Keeps completion commands until Backend acknowledgement across disconnects. */
class GAHYEON_RUNTIME_CORE_API WorldActionCompletionOutbox {
public:
    explicit WorldActionCompletionOutbox(
        std::size_t capacity = 64,
        Millis initialRetryMs = 250,
        Millis maximumRetryMs = 5'000);

    CompletionOutboxResult Enqueue(WorldActionCompletion completion, Millis nowMs);
    std::optional<PendingWorldActionCompletion> Due(Millis nowMs) const;
    CompletionOutboxResult MarkAttempt(const std::string& actionId, Millis nowMs);
    std::optional<WorldActionCompletion> Find(const std::string& actionId) const;
    bool Acknowledge(const std::string& actionId);
    std::vector<WorldActionOutboxSnapshotEntry> Snapshot(Millis nowMs) const;
    CompletionOutboxResult Restore(
        std::vector<WorldActionOutboxSnapshotEntry> entries,
        Millis nowMs);
    std::size_t Size() const;
    bool Empty() const;

private:
    static bool Valid(const WorldActionCompletion& completion);
    Millis RetryDelay(int attempts) const;

    std::size_t capacity_;
    Millis initialRetryMs_;
    Millis maximumRetryMs_;
    std::optional<Millis> lastObservedAtMs_;
    std::deque<PendingWorldActionCompletion> pending_;
    std::unordered_set<std::string> actionIds_;
};

} // namespace Gahyeon
