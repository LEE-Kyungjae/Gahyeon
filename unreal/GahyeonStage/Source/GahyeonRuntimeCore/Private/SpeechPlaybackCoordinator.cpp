#include "Gahyeon/SpeechPlaybackCoordinator.h"

#include <utility>

namespace Gahyeon {

SpeechPlaybackCoordinator::SpeechPlaybackCoordinator(
    RealtimeCharacterCoordinator& character,
    std::size_t queueCapacity,
    LipSyncRuntime* lipSync)
    : character_(character), queue_(queueCapacity), lipSync_(lipSync) {}

std::optional<std::string> SpeechPlaybackCoordinator::SetGeneration(
    Generation generation) {
    if (generation <= queue_.CurrentGeneration()) return std::nullopt;
    std::optional<std::string> interrupted;
    if (active_.has_value()) interrupted = active_->UtteranceId;
    active_.reset();
    playing_ = false;
    queue_.SetGeneration(generation);
    if (lipSync_ != nullptr) lipSync_->SetGeneration(generation);
    return interrupted;
}

SpeechEnqueueResult SpeechPlaybackCoordinator::Prepared(PreparedSpeech speech) {
    return queue_.Enqueue(std::move(speech));
}

SpeechEnqueueResult SpeechPlaybackCoordinator::SequenceEnded(
    SpeechSequenceEnd end,
    Millis nowMs) {
    const SpeechEnqueueResult result = queue_.MarkSequenceEnded(std::move(end));
    if (result == SpeechEnqueueResult::Accepted) MaybeEndSpeech(nowMs);
    return result;
}

std::optional<PreparedSpeech> SpeechPlaybackCoordinator::AcquireNext() {
    if (active_.has_value()) return std::nullopt;
    active_ = queue_.Pop();
    playing_ = false;
    return active_;
}

bool SpeechPlaybackCoordinator::PlaybackStarted(
    const std::string& utteranceId,
    Millis nowMs) {
    if (!active_.has_value() || active_->UtteranceId != utteranceId || playing_) {
        return false;
    }
    if (lipSync_ != nullptr
        && lipSync_->BeginPlayback(active_.value()) != LipSyncPrepareResult::Accepted) {
        active_.reset();
        return false;
    }
    if (!character_.SpeechStarted(
            active_->GenerationId, nowMs, active_->UtteranceId)) {
        if (lipSync_ != nullptr) lipSync_->EndPlayback(utteranceId);
        active_.reset();
        return false;
    }
    playing_ = true;
    return true;
}

bool SpeechPlaybackCoordinator::PlaybackFinished(
    const std::string& utteranceId,
    Millis nowMs) {
    return CompleteActive(utteranceId, nowMs);
}

bool SpeechPlaybackCoordinator::PlaybackFailed(
    const std::string& utteranceId,
    Millis nowMs) {
    return CompleteActive(utteranceId, nowMs);
}

bool SpeechPlaybackCoordinator::HasActiveAudio() const {
    return active_.has_value();
}

bool SpeechPlaybackCoordinator::IsPlaying() const {
    return playing_;
}

const SpeechQueue& SpeechPlaybackCoordinator::Queue() const {
    return queue_;
}

bool SpeechPlaybackCoordinator::CompleteActive(
    const std::string& utteranceId,
    Millis nowMs) {
    if (!active_.has_value() || active_->UtteranceId != utteranceId) return false;
    if (lipSync_ != nullptr) lipSync_->EndPlayback(utteranceId);
    active_.reset();
    playing_ = false;
    MaybeEndSpeech(nowMs);
    return true;
}

void SpeechPlaybackCoordinator::MaybeEndSpeech(Millis nowMs) {
    if (!active_.has_value() && queue_.SequenceDrained()) {
        character_.SpeechEnded(character_.Intents().CurrentGeneration(), nowMs);
    }
}

} // namespace Gahyeon
