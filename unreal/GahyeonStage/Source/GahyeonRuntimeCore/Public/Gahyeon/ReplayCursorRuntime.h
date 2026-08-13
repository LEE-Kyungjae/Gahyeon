#pragma once

#include "Gahyeon/IntentRuntime.h"

namespace Gahyeon {

enum class ReplayCursorResult {
    Advanced,
    Duplicate,
    Invalid,
    HandshakeRequired,
};

/** Game-thread-owned durable replay and acknowledgement watermark. */
class GAHYEON_RUNTIME_CORE_API ReplayCursorRuntime {
public:
    explicit ReplayCursorRuntime(Generation persistedSequence = 0);

    void BeginConnection();
    ReplayCursorResult Welcome(Generation resumeAfter);
    ReplayCursorResult CompleteDurable(Generation sequence);
    ReplayCursorResult ObserveScanCursor(Generation scannedThrough);
    ReplayCursorResult MarkAcknowledged(Generation sequence);

    Generation PersistedSequence() const;
    Generation SafeAcknowledgement() const;
    Generation LastAcknowledged() const;
    bool IsWelcomed() const;
    bool AckPending() const;

private:
    Generation persistedSequence_;
    Generation safeAcknowledgement_;
    Generation lastAcknowledged_;
    bool welcomed_ = false;
};

} // namespace Gahyeon
