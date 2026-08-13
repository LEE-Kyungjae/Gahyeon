#include "Gahyeon/MockCognitionRuntime.h"

#include <algorithm>
#include <limits>
#include <stdexcept>
#include <utility>

namespace Gahyeon {

MockCognitionRuntime::MockCognitionRuntime(std::size_t capacity)
    : capacity_(capacity) {
    if (capacity_ == 0) {
        throw std::invalid_argument("mock cognition capacity must be positive");
    }
}

MockCognitionScheduleResult MockCognitionRuntime::Schedule(
    Generation generation,
    std::string requestId,
    Millis submittedAtMs,
    Millis delayMs,
    MockCognitionOutcome outcome) {
    if (requestId.empty() || submittedAtMs < 0 || delayMs < 0
        || submittedAtMs > std::numeric_limits<Millis>::max() - delayMs) {
        ++rejectedCount_;
        return MockCognitionScheduleResult::Invalid;
    }
    if (lastObservedAtMs_.has_value() && submittedAtMs < lastObservedAtMs_.value()) {
        ++rejectedCount_;
        return MockCognitionScheduleResult::NonMonotonic;
    }
    if (pending_.size() >= capacity_) {
        ++rejectedCount_;
        return MockCognitionScheduleResult::Full;
    }
    pending_.push_back(Pending{
        .Completion = {
            .GenerationId = generation,
            .RequestId = std::move(requestId),
            .Outcome = outcome,
            .SubmittedAtMs = submittedAtMs,
            .DueAtMs = submittedAtMs + delayMs,
        },
        .Order = nextOrder_++,
    });
    return MockCognitionScheduleResult::Accepted;
}

std::vector<MockCognitionCompletion> MockCognitionRuntime::TakeDue(Millis nowMs) {
    if (nowMs < 0 || (lastObservedAtMs_.has_value() && nowMs < lastObservedAtMs_.value())) {
        ++rejectedCount_;
        return {};
    }
    lastObservedAtMs_ = nowMs;
    std::stable_sort(pending_.begin(), pending_.end(), [](const Pending& left, const Pending& right) {
        if (left.Completion.DueAtMs != right.Completion.DueAtMs) {
            return left.Completion.DueAtMs < right.Completion.DueAtMs;
        }
        return left.Order < right.Order;
    });
    const auto firstFuture = std::find_if(
        pending_.begin(), pending_.end(), [nowMs](const Pending& value) {
            return value.Completion.DueAtMs > nowMs;
        });
    std::vector<MockCognitionCompletion> due;
    due.reserve(static_cast<std::size_t>(firstFuture - pending_.begin()));
    for (auto item = pending_.begin(); item != firstFuture; ++item) {
        due.push_back(std::move(item->Completion));
    }
    pending_.erase(pending_.begin(), firstFuture);
    return due;
}

} // namespace Gahyeon
