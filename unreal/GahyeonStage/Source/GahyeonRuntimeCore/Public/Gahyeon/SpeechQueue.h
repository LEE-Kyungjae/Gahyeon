#pragma once

#include "Gahyeon/IntentRuntime.h"

#include <cstddef>
#include <deque>
#include <optional>
#include <string>
#include <unordered_set>
#include <vector>

namespace Gahyeon {

struct VisemeCue {
    std::string Semantic;
    Millis AtMs = 0;
    Millis DurationMs = 0;
    double Weight = 1.0;

    bool operator==(const VisemeCue&) const = default;
};

struct PreparedSpeech {
    Generation GenerationId = 0;
    std::string UtteranceId;
    int UtteranceIndex = 0;
    int SegmentIndex = 0;
    bool FinalSegment = false;
    std::string AudioUrl;
    std::string MimeType;
    std::vector<VisemeCue> Visemes;
};

struct SpeechSequenceEnd {
    Generation GenerationId = 0;
    int UtteranceCount = 0;
    std::string Outcome;
};

enum class SpeechEnqueueResult {
    Accepted,
    Duplicate,
    Stale,
    Full,
    Invalid,
};

/** Game-thread-owned ordered audio queue, separate from latest-value intent arbitration. */
class GAHYEON_RUNTIME_CORE_API SpeechQueue {
public:
    explicit SpeechQueue(std::size_t capacity = 16);

    void SetGeneration(Generation generation);
    Generation CurrentGeneration() const;
    SpeechEnqueueResult Enqueue(PreparedSpeech speech);
    SpeechEnqueueResult MarkSequenceEnded(SpeechSequenceEnd end);
    std::optional<PreparedSpeech> Pop();
    std::size_t Size() const;
    bool SequenceEnded() const;
    bool SequenceDrained() const;
    int ExpectedUtterances() const;

private:
    const std::size_t capacity_;
    Generation generation_ = 0;
    std::deque<PreparedSpeech> queue_;
    std::unordered_set<std::string> utteranceIds_;
    bool sequenceEnded_ = false;
    int expectedUtterances_ = 0;
};

} // namespace Gahyeon
