#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <unordered_map>
#include <vector>

namespace Gahyeon {

enum class LatencyMetric {
    VadToListening,
    BargeInToAudioStop,
    ReconnectToSnapshot,
    VisemeOnsetOffset,
    TranscriptToThinking,
    FirstAudioPlayable,
    VoiceEndToFinalTranscript,
};

struct LatencySummary {
    std::uint64_t TotalCount = 0;
    std::size_t RetainedCount = 0;
    Millis P50Ms = 0;
    Millis P95Ms = 0;
    Millis P99Ms = 0;
    Millis WorstMs = 0;
    Millis BudgetMs = 0;
    std::uint64_t BudgetViolations = 0;
    bool PassesP95 = false;
};

enum class LatencyTraceResult {
    Started,
    Recorded,
    Duplicate,
    Missing,
    Full,
    Invalid,
    NonMonotonic,
};

/** Game-thread-owned bounded monotonic latency recorder for the acceptance overlay. */
class GAHYEON_RUNTIME_CORE_API LatencyTrace {
public:
    explicit LatencyTrace(
        std::size_t sampleCapacityPerMetric = 2'048,
        std::size_t pendingCapacity = 256);

    LatencyTraceResult Begin(
        LatencyMetric metric,
        std::uint64_t spanId,
        Millis nowMs);
    LatencyTraceResult End(std::uint64_t spanId, Millis nowMs);
    LatencyTraceResult Cancel(std::uint64_t spanId);
    LatencyTraceResult Record(LatencyMetric metric, Millis durationMs);

    LatencySummary Summary(LatencyMetric metric) const;
    /** Retained samples in chronological order; intended for sealed physical acceptance export. */
    std::vector<Millis> Samples(LatencyMetric metric) const;
    void ClearSamples(LatencyMetric metric);
    std::size_t PendingCount() const;

    static Millis Budget(LatencyMetric metric);

private:
    struct PendingSpan {
        LatencyMetric Metric;
        Millis StartedAtMs;
    };

    struct MetricSamples {
        std::vector<Millis> Ring;
        std::size_t Next = 0;
        std::uint64_t Total = 0;
        std::uint64_t Violations = 0;
    };

    static Millis Percentile(std::vector<Millis> values, double percentile);
    bool ObserveTime(Millis nowMs);

    std::size_t sampleCapacityPerMetric_;
    std::size_t pendingCapacity_;
    std::optional<Millis> lastObservedAtMs_;
    std::unordered_map<std::uint64_t, PendingSpan> pending_;
    std::map<LatencyMetric, MetricSamples> samples_;
};

} // namespace Gahyeon
