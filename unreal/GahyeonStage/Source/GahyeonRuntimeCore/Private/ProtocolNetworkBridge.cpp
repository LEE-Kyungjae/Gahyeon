#include "Gahyeon/ProtocolNetworkBridge.h"

#include <utility>

namespace Gahyeon {

ProtocolNetworkIngressAdapter::ProtocolNetworkIngressAdapter(
    ProtocolIngressMailbox& ingress)
    : ingress_(ingress) {}

NetworkIngressDirective ProtocolNetworkIngressAdapter::OnEvent(
    ProtocolIngressEvent event) {
    const bool durable = event.Durable;
    switch (ingress_.TryPush(std::move(event))) {
        case ProtocolIngressResult::Accepted:
        case ProtocolIngressResult::EvictedEphemeral:
            return NetworkIngressDirective::Accepted;
        case ProtocolIngressResult::Full:
            return durable
                ? NetworkIngressDirective::ReconnectRequired
                : NetworkIngressDirective::DroppedEphemeral;
        case ProtocolIngressResult::Invalid:
            return NetworkIngressDirective::Invalid;
    }
    return NetworkIngressDirective::Invalid;
}

ProtocolNetworkEgressRuntime::ProtocolNetworkEgressRuntime(
    ReplayCursorRuntime& cursor,
    WorldActionCommandBridge& actions)
    : cursor_(cursor), actions_(actions),
      confirmedPersistedSequence_(cursor.LastAcknowledged()) {}

std::optional<OutboundProtocolCommand> ProtocolNetworkEgressRuntime::Next(
    Millis nowMs) const {
    if (nowMs < 0) return std::nullopt;
    if (confirmedPersistedSequence_ > cursor_.LastAcknowledged()) {
        return OutboundProtocolCommand{
            .Type = OutboundProtocolCommandType::ClientAck,
            .Sequence = confirmedPersistedSequence_,
            .Completion = std::nullopt,
        };
    }
    auto action = actions_.NextCommand(nowMs);
    if (!action.has_value()
        || !confirmedActionIds_.contains(action->Completion.ActionId)) return std::nullopt;
    return OutboundProtocolCommand{
        .Type = OutboundProtocolCommandType::ActionCompletion,
        .Sequence = 0,
        .Completion = action->Completion,
    };
}

bool ProtocolNetworkEgressRuntime::PersistenceConfirmed(Generation sequence) {
    if (sequence < confirmedPersistedSequence_
        || sequence > cursor_.SafeAcknowledgement()) return false;
    confirmedPersistedSequence_ = sequence;
    return true;
}

bool ProtocolNetworkEgressRuntime::ActionPersistenceConfirmed(
    const std::string& actionId) {
    if (!actions_.Outbox().Find(actionId).has_value()) return false;
    confirmedActionIds_.insert(actionId);
    return true;
}

NetworkEgressResult ProtocolNetworkEgressRuntime::MarkSent(
    const OutboundProtocolCommand& command,
    Millis nowMs) {
    if (nowMs < 0) return NetworkEgressResult::Invalid;
    if (command.Type == OutboundProtocolCommandType::ClientAck) {
        if (command.Completion.has_value() || command.Sequence <= 0) {
            return NetworkEgressResult::Invalid;
        }
        switch (cursor_.MarkAcknowledged(command.Sequence)) {
            case ReplayCursorResult::Advanced: return NetworkEgressResult::Sent;
            case ReplayCursorResult::Duplicate: return NetworkEgressResult::Stale;
            case ReplayCursorResult::Invalid:
            case ReplayCursorResult::HandshakeRequired:
                return NetworkEgressResult::Invalid;
        }
    }
    if (!command.Completion.has_value() || command.Sequence != 0) {
        return NetworkEgressResult::Invalid;
    }
    switch (actions_.CommandSent(command.Completion->ActionId, nowMs)) {
        case CompletionOutboxResult::Accepted: return NetworkEgressResult::Sent;
        case CompletionOutboxResult::Full: return NetworkEgressResult::Backpressured;
        case CompletionOutboxResult::Duplicate: return NetworkEgressResult::Stale;
        case CompletionOutboxResult::Invalid:
        case CompletionOutboxResult::NonMonotonic:
            return NetworkEgressResult::Invalid;
    }
    return NetworkEgressResult::Invalid;
}

WorldActionAckResult ProtocolNetworkEgressRuntime::ActionAcknowledged(
    WorldActionAcknowledgement acknowledgement) {
    const std::string actionId = acknowledgement.ActionId;
    const WorldActionAckResult result = actions_.ApplyAcknowledgement(
        std::move(acknowledgement));
    if (result == WorldActionAckResult::Acknowledged
        || result == WorldActionAckResult::Rejected) {
        confirmedActionIds_.erase(actionId);
    }
    return result;
}

} // namespace Gahyeon
