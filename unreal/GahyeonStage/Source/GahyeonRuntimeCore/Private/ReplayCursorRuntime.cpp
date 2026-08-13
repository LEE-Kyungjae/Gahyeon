#include "Gahyeon/ReplayCursorRuntime.h"

namespace Gahyeon {

ReplayCursorRuntime::ReplayCursorRuntime(Generation persistedSequence)
    : persistedSequence_(persistedSequence),
      safeAcknowledgement_(persistedSequence),
      lastAcknowledged_(persistedSequence) {}

void ReplayCursorRuntime::BeginConnection() {
    welcomed_ = false;
    safeAcknowledgement_ = persistedSequence_;
    lastAcknowledged_ = persistedSequence_;
}

ReplayCursorResult ReplayCursorRuntime::Welcome(Generation resumeAfter) {
    if (resumeAfter != persistedSequence_) {
        return ReplayCursorResult::Invalid;
    }
    welcomed_ = true;
    return ReplayCursorResult::Advanced;
}

ReplayCursorResult ReplayCursorRuntime::CompleteDurable(Generation sequence) {
    if (!welcomed_) {
        return ReplayCursorResult::HandshakeRequired;
    }
    if (sequence == 0) {
        return ReplayCursorResult::Invalid;
    }
    if (sequence <= safeAcknowledgement_) {
        return ReplayCursorResult::Duplicate;
    }
    safeAcknowledgement_ = sequence;
    persistedSequence_ = sequence;
    return ReplayCursorResult::Advanced;
}

ReplayCursorResult ReplayCursorRuntime::ObserveScanCursor(Generation scannedThrough) {
    if (!welcomed_) {
        return ReplayCursorResult::HandshakeRequired;
    }
    if (scannedThrough < safeAcknowledgement_) {
        return ReplayCursorResult::Invalid;
    }
    if (scannedThrough == safeAcknowledgement_) {
        return ReplayCursorResult::Duplicate;
    }
    safeAcknowledgement_ = scannedThrough;
    persistedSequence_ = scannedThrough;
    return ReplayCursorResult::Advanced;
}

ReplayCursorResult ReplayCursorRuntime::MarkAcknowledged(Generation sequence) {
    if (!welcomed_) {
        return ReplayCursorResult::HandshakeRequired;
    }
    if (sequence < lastAcknowledged_ || sequence > safeAcknowledgement_) {
        return ReplayCursorResult::Invalid;
    }
    if (sequence == lastAcknowledged_) {
        return ReplayCursorResult::Duplicate;
    }
    lastAcknowledged_ = sequence;
    return ReplayCursorResult::Advanced;
}

Generation ReplayCursorRuntime::PersistedSequence() const {
    return persistedSequence_;
}

Generation ReplayCursorRuntime::SafeAcknowledgement() const {
    return safeAcknowledgement_;
}

Generation ReplayCursorRuntime::LastAcknowledged() const {
    return lastAcknowledged_;
}

bool ReplayCursorRuntime::IsWelcomed() const {
    return welcomed_;
}

bool ReplayCursorRuntime::AckPending() const {
    return safeAcknowledgement_ > lastAcknowledged_;
}

} // namespace Gahyeon
