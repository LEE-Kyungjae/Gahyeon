#include "Gahyeon/ConnectionConvergenceRuntime.h"

#include <limits>
#include <stdexcept>

namespace Gahyeon {

ConnectionConvergenceRuntime::ConnectionConvergenceRuntime(
    Millis deadlineMs,
    LatencyTrace* latency)
    : deadlineMs_(deadlineMs), latency_(latency) {
    if (deadlineMs_ <= 0) {
        throw std::invalid_argument("connection convergence deadline must be positive");
    }
}

ConnectionConvergenceResult ConnectionConvergenceRuntime::BeginConnection(Millis nowMs) {
    if (!IsMonotonic(nowMs)) return ConnectionConvergenceResult::InvalidTime;
    connectionStartedAtMs_ = nowMs;
    lastObservedAtMs_ = nowMs;
    state_ = ConnectionConvergenceState::AwaitingWelcome;
    ++attemptId_;
    if (attemptId_ == 0) ++attemptId_;
    if (latency_ != nullptr) {
        latency_->Begin(LatencyMetric::ReconnectToSnapshot, attemptId_ * 8 + 3, nowMs);
    }
    return ConnectionConvergenceResult::Accepted;
}

ConnectionConvergenceResult ConnectionConvergenceRuntime::Welcome(Millis nowMs) {
    if (!IsMonotonic(nowMs)) return ConnectionConvergenceResult::InvalidTime;
    if (state_ != ConnectionConvergenceState::AwaitingWelcome) {
        return ConnectionConvergenceResult::InvalidOrder;
    }
    lastObservedAtMs_ = nowMs;
    state_ = ConnectionConvergenceState::AwaitingSnapshot;
    return ConnectionConvergenceResult::Accepted;
}

ConnectionConvergenceResult ConnectionConvergenceRuntime::SnapshotApplied(Millis nowMs) {
    if (!IsMonotonic(nowMs)) return ConnectionConvergenceResult::InvalidTime;
    if (state_ != ConnectionConvergenceState::AwaitingSnapshot
        || !connectionStartedAtMs_.has_value()) {
        return ConnectionConvergenceResult::InvalidOrder;
    }
    lastObservedAtMs_ = nowMs;
    const Millis elapsed = nowMs - connectionStartedAtMs_.value();
    lastConvergenceMs_ = elapsed;
    if (latency_ != nullptr) latency_->End(attemptId_ * 8 + 3, nowMs);
    state_ = elapsed <= deadlineMs_
        ? ConnectionConvergenceState::Converged
        : ConnectionConvergenceState::TimedOut;
    return elapsed <= deadlineMs_
        ? ConnectionConvergenceResult::Accepted
        : ConnectionConvergenceResult::Late;
}

bool ConnectionConvergenceRuntime::Advance(Millis nowMs) {
    if (!IsMonotonic(nowMs)) return false;
    lastObservedAtMs_ = nowMs;
    if ((state_ != ConnectionConvergenceState::AwaitingWelcome
            && state_ != ConnectionConvergenceState::AwaitingSnapshot)
        || !connectionStartedAtMs_.has_value()) {
        return false;
    }
    const Millis started = connectionStartedAtMs_.value();
    const Millis deadline = started > std::numeric_limits<Millis>::max() - deadlineMs_
        ? std::numeric_limits<Millis>::max()
        : started + deadlineMs_;
    if (nowMs <= deadline) return false;
    state_ = ConnectionConvergenceState::TimedOut;
    if (latency_ != nullptr) latency_->End(attemptId_ * 8 + 3, nowMs);
    return true;
}

void ConnectionConvergenceRuntime::Disconnected() {
    if (latency_ != nullptr && attemptId_ != 0) {
        latency_->Cancel(attemptId_ * 8 + 3);
    }
    state_ = ConnectionConvergenceState::Disconnected;
    connectionStartedAtMs_.reset();
}

ConnectionConvergenceState ConnectionConvergenceRuntime::State() const { return state_; }

std::optional<Millis> ConnectionConvergenceRuntime::LastConvergenceMs() const {
    return lastConvergenceMs_;
}

bool ConnectionConvergenceRuntime::IsMonotonic(Millis nowMs) const {
    return nowMs >= 0
        && (!lastObservedAtMs_.has_value() || nowMs >= lastObservedAtMs_.value());
}

} // namespace Gahyeon
