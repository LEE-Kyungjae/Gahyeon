#include "Gahyeon/SpeechQueue.h"

#include <utility>

namespace Gahyeon {

SpeechQueue::SpeechQueue(std::size_t capacity)
    : capacity_(capacity) {}

void SpeechQueue::SetGeneration(Generation generation) {
    if (generation <= generation_) return;
    generation_ = generation;
    queue_.clear();
    utteranceIds_.clear();
    sequenceEnded_ = false;
    expectedUtterances_ = 0;
}

Generation SpeechQueue::CurrentGeneration() const {
    return generation_;
}

SpeechEnqueueResult SpeechQueue::Enqueue(PreparedSpeech speech) {
    if (speech.UtteranceId.empty() || speech.UtteranceIndex < 0 || speech.SegmentIndex < 0) {
        return SpeechEnqueueResult::Invalid;
    }
    if (speech.GenerationId != generation_) {
        return SpeechEnqueueResult::Stale;
    }
    if (utteranceIds_.contains(speech.UtteranceId)) {
        return SpeechEnqueueResult::Duplicate;
    }
    if (queue_.size() >= capacity_) {
        return SpeechEnqueueResult::Full;
    }
    utteranceIds_.insert(speech.UtteranceId);
    queue_.push_back(std::move(speech));
    return SpeechEnqueueResult::Accepted;
}

SpeechEnqueueResult SpeechQueue::MarkSequenceEnded(SpeechSequenceEnd end) {
    if (end.UtteranceCount < 0
        || (end.Outcome != "completed" && end.Outcome != "failed")) {
        return SpeechEnqueueResult::Invalid;
    }
    if (end.GenerationId != generation_) return SpeechEnqueueResult::Stale;
    sequenceEnded_ = true;
    expectedUtterances_ = end.UtteranceCount;
    return SpeechEnqueueResult::Accepted;
}

std::optional<PreparedSpeech> SpeechQueue::Pop() {
    if (queue_.empty()) return std::nullopt;
    PreparedSpeech speech = std::move(queue_.front());
    queue_.pop_front();
    return speech;
}

std::size_t SpeechQueue::Size() const {
    return queue_.size();
}

bool SpeechQueue::SequenceEnded() const {
    return sequenceEnded_;
}

bool SpeechQueue::SequenceDrained() const {
    return sequenceEnded_ && queue_.empty();
}

int SpeechQueue::ExpectedUtterances() const {
    return expectedUtterances_;
}

} // namespace Gahyeon
