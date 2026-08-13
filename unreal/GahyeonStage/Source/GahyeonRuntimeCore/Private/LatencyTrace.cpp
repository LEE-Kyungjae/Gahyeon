#include "Gahyeon/LatencyTrace.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace Gahyeon {

LatencyTrace::LatencyTrace(
    std::size_t sampleCapacityPerMetric,
    std::size_t pendingCapacity)
    : sampleCapacityPerMetric_(sampleCapacityPerMetric),
      pendingCapacity_(pendingCapacity) {
    if (sampleCapacityPerMetric_ == 0 || pendingCapacity_ == 0) {
        throw std::invalid_argument("latency trace capacities must be positive");
    }
}

LatencyTraceResult LatencyTrace::Begin(
    LatencyMetric metric,
    std::uint64_t spanId,
    Millis nowMs) {
    if (spanId == 0 || nowMs < 0) return LatencyTraceResult::Invalid;
    if (!ObserveTime(nowMs)) return LatencyTraceResult::NonMonotonic;
    if (pending_.contains(spanId)) return LatencyTraceResult::Duplicate;
    if (pending_.size() >= pendingCapacity_) return LatencyTraceResult::Full;
    pending_.emplace(spanId, PendingSpan{metric, nowMs});
    return LatencyTraceResult::Started;
}

LatencyTraceResult LatencyTrace::End(std::uint64_t spanId, Millis nowMs) {
    if (spanId == 0 || nowMs < 0) return LatencyTraceResult::Invalid;
    if (!ObserveTime(nowMs)) return LatencyTraceResult::NonMonotonic;
    const auto found = pending_.find(spanId);
    if (found == pending_.end()) return LatencyTraceResult::Missing;
    const PendingSpan span = found->second;
    pending_.erase(found);
    return Record(span.Metric, nowMs - span.StartedAtMs);
}

LatencyTraceResult LatencyTrace::Cancel(std::uint64_t spanId) {
    if (spanId == 0) return LatencyTraceResult::Invalid;
    return pending_.erase(spanId) == 1
        ? LatencyTraceResult::Recorded
        : LatencyTraceResult::Missing;
}

LatencyTraceResult LatencyTrace::Record(
    LatencyMetric metric,
    Millis durationMs) {
    if (durationMs < 0) return LatencyTraceResult::Invalid;
    MetricSamples& metricSamples = samples_[metric];
    if (metricSamples.Ring.size() < sampleCapacityPerMetric_) {
        metricSamples.Ring.push_back(durationMs);
    } else {
        metricSamples.Ring[metricSamples.Next] = durationMs;
        metricSamples.Next = (metricSamples.Next + 1) % sampleCapacityPerMetric_;
    }
    ++metricSamples.Total;
    if (durationMs > Budget(metric)) ++metricSamples.Violations;
    return LatencyTraceResult::Recorded;
}

LatencySummary LatencyTrace::Summary(LatencyMetric metric) const {
    LatencySummary summary;
    summary.BudgetMs = Budget(metric);
    const auto found = samples_.find(metric);
    if (found == samples_.end() || found->second.Ring.empty()) return summary;
    const MetricSamples& metricSamples = found->second;
    summary.TotalCount = metricSamples.Total;
    summary.RetainedCount = metricSamples.Ring.size();
    summary.P50Ms = Percentile(metricSamples.Ring, 0.50);
    summary.P95Ms = Percentile(metricSamples.Ring, 0.95);
    summary.P99Ms = Percentile(metricSamples.Ring, 0.99);
    summary.WorstMs = *std::max_element(
        metricSamples.Ring.begin(), metricSamples.Ring.end());
    summary.BudgetViolations = metricSamples.Violations;
    summary.PassesP95 = summary.P95Ms <= summary.BudgetMs;
    return summary;
}

std::vector<Millis> LatencyTrace::Samples(LatencyMetric metric) const {
    const auto found = samples_.find(metric);
    if (found == samples_.end() || found->second.Ring.empty()) return {};
    const MetricSamples& metricSamples = found->second;
    if (metricSamples.Total <= metricSamples.Ring.size() || metricSamples.Next == 0) {
        return metricSamples.Ring;
    }
    std::vector<Millis> chronological;
    chronological.reserve(metricSamples.Ring.size());
    chronological.insert(
        chronological.end(), metricSamples.Ring.begin() + metricSamples.Next,
        metricSamples.Ring.end());
    chronological.insert(
        chronological.end(), metricSamples.Ring.begin(),
        metricSamples.Ring.begin() + metricSamples.Next);
    return chronological;
}

void LatencyTrace::ClearSamples(LatencyMetric metric) {
    samples_.erase(metric);
}

std::size_t LatencyTrace::PendingCount() const { return pending_.size(); }

Millis LatencyTrace::Budget(LatencyMetric metric) {
    switch (metric) {
        case LatencyMetric::VadToListening: return 100;
        case LatencyMetric::BargeInToAudioStop: return 150;
        case LatencyMetric::ReconnectToSnapshot: return 2'000;
        case LatencyMetric::VisemeOnsetOffset: return 80;
        case LatencyMetric::TranscriptToThinking: return 100;
        case LatencyMetric::FirstAudioPlayable: return 2'000;
        case LatencyMetric::VoiceEndToFinalTranscript: return 3'000;
    }
    return 0;
}

Millis LatencyTrace::Percentile(std::vector<Millis> values, double percentile) {
    std::sort(values.begin(), values.end());
    const double rank = std::ceil(percentile * static_cast<double>(values.size()));
    const std::size_t index = static_cast<std::size_t>(std::max(1.0, rank)) - 1;
    return values[std::min(index, values.size() - 1)];
}

bool LatencyTrace::ObserveTime(Millis nowMs) {
    if (lastObservedAtMs_.has_value() && nowMs < lastObservedAtMs_.value()) return false;
    lastObservedAtMs_ = nowMs;
    return true;
}

} // namespace Gahyeon
