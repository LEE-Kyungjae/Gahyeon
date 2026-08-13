#pragma once

#include "Gahyeon/IntentRuntime.h"
#include "Gahyeon/LatencyTrace.h"

#include <optional>

namespace Gahyeon {

enum class ConnectionConvergenceState {
    Disconnected,
    AwaitingWelcome,
    AwaitingSnapshot,
    Converged,
    TimedOut,
};

enum class ConnectionConvergenceResult {
    Accepted,
    InvalidOrder,
    InvalidTime,
    Late,
};

/** Monotonic acceptance tracker for reconnect -> authoritative snapshot convergence. */
class GAHYEON_RUNTIME_CORE_API ConnectionConvergenceRuntime {
public:
    explicit ConnectionConvergenceRuntime(
        Millis deadlineMs = 2'000,
        LatencyTrace* latency = nullptr);

    ConnectionConvergenceResult BeginConnection(Millis nowMs);
    ConnectionConvergenceResult Welcome(Millis nowMs);
    ConnectionConvergenceResult SnapshotApplied(Millis nowMs);
    bool Advance(Millis nowMs);
    void Disconnected();

    ConnectionConvergenceState State() const;
    std::optional<Millis> LastConvergenceMs() const;

private:
    bool IsMonotonic(Millis nowMs) const;

    Millis deadlineMs_;
    ConnectionConvergenceState state_ = ConnectionConvergenceState::Disconnected;
    std::optional<Millis> connectionStartedAtMs_;
    std::optional<Millis> lastObservedAtMs_;
    std::optional<Millis> lastConvergenceMs_;
    LatencyTrace* latency_ = nullptr;
    std::uint64_t attemptId_ = 0;
};

} // namespace Gahyeon
