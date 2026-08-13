#include "Gahyeon/ProtocolGameThreadDispatcher.h"

#include <stdexcept>
#include <utility>

namespace Gahyeon {

ProtocolGameThreadDispatcher::ProtocolGameThreadDispatcher(
    ProtocolIngressMailbox& ingress,
    ProtocolEventRuntime& runtime,
    ReplayCursorRuntime& cursor)
    : ingress_(ingress), runtime_(runtime), cursor_(cursor) {}

ProtocolDispatchBatch ProtocolGameThreadDispatcher::Drain(
    std::size_t maximumEvents,
    Millis nowMs) {
    if (maximumEvents == 0 || nowMs < 0) {
        throw std::invalid_argument("dispatcher drain arguments are invalid");
    }
    ProtocolDispatchBatch batch;
    if (!cursor_.IsWelcomed()) {
        batch.Backpressured = true;
        batch.SafeAcknowledgement = cursor_.SafeAcknowledgement();
        return batch;
    }
    for (std::size_t index = 0; index < maximumEvents; ++index) {
        auto event = ingress_.Pop();
        if (!event.has_value()) break;
        const ProtocolApplyResult result = runtime_.Apply(event->Message, nowMs);
        if (result.Status == ProtocolApplyStatus::Backpressured) {
            if (!ingress_.RequeueFront(std::move(event.value()))) {
                ++batch.IsolatedFailures;
            }
            batch.Backpressured = true;
            break;
        }
        if (result.Status == ProtocolApplyStatus::Invalid) ++batch.IsolatedFailures;
        else ++batch.Applied;
        if (event->Durable) cursor_.CompleteDurable(event->Sequence);
    }
    batch.AckReady = cursor_.AckPending();
    batch.SafeAcknowledgement = cursor_.SafeAcknowledgement();
    return batch;
}

} // namespace Gahyeon
