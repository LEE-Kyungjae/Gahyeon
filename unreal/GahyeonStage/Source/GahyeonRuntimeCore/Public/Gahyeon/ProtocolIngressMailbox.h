#pragma once

#include "Gahyeon/ProtocolMessageTranslator.h"

#include <cstddef>
#include <deque>
#include <mutex>
#include <optional>

namespace Gahyeon {

struct ProtocolIngressEvent {
    ProtocolMessage Message;
    bool Durable = false;
    Generation Sequence = 0;
};

enum class ProtocolIngressResult { Accepted, EvictedEphemeral, Full, Invalid };

/** Bounded network-thread to game-thread handoff; durable replay is never silently evicted. */
class GAHYEON_RUNTIME_CORE_API ProtocolIngressMailbox {
public:
    explicit ProtocolIngressMailbox(std::size_t capacity = 512);

    ProtocolIngressResult TryPush(ProtocolIngressEvent event);
    std::optional<ProtocolIngressEvent> Pop();
    bool RequeueFront(ProtocolIngressEvent event);
    std::size_t Size() const;
    std::uint64_t DroppedEphemeralCount() const;
    std::uint64_t RejectedDurableCount() const;

private:
    static bool Valid(const ProtocolIngressEvent& event);

    const std::size_t capacity_;
    mutable std::mutex mutex_;
    std::deque<ProtocolIngressEvent> queue_;
    std::uint64_t droppedEphemeralCount_ = 0;
    std::uint64_t rejectedDurableCount_ = 0;
};

} // namespace Gahyeon
