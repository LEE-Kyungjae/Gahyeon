#pragma once

#include "Gahyeon/WorldActionCompletionOutbox.h"

#include <deque>
#include <optional>
#include <string>

namespace Gahyeon {

struct WorldActionAcknowledgement {
    std::string ActionId;
    std::string Result;
    bool Terminal = false;
    bool Accepted = false;
    bool Duplicate = false;
};

struct RejectedWorldActionCompletion {
    WorldActionCompletion Completion;
    std::string BackendResult;
};

struct WorldActionBridgeSnapshot {
    std::vector<WorldActionOutboxSnapshotEntry> Pending;
    std::vector<RejectedWorldActionCompletion> Rejections;
};

enum class WorldActionAckResult {
    Acknowledged,
    Rejected,
    Deferred,
    Unknown,
    Invalid,
};

/** Normalized boundary used by an Unreal WebSocket adapter without frame-level coupling. */
class GAHYEON_RUNTIME_CORE_API WorldActionCommandBridge {
public:
    explicit WorldActionCommandBridge(
        std::size_t outboxCapacity = 64,
        std::size_t rejectionCapacity = 64);

    CompletionOutboxResult Queue(WorldActionCompletion completion, Millis nowMs);
    std::optional<PendingWorldActionCompletion> NextCommand(Millis nowMs) const;
    CompletionOutboxResult CommandSent(const std::string& actionId, Millis nowMs);
    WorldActionAckResult ApplyAcknowledgement(WorldActionAcknowledgement acknowledgement);
    WorldActionBridgeSnapshot Snapshot(Millis nowMs) const;
    CompletionOutboxResult Restore(WorldActionBridgeSnapshot snapshot, Millis nowMs);

    const WorldActionCompletionOutbox& Outbox() const;
    const std::deque<RejectedWorldActionCompletion>& Rejections() const;

private:
    static bool ValidResult(const std::string& result);

    WorldActionCompletionOutbox outbox_;
    std::size_t rejectionCapacity_;
    std::deque<RejectedWorldActionCompletion> rejections_;
};

} // namespace Gahyeon
