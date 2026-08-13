#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace Gahyeon {

enum class MockCognitionOutcome : std::uint8_t {
    Completed,
    Failed,
};

enum class MockCognitionScheduleResult : std::uint8_t {
    Accepted,
    Full,
    Invalid,
    NonMonotonic,
};

struct MockCognitionCompletion {
    Generation GenerationId = 0;
    std::string RequestId;
    MockCognitionOutcome Outcome = MockCognitionOutcome::Completed;
    Millis SubmittedAtMs = 0;
    Millis DueAtMs = 0;
};

/**
 * Bounded deterministic fault harness for VS-5.
 *
 * It owns no thread and never touches presentation state. Tests, UE Automation,
 * or PIE diagnostics advance it from a monotonic clock and feed due completions
 * through the same generation admission used by real Cognition callbacks.
 */
class GAHYEON_RUNTIME_CORE_API MockCognitionRuntime final {
public:
    explicit MockCognitionRuntime(std::size_t capacity = 32);

    MockCognitionScheduleResult Schedule(
        Generation generation,
        std::string requestId,
        Millis submittedAtMs,
        Millis delayMs,
        MockCognitionOutcome outcome = MockCognitionOutcome::Completed);

    std::vector<MockCognitionCompletion> TakeDue(Millis nowMs);
    std::size_t PendingCount() const { return pending_.size(); }
    std::size_t RejectedCount() const { return rejectedCount_; }

private:
    struct Pending {
        MockCognitionCompletion Completion;
        std::uint64_t Order = 0;
    };

    std::size_t capacity_;
    std::vector<Pending> pending_;
    std::optional<Millis> lastObservedAtMs_;
    std::uint64_t nextOrder_ = 0;
    std::size_t rejectedCount_ = 0;
};

} // namespace Gahyeon
