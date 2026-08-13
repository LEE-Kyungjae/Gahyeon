#include "Gahyeon/WorldActionCommandBridge.h"

#include <utility>
#include <unordered_set>

namespace Gahyeon {

WorldActionCommandBridge::WorldActionCommandBridge(
    std::size_t outboxCapacity,
    std::size_t rejectionCapacity)
    : outbox_(outboxCapacity), rejectionCapacity_(rejectionCapacity) {
    if (rejectionCapacity_ == 0) rejectionCapacity_ = 64;
}

CompletionOutboxResult WorldActionCommandBridge::Queue(
    WorldActionCompletion completion,
    Millis nowMs) {
    return outbox_.Enqueue(std::move(completion), nowMs);
}

std::optional<PendingWorldActionCompletion> WorldActionCommandBridge::NextCommand(
    Millis nowMs) const {
    return outbox_.Due(nowMs);
}

CompletionOutboxResult WorldActionCommandBridge::CommandSent(
    const std::string& actionId,
    Millis nowMs) {
    return outbox_.MarkAttempt(actionId, nowMs);
}

WorldActionAckResult WorldActionCommandBridge::ApplyAcknowledgement(
    WorldActionAcknowledgement acknowledgement) {
    if (acknowledgement.ActionId.empty() || !ValidResult(acknowledgement.Result)) {
        return WorldActionAckResult::Invalid;
    }
    auto completion = outbox_.Find(acknowledgement.ActionId);
    if (!completion.has_value()) return WorldActionAckResult::Unknown;
    if (!acknowledgement.Terminal) return WorldActionAckResult::Deferred;
    if (!outbox_.Acknowledge(acknowledgement.ActionId)) {
        return WorldActionAckResult::Unknown;
    }
    if (acknowledgement.Accepted || acknowledgement.Duplicate) {
        return WorldActionAckResult::Acknowledged;
    }
    rejections_.push_back({std::move(completion.value()), acknowledgement.Result});
    while (rejections_.size() > rejectionCapacity_) rejections_.pop_front();
    return WorldActionAckResult::Rejected;
}

WorldActionBridgeSnapshot WorldActionCommandBridge::Snapshot(Millis nowMs) const {
    return {outbox_.Snapshot(nowMs), {rejections_.begin(), rejections_.end()}};
}

CompletionOutboxResult WorldActionCommandBridge::Restore(
    WorldActionBridgeSnapshot snapshot,
    Millis nowMs) {
    // Preserve the configured outbox capacity by rejecting through the current outbox first.
    WorldActionCompletionOutbox restored = outbox_;
    const CompletionOutboxResult result = restored.Restore(std::move(snapshot.Pending), nowMs);
    if (result != CompletionOutboxResult::Accepted) return result;
    if (snapshot.Rejections.size() > rejectionCapacity_) return CompletionOutboxResult::Full;
    std::unordered_set<std::string> rejectedIds;
    for (const auto& rejected : snapshot.Rejections) {
        if (rejected.Completion.ActionId.empty() || !ValidResult(rejected.BackendResult)
            || restored.Find(rejected.Completion.ActionId).has_value()
            || !rejectedIds.insert(rejected.Completion.ActionId).second) {
            return CompletionOutboxResult::Invalid;
        }
    }
    outbox_ = std::move(restored);
    rejections_ = {snapshot.Rejections.begin(), snapshot.Rejections.end()};
    return CompletionOutboxResult::Accepted;
}

const WorldActionCompletionOutbox& WorldActionCommandBridge::Outbox() const {
    return outbox_;
}

const std::deque<RejectedWorldActionCompletion>&
WorldActionCommandBridge::Rejections() const {
    return rejections_;
}

bool WorldActionCommandBridge::ValidResult(const std::string& result) {
    return result == "committed" || result == "recorded_failure"
        || result == "duplicate" || result == "stale"
        || result == "conflict";
}

} // namespace Gahyeon
