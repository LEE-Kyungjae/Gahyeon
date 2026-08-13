#pragma once

#include "Gahyeon/ProtocolIngressMailbox.h"
#include "Gahyeon/ReplayCursorRuntime.h"
#include "Gahyeon/WorldActionCommandBridge.h"

#include <optional>
#include <string>
#include <unordered_set>

namespace Gahyeon {

enum class NetworkIngressDirective {
    Accepted,
    DroppedEphemeral,
    ReconnectRequired,
    Invalid,
};

/** Called only by the socket thread; it never touches game-thread runtime state. */
class GAHYEON_RUNTIME_CORE_API ProtocolNetworkIngressAdapter {
public:
    explicit ProtocolNetworkIngressAdapter(ProtocolIngressMailbox& ingress);
    NetworkIngressDirective OnEvent(ProtocolIngressEvent event);

private:
    ProtocolIngressMailbox& ingress_;
};

enum class OutboundProtocolCommandType { ClientAck, ActionCompletion };

struct OutboundProtocolCommand {
    OutboundProtocolCommandType Type = OutboundProtocolCommandType::ClientAck;
    Generation Sequence = 0;
    std::optional<WorldActionCompletion> Completion;
};

enum class NetworkEgressResult { Sent, Invalid, Stale, Backpressured };

/** Game-thread scheduler for non-blocking socket sends; JSON stays in the Unreal adapter. */
class GAHYEON_RUNTIME_CORE_API ProtocolNetworkEgressRuntime {
public:
    ProtocolNetworkEgressRuntime(
        ReplayCursorRuntime& cursor,
        WorldActionCommandBridge& actions);

    std::optional<OutboundProtocolCommand> Next(Millis nowMs) const;
    bool PersistenceConfirmed(Generation sequence);
    bool ActionPersistenceConfirmed(const std::string& actionId);
    NetworkEgressResult MarkSent(const OutboundProtocolCommand& command, Millis nowMs);
    WorldActionAckResult ActionAcknowledged(WorldActionAcknowledgement acknowledgement);

private:
    ReplayCursorRuntime& cursor_;
    WorldActionCommandBridge& actions_;
    Generation confirmedPersistedSequence_ = 0;
    std::unordered_set<std::string> confirmedActionIds_;
};

} // namespace Gahyeon
