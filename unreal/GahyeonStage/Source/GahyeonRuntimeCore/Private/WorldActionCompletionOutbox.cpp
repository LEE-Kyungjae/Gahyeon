#include "Gahyeon/WorldActionCompletionOutbox.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <utility>

namespace Gahyeon {

WorldActionCompletionOutbox::WorldActionCompletionOutbox(
    std::size_t capacity, Millis initialRetryMs, Millis maximumRetryMs)
    : capacity_(capacity), initialRetryMs_(initialRetryMs),
      maximumRetryMs_(maximumRetryMs) {
    if (capacity_ == 0 || initialRetryMs_ <= 0 || maximumRetryMs_ < initialRetryMs_) {
        capacity_ = 64;
        initialRetryMs_ = 250;
        maximumRetryMs_ = 5'000;
    }
}

CompletionOutboxResult WorldActionCompletionOutbox::Enqueue(
    WorldActionCompletion completion, Millis nowMs) {
    if (nowMs < 0 || !Valid(completion)) return CompletionOutboxResult::Invalid;
    if (lastObservedAtMs_.has_value() && nowMs < lastObservedAtMs_.value()) {
        return CompletionOutboxResult::NonMonotonic;
    }
    lastObservedAtMs_ = nowMs;
    if (actionIds_.contains(completion.ActionId)) return CompletionOutboxResult::Duplicate;
    if (pending_.size() >= capacity_) return CompletionOutboxResult::Full;
    actionIds_.insert(completion.ActionId);
    pending_.push_back({std::move(completion), 0, nowMs});
    return CompletionOutboxResult::Accepted;
}

std::optional<PendingWorldActionCompletion> WorldActionCompletionOutbox::Due(
    Millis nowMs) const {
    if (nowMs < 0) return std::nullopt;
    for (const auto& command : pending_) {
        if (command.NextAttemptAtMs <= nowMs) return command;
    }
    return std::nullopt;
}

CompletionOutboxResult WorldActionCompletionOutbox::MarkAttempt(
    const std::string& actionId, Millis nowMs) {
    if (actionId.empty() || nowMs < 0) return CompletionOutboxResult::Invalid;
    if (lastObservedAtMs_.has_value() && nowMs < lastObservedAtMs_.value()) {
        return CompletionOutboxResult::NonMonotonic;
    }
    lastObservedAtMs_ = nowMs;
    auto found = std::find_if(pending_.begin(), pending_.end(),
        [&](const auto& command) { return command.Completion.ActionId == actionId; });
    if (found == pending_.end() || found->NextAttemptAtMs > nowMs) {
        return CompletionOutboxResult::Invalid;
    }
    ++found->Attempts;
    const Millis delay = RetryDelay(found->Attempts);
    found->NextAttemptAtMs = nowMs > std::numeric_limits<Millis>::max() - delay
        ? std::numeric_limits<Millis>::max() : nowMs + delay;
    return CompletionOutboxResult::Accepted;
}

std::optional<WorldActionCompletion> WorldActionCompletionOutbox::Find(
    const std::string& actionId) const {
    auto found = std::find_if(pending_.begin(), pending_.end(),
        [&](const auto& command) { return command.Completion.ActionId == actionId; });
    return found == pending_.end()
        ? std::nullopt
        : std::optional<WorldActionCompletion>(found->Completion);
}

bool WorldActionCompletionOutbox::Acknowledge(const std::string& actionId) {
    auto found = std::find_if(pending_.begin(), pending_.end(),
        [&](const auto& command) { return command.Completion.ActionId == actionId; });
    if (found == pending_.end()) return false;
    actionIds_.erase(actionId);
    pending_.erase(found);
    return true;
}

std::vector<WorldActionOutboxSnapshotEntry> WorldActionCompletionOutbox::Snapshot(
    Millis nowMs) const {
    std::vector<WorldActionOutboxSnapshotEntry> result;
    if (nowMs < 0) return result;
    result.reserve(pending_.size());
    for (const auto& pending : pending_) {
        result.push_back({
            pending.Completion,
            pending.Attempts,
            std::max<Millis>(0, pending.NextAttemptAtMs - nowMs),
        });
    }
    return result;
}

CompletionOutboxResult WorldActionCompletionOutbox::Restore(
    std::vector<WorldActionOutboxSnapshotEntry> entries,
    Millis nowMs) {
    if (nowMs < 0) return CompletionOutboxResult::Invalid;
    if (entries.size() > capacity_) return CompletionOutboxResult::Full;
    std::unordered_set<std::string> restoredIds;
    for (const auto& entry : entries) {
        if (!Valid(entry.Completion) || entry.Attempts < 0 || entry.RetryAfterMs < 0
            || !restoredIds.insert(entry.Completion.ActionId).second) {
            return CompletionOutboxResult::Invalid;
        }
    }
    pending_.clear();
    actionIds_.clear();
    for (auto& entry : entries) {
        const Millis next = nowMs > std::numeric_limits<Millis>::max() - entry.RetryAfterMs
            ? std::numeric_limits<Millis>::max() : nowMs + entry.RetryAfterMs;
        actionIds_.insert(entry.Completion.ActionId);
        pending_.push_back({std::move(entry.Completion), entry.Attempts, next});
    }
    lastObservedAtMs_ = nowMs;
    return CompletionOutboxResult::Accepted;
}

std::size_t WorldActionCompletionOutbox::Size() const { return pending_.size(); }
bool WorldActionCompletionOutbox::Empty() const { return pending_.empty(); }

bool WorldActionCompletionOutbox::Valid(const WorldActionCompletion& completion) {
    const auto& position = completion.FinalPosition;
    return !completion.ActionId.empty()
        && (completion.Outcome == "completed" || completion.Outcome == "failed"
            || completion.Outcome == "cancelled")
        && std::isfinite(position.X) && std::isfinite(position.Y)
        && std::isfinite(position.Z);
}

Millis WorldActionCompletionOutbox::RetryDelay(int attempts) const {
    Millis delay = initialRetryMs_;
    for (int index = 1; index < attempts && delay < maximumRetryMs_; ++index) {
        delay = std::min(maximumRetryMs_, delay > maximumRetryMs_ / 2
            ? maximumRetryMs_ : delay * 2);
    }
    return delay;
}

} // namespace Gahyeon
