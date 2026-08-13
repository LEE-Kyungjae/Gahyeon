#include "Gahyeon/ProtocolIngressMailbox.h"

#include <algorithm>
#include <stdexcept>
#include <utility>

namespace Gahyeon {

ProtocolIngressMailbox::ProtocolIngressMailbox(std::size_t capacity)
    : capacity_(capacity) {
    if (capacity_ == 0) throw std::invalid_argument("protocol mailbox capacity must be positive");
}

ProtocolIngressResult ProtocolIngressMailbox::TryPush(ProtocolIngressEvent event) {
    if (!Valid(event)) return ProtocolIngressResult::Invalid;
    std::scoped_lock lock(mutex_);
    ProtocolIngressResult result = ProtocolIngressResult::Accepted;
    if (queue_.size() >= capacity_) {
        if (!event.Durable) {
            ++droppedEphemeralCount_;
            return ProtocolIngressResult::Full;
        }
        auto ephemeral = std::find_if(queue_.begin(), queue_.end(),
            [](const auto& queued) { return !queued.Durable; });
        if (ephemeral == queue_.end()) {
            ++rejectedDurableCount_;
            return ProtocolIngressResult::Full;
        }
        queue_.erase(ephemeral);
        ++droppedEphemeralCount_;
        result = ProtocolIngressResult::EvictedEphemeral;
    }
    queue_.push_back(std::move(event));
    return result;
}

std::optional<ProtocolIngressEvent> ProtocolIngressMailbox::Pop() {
    std::scoped_lock lock(mutex_);
    if (queue_.empty()) return std::nullopt;
    ProtocolIngressEvent event = std::move(queue_.front());
    queue_.pop_front();
    return event;
}

bool ProtocolIngressMailbox::RequeueFront(ProtocolIngressEvent event) {
    if (!Valid(event)) return false;
    std::scoped_lock lock(mutex_);
    // One consumer-owned retry slot prevents loss if a producer fills the slot released
    // by Pop while the game thread is applying the event.
    if (queue_.size() > capacity_) return false;
    queue_.push_front(std::move(event));
    return true;
}

std::size_t ProtocolIngressMailbox::Size() const {
    std::scoped_lock lock(mutex_);
    return queue_.size();
}

std::uint64_t ProtocolIngressMailbox::DroppedEphemeralCount() const {
    std::scoped_lock lock(mutex_);
    return droppedEphemeralCount_;
}

std::uint64_t ProtocolIngressMailbox::RejectedDurableCount() const {
    std::scoped_lock lock(mutex_);
    return rejectedDurableCount_;
}

bool ProtocolIngressMailbox::Valid(const ProtocolIngressEvent& event) {
    return !event.Message.Type.empty()
        && ((event.Durable && event.Sequence > 0)
            || (!event.Durable && event.Sequence == 0));
}

} // namespace Gahyeon
