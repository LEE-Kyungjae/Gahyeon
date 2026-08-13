#pragma once

#include "Gahyeon/ProtocolEventRuntime.h"
#include "Gahyeon/ProtocolIngressMailbox.h"
#include "Gahyeon/ReplayCursorRuntime.h"

#include <cstddef>

namespace Gahyeon {

struct ProtocolDispatchBatch {
    std::size_t Applied = 0;
    std::size_t IsolatedFailures = 0;
    bool Backpressured = false;
    bool AckReady = false;
    Generation SafeAcknowledgement = 0;
};

/** Sole game-thread consumer that advances durable cursor only after isolated application. */
class GAHYEON_RUNTIME_CORE_API ProtocolGameThreadDispatcher {
public:
    ProtocolGameThreadDispatcher(
        ProtocolIngressMailbox& ingress,
        ProtocolEventRuntime& runtime,
        ReplayCursorRuntime& cursor);

    ProtocolDispatchBatch Drain(std::size_t maximumEvents, Millis nowMs);

private:
    ProtocolIngressMailbox& ingress_;
    ProtocolEventRuntime& runtime_;
    ReplayCursorRuntime& cursor_;
};

} // namespace Gahyeon
